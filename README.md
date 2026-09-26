# quotes

One endpoint, four kinds of test, and a CI pipeline. Small enough to read in a sitting,
complete enough to copy the shape from.

```
POST /api/v1/quotes
{"customerId":"cust-1","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}

201 Created
Location: http://localhost:8080/api/v1/quotes/<uuid>
{"quoteId":"...","customerId":"cust-1","productCode":"WIDGET","amount":10000.00,
 "currency":"USD","termMonths":12,"annualRatePercent":4.25,"total":10425.00,
 "createdAt":"2026-01-15T10:30:00Z"}
```

The flow is: validate the command → fetch a rate from the downstream rate service
(`GET {baseUrl}/rates/{productCode}?currency=USD`) → price it → persist it.

Pricing is simple interest, rounded half-up to whole cents:

```
total = amount * (1 + annualPercentage / 100 * termMonths / 12)
```

## Running it

```bash
docker compose up -d             # local PostgreSQL, for bootRun
./gradlew bootRun                # needs the database above and a rate service on :8081
./gradlew test                   # unit tier — needs nothing at all
./gradlew integrationTest        # Spring slices, adapter test, real-Postgres checks
./gradlew systemTest             # black box over real HTTP
./gradlew check                  # every tier, plus the guards
./gradlew build                  # check, then the jar
```

**PostgreSQL is now the only database, in every environment.** There is no in-memory database in
tests, so a migration or query that PostgreSQL rejects fails in the build instead of in production —
and the migration is free to use PostgreSQL features. The tests start their own throwaway container,
so a test run never touches whatever is in your local `docker compose` database.

The trade-off is explicit: **`check` requires Docker.** There is no longer a subset of the suite
that can meaningfully run without it. `./gradlew test` (the unit tier) still needs nothing.

The rate service base URL and HTTP timeouts come from `app.rate.*` in `application.yml` and can be
overridden with environment variables.

## Layout

```
src/main/java/com/example/quotes
├── rate/                      outbound side
│   ├── RateGateway            the port the use case depends on
│   ├── HttpRateGateway        RestClient adapter; translates every failure to RateUnavailableException
│   └── RateProperties         @ConfigurationProperties, validated at startup
├── quote/
│   ├── QuoteController        POST /api/v1/quotes
│   ├── QuoteRequest/Response  records, request is validated
│   ├── QuoteService           orchestrates lookup → price → save
│   ├── QuoteCalculator        pure arithmetic
│   ├── Quote                  JPA entity, created through one factory
│   └── QuoteRepository        Spring Data
└── web/ApiExceptionHandler    domain failures → RFC 9457 problem responses
```

## The four tiers

A tier is a Gradle **JVM Test Suite** — its own source set, dependencies and task. The tier is
therefore a property of *where the code lives*, not a tag string on a class, and that is what lets
the unit tier simply not have Spring test support on its classpath.

| Tier | Source set | Dependencies it adds | What it proves |
| --- | --- | --- | --- |
| Unit | `src/test` | JUnit, AssertJ, Mockito, ArchUnit — **no Spring test support** | Arithmetic, orchestration, architecture rules. Milliseconds, no Docker. |
| Integration | `src/integrationTest` | `-webmvc-test`, `-data-jpa-test`, WireMock, Testcontainers | Slices, the adapter's wire contract, and that the migration really runs on PostgreSQL. |
| System | `src/systemTest` | `-webmvc-test`, `-restclient-test`, Testcontainers | Black box over real HTTP on the real database. Cannot see the database *from code*. |

Regression is a *purpose*, not a tier: `QuotePricingRegressionTest` is a unit test and
`QuoteWireContractRegressionTest` is a black-box test, so each lives where its mechanism belongs.

**See [docs/TEST-ARCHITECTURE.md](docs/TEST-ARCHITECTURE.md)** for the measured context counts,
the guards and the design rationale.

