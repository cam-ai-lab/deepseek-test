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
./gradlew bootRun                 # needs a rate service on http://localhost:8081
./gradlew test                    # unit tier
./gradlew integrationTest         # Spring slices + the adapter contract test
./gradlew systemTest              # black box over real HTTP
./gradlew ephemeralTest           # real Postgres in a container (needs Docker)
./gradlew check                   # every tier except ephemeral, plus the guards
./gradlew build                   # check, then the jar
```

`check` is deliberately Docker-free, so it runs on any laptop; the ephemeral tier is opt-in and
gets its own CI job. The rate service base URL and HTTP timeouts come from `app.rate.*` in
`application.yml` and can be overridden with environment variables.

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
| Unit | `src/test` | JUnit, AssertJ, Mockito, ArchUnit — **no Spring test support** | Arithmetic, orchestration, architecture rules. Milliseconds. |
| Integration | `src/integrationTest` | `-webmvc-test`, `-data-jpa-test`, WireMock | Slices, and the adapter's wire contract. H2. |
| System | `src/systemTest` | `-webmvc-test`, `-restclient-test` | Black box over real HTTP; cannot see the database. |
| Ephemeral | `src/ephemeralTest` | the above + Testcontainers | The same migrations against real Postgres. |

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

**System.** `QuoteApiIntegrationTest` does the whole thing over real HTTP, and
`QuoteWireContractRegressionTest` freezes the JSON contract against a golden file. Both inherit one
composed configuration (`@FullStackTest`) and one inherited `@DynamicPropertySource`, so they share
a single `ApplicationContext`: the second class costs 0.02s against the first's 5.46s. Neither can
see `QuoteRepository` — that classpath constraint is what keeps the tier honest, and it is why the
service grew a read endpoint.

**Regression.** These are not tests of correctness, they are tripwires. The golden pricing table
and the golden JSON body in `src/testFixtures/resources/golden/` record what the service does
*today*. If one fails, you have changed the price or the contract — a decision to make
deliberately, not an assertion to quietly update. The JSON comparison ignores whitespace but not
number scale, so `10425.00` turning into `10425.0` is caught.

**Ephemeral.** `QuoteEphemeralPostgresTest` runs the same migrations and the same code against a
throwaway Postgres container, asserting up front that it really is Postgres and not an in-memory
stand-in. The container is destroyed with the test run, so there is no shared state to clean up.
`@Testcontainers(disabledWithoutDocker = true)` means it skips rather than fails where Docker
is unavailable.

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

`.github/workflows/ci.yml` has two jobs: `build` runs `./gradlew build` (every tier except
`ephemeral`, then coverage verification) and uploads the test and JaCoCo reports as artifacts;
`ephemeral` runs `./gradlew ephemeralTest` on the runner's Docker daemon. Runs are cancelled when
a newer commit lands on the same ref, and Dependabot keeps the Gradle dependencies and the
workflow actions current.

Coverage is enforced at 85% line coverage over the main source set by
`jacocoTestCoverageVerification`, which is part of `check`. It currently sits at 98.2% — every class
is at 100% except `QuotesApplication.main`, which no test invokes.

## Not included, on purpose

Authentication, pagination, retries/circuit breaking on the rate call, and contract testing against
a real provider. `docs/TEST-ARCHITECTURE.md` also lists the recommendations from a peer review that
were deliberately deferred — running the slices on Testcontainers Postgres instead of H2, replacing
the log-parsed context guard with a `TestExecutionListener`, and a flake/quarantine policy — with
the reasoning for each.
