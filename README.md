# quotes

One endpoint, three test tiers — unit, integration and a Cucumber black-box suite against the real
Docker image — and a CI pipeline. Small enough to read in a sitting, complete enough to copy the
shape from. A book that uses this repository as its lab lives in [`book/`](book/).

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
./gradlew :test                  # unit tier — needs nothing at all
./gradlew integrationTest        # Spring slices, adapter test, real-Postgres checks
./gradlew :blackbox:test         # black box: builds the image, runs Cucumber against it
./gradlew :blackbox:test -PdryRun   # checks every Cucumber step has a definition; no Docker
./gradlew check                  # every tier, plus the guards
./gradlew build                  # check, then the jar
```

**PostgreSQL is now the only database, in every environment.** There is no in-memory database in
tests, so a migration or query that PostgreSQL rejects fails in the build instead of in production —
and the migration is free to use PostgreSQL features. The tests start their own throwaway container,
so a test run never touches whatever is in your local `docker compose` database.

The trade-off is explicit: **`check` requires Docker.** There is no longer a subset of the suite
that can meaningfully run without it. `./gradlew :test` (the unit tier) still needs nothing. Note the
leading `:` — plain `./gradlew test` also runs `:blackbox:test`, which builds a Docker image.

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

blackbox/                      the black-box suite; no dependency on the code above
├── src/main/.../BlackboxStack starts compose.blackbox.yaml via Testcontainers
├── src/test/                  Cucumber features, steps, a read-only DB helper
└── src/gatling/               nightly performance smoke test
Dockerfile                     the image the black-box suite tests
compose.blackbox.yaml          app + PostgreSQL + WireMock (as the rate service)
docker/postgres-init/          a SELECT-only role for black-box assertions
```

## The three tiers

The unit and integration tiers are Gradle **JVM Test Suites** — each its own source set,
dependencies and task. The tier is therefore a property of *where the code lives*, not a tag string
on a class, and that is what lets the unit tier simply not have Spring test support on its classpath.
The black-box tier goes one step further: it is a separate Gradle **subproject** that cannot see the
application's code at all.

| Tier | Location | Dependencies it adds | What it proves |
| --- | --- | --- | --- |
| Unit | `src/test` | JUnit, AssertJ, Mockito, ArchUnit — **no Spring test support** | Arithmetic, orchestration, architecture rules. Milliseconds, no Docker. |
| Integration | `src/integrationTest` | `-webmvc-test`, `-data-jpa-test`, WireMock, Testcontainers | Slices, the adapter's wire contract, and that the migration really runs on PostgreSQL. |
| Black box | `blackbox/` | Cucumber, REST-assured, WireMock client, Testcontainers — **no application code** | The real image, configured only by environment variables, over HTTP: behaviour, wire contract, shipped configuration. |

Regression is a *purpose*, not a tier: `QuotePricingRegressionTest` is a unit test and
`wire_contract.feature` is a black-box test, so each lives where its mechanism belongs.

The black-box suite replaced an in-JVM "system" tier that started the app with `@SpringBootTest` and
asserted through the app's own `QuoteResponse` type — which made it blind to wire-level changes such as
number scale. The tag `before-blackbox` marks the repository before that migration;
[`BLACKBOX-TEST-PLAN.md`](BLACKBOX-TEST-PLAN.md) is the plan it followed.

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

**Black box.** `blackbox/` builds the real Docker image and starts it with PostgreSQL and a WireMock
container standing in for the rate service. Cucumber scenarios talk to it only over HTTP, program the
stub through WireMock's admin API, and may *read* the database through a role that can only `SELECT`.
Assertions compare **raw JSON tokens**, so `10425.00` becoming `10425.0` is a failure. Every scenario
uses its own `customerId`, so nothing is ever cleaned up. `verifyBlackboxIsolation` fails the build if
the module ever gains a dependency on the application. See [`blackbox/README.md`](blackbox/README.md).

**Known bugs.** `blackbox/.../known_bugs.feature` states three defects as the *correct* behaviour,
tagged `@known-bug` and excluded by default: POST and GET return the same quote with different number
scales, the largest valid amount overflows the `total` column into a 500, and an unknown product is
reported as a 503 instead of a 4xx. CI reports on every run how many still reproduce; when one is
fixed, delete its tag. Run them with `./gradlew :blackbox:test -Ptags=@known-bug`.

**Regression.** These are not tests of correctness, they are tripwires. The golden pricing table
records what the service charges *today*; `wire_contract.feature` pins the JSON field set and the
exact number tokens. If one fails, you have changed the price or the contract — a decision to make
deliberately, not an assertion to quietly update.

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

`.github/workflows/ci.yml` runs two jobs in parallel, both required:

- **Unit and integration** — `./gradlew check -x :blackbox:test`: the in-process tiers, the build
  guards and coverage verification, plus a Cucumber **dry run** that fails fast on any step without a
  definition.
- **Black box** — `./gradlew :blackbox:test`: builds the image and runs the scenarios. A final,
  non-blocking step runs the `@known-bug` scenarios and reports how many still reproduce.

`perf-smoke.yml` runs the Gatling smoke test nightly and on demand; it never blocks a merge, because
latency on shared runners is too noisy to gate on. Runs are cancelled when a newer commit lands on
the same ref, and Dependabot keeps the Gradle dependencies and the workflow actions current.

Coverage is enforced at 85% line coverage over the main source set by
`jacocoTestCoverageVerification`, which is part of `check`. It is measured over the unit and
integration tiers only: the black-box suite runs the app in a separate container, which JaCoCo in the
test JVM cannot see.

## Not included, on purpose

Authentication, pagination, retries/circuit breaking on the rate call, and consumer-driven contract
testing against the real rate service. `docs/TEST-ARCHITECTURE.md` lists the peer-review recommendations still outstanding
— replacing the log-parsed context guard with a `TestExecutionListener`, a data-isolation policy for
the now-shared container, and a flake/quarantine policy — with the reasoning for each.