**Unit.** `QuoteCalculator` has no dependencies, so it can be tested exhaustively — including
the half-up rounding case that banker's rounding would get wrong. `QuoteServiceTest` mocks the
gateway and repository and injects a `Clock.fixed(...)`, so even `createdAt` is asserted exactly.

**Integration.** Each slice is as thin as it can be and still be real. `@WebMvcTest` runs only
the web layer, `@DataJpaTest` only JPA + Flyway, and `HttpRateGatewayTest` stubs the upstream with
WireMock to check the wire format, status handling and read timeout.

**The real database.** `QuoteRepositoryTest` is the only test whose job is to prove the entity
mapping and the Flyway migration agree, so it runs on real PostgreSQL, not a stand-in. Two of its
tests step outside Hibernate and talk to the container over plain JDBC to confirm the engine really
is PostgreSQL and that the table came from the migration. One container is started lazily and shared
for the whole run; the container-based tier that used to be separate is gone, because once every
database test runs on PostgreSQL there was nothing left to distinguish it.

**System.** `QuoteApiIntegrationTest` does the whole thing over real HTTP, and
`QuoteWireContractRegressionTest` freezes the JSON contract against a golden file. Both inherit one
composed configuration (`@FullStackTest`) and one inherited `@DynamicPropertySource`, so they share
a single `ApplicationContext`. Neither can see `QuoteRepository` — that classpath constraint is what
keeps the tier honest, and it is why the service grew a read endpoint.

**Regression.** These are not tests of correctness, they are tripwires. The golden pricing table
and the golden JSON body in `src/testFixtures/resources/golden/` record what the service does
*today*. If one fails, you have changed the price or the contract — a decision to make
deliberately, not an assertion to quietly update. The JSON comparison ignores whitespace but not
number scale, so `10425.00` turning into `10425.0` is caught.

**Throwaway infrastructure.** There is no longer a separate tier for it. Every database test starts
a PostgreSQL container through one shared, lazily started fixture, so the container exists for the
run and is destroyed with it. The tier that used to exist purely to be "the real database" had
nothing unique left once everything else moved onto PostgreSQL too.

## Choices worth calling out

- **A gateway interface, not a `RestClient` field.** `QuoteService` depends on `RateGateway`, so
  unit tests need no HTTP and the adapter has one place to translate failures.
- **The use case is not `@Transactional`.** The remote rate lookup would otherwise hold a database
  connection open for the length of an HTTP call. The single insert is already transactional
  inside the repository.
- **`BigDecimal` with an explicit scale and `RoundingMode`,** never `double`, for money.
- **Flyway owns the schema,** `ddl-auto: validate` proves the entity mapping still matches it, and
  `open-in-view: false` keeps lazy loading out of the view layer.
- **`@ConfigurationProperties` is validated** so a bad `base-url` fails at startup, not on the
  first request.
- **A `Clock` bean instead of `Instant.now()`** so time is injectable in tests.
- **Errors are RFC 9457 problem details,** and an unreachable rate service is a `503`, not a `500`.
- **A `@Version` column** on the entity for optimistic locking.

## CI

`.github/workflows/ci.yml` is a single job running `./gradlew build` — every tier, then coverage
verification — and uploading the test and JaCoCo reports as artifacts. The database tests start their
own PostgreSQL container on the runner's Docker daemon, so there is no separate container job and no
Docker-free subset to keep in step. Runs are cancelled when a newer commit lands on the same ref, and
Dependabot keeps the Gradle dependencies and the workflow actions current.

Coverage is enforced at 85% line coverage over the main source set by
`jacocoTestCoverageVerification`, which is part of `check`. It currently sits at 98.2% — every class
is at 100% except `QuotesApplication.main`, which no test invokes.

## Not included, on purpose

Authentication, pagination, retries/circuit breaking on the rate call, and contract testing against
a real provider. `docs/TEST-ARCHITECTURE.md` lists the peer-review recommendations still outstanding
— replacing the log-parsed context guard with a `TestExecutionListener`, a data-isolation policy for
the now-shared container, and a flake/quarantine policy — with the reasoning for each.
