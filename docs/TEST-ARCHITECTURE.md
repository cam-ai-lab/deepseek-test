# Test architecture for a heavily developed repo

The design target: a service that will grow past several hundred test classes, written by many
people, where test *runtime* and test *trust* are what decay. This document is the design; the code
beside it is the reference implementation. Everything claimed here was measured on this repo.

## The problem, stated precisely

At scale, a Spring test suite's cost is not proportional to the number of tests. It is proportional
to the number of **distinct `ApplicationContext`s** the suite builds, multiplied by the cost of one
build (~1-2s). Measured before this restructure: one heavyweight context was 74% of wall-clock, and
the pure unit tests were ~1%.

Two consequences drive everything:

1. Anything that *multiplies context count* multiplies CI cost.
2. Anything that lets a tier drift from its intent is undetectable until it is expensive.

So: **keep context count flat as tests grow**, and **make the invariants that matter impossible to
violate silently**.

## Layout

```
app/
  src/main/java/                    production code
  src/testFixtures/java/            shared test vocabulary: builders, fakes, a WireMock stub
  src/test/java/                    UNIT        - JUnit + AssertJ + Mockito only
  src/integrationTest/java/         INTEGRATION - Spring slices, H2
  src/systemTest/java/              SYSTEM      - black box over real HTTP
  src/ephemeralTest/java/           EPHEMERAL   - real Postgres in a container
  src/testFixtures/resources/golden/  frozen contract fixtures, shared by any tier
```

Each is a Gradle **JVM Test Suite**: its own source directory, configurations, `Test` task and
dependencies. The tier is a property of *where the code lives*, not a string on a class.

## The keystone: enforce by classpath, not by convention

The unit suite's dependency block omits every `spring-boot-starter-*-test` artifact. Verified by
deliberately adding `@SpringBootTest` to a unit test:

```
error: package org.springframework.boot.test.context does not exist
  symbol: class SpringBootTest
```

Be precise about what this is, because the loose version is wrong. Spring *core* is on the unit
tier's classpath transitively through the application, and always will be. What is absent is the
Spring **test** support, so a *static reference* to a context-booting annotation will not compile.
Reflective use remains physically possible; it is a classpath fact, not a language-level one.

That invariant is load-bearing and it rests on a dependency block staying correct - so it is itself
guarded. `verifyTierClasspaths` fails if Spring test support, Testcontainers or WireMock ever reach
the unit tier's compile classpath. It earned its place on the first run: it immediately caught
WireMock leaking into the unit tier through the fixtures.

## Context economy, measured

| Tier | Context boots | Peak cached | Reuse |
| --- | --- | --- | --- |
| unit | 0 | 0 | - |
| integration | 2 | 2 | 95% |
| system | **1** | 1 | - |

The system tier is the demonstration: two full-stack test classes, **one** context, because both
inherit the same composed `@FullStackTest` annotation and the same `@DynamicPropertySource` method
declared once in a base class. The visible payoff is in the timings:

```
quote API, black box        5 tests   5.46s   <- pays for the context build
wire contract regression    1 test    0.02s   <- reuses it, essentially free
```

Before this restructure, that second class used `@SpringBootTest` + `@MockitoBean` and therefore
built its own context. Five rules, in order of impact:

1. **One canonical configuration per concern**, reached through a composed annotation.
2. **Standardise mocks.** `@MockitoBean`, `@TestPropertySource`, `@ActiveProfiles` and
   `@DynamicPropertySource` are all part of the cache key, so a per-class variation is a per-class
   boot. Moving the stub wiring into one inherited base method is what merged two contexts into one.
3. **Never `@DirtiesContext`.** A dirtied-and-rebuilt context is a context boot.
4. **Stay under the 32-context LRU limit** or contexts are evicted and rebuilt (thrashing).
5. **Do not raise `maxParallelForks` blindly.** The cache is `static` per JVM, so forking trades
   CPU for context reuse.

## Fixtures, not copy-paste

`src/testFixtures/java` holds the shared vocabulary: `QuoteTestData` (builders),
`StubRateGateway` (a hand-written fake for the port) and `RateServiceStub` (one shared WireMock
server). Two scoping rules, both enforced:

- The fixtures are **Spring-free**. The unit tier compiles against them, so one Spring type in a
  public signature would break the keystone invariant.
- WireMock is `testFixturesImplementation`, not `api`, so it is not on any consumer's compile
  classpath. Suites that compile against WireMock declare it themselves; the system tier does not,
  because it goes through `RateServiceStub`'s intent-revealing methods instead of the raw server.

## Guards

| Guard | Catches |
| --- | --- |
| `verifyContextBudget` | The suite building more contexts than budgeted |
| `verifyTierClasspaths` | The unit tier's classpath invariant decaying |
| `failOnNoDiscoveredTests` | A suite that discovers nothing |
| ArchUnit (`ArchitectureTest`) | Production layering regressing |

`verifyContextBudget` reads Spring's `TestContext` cache statistics and gates on **`missCount` -
contexts actually built - not `size`**. This matters and was wrong in the first draft: `size` is
capped by the LRU `maxSize` of 32, so a suite thrashing three hundred configurations still reports
`size = 32` and passes forever. `missCount` is the quantity that grows without bound.

It also fails when no suite reports statistics at all, so the measurement cannot silently stop
working - a guard that quietly observes nothing is worse than no guard.

## Deliberate trade-offs

- **`check` is Docker-free.** The ephemeral tier is excluded from `check` *and* from the coverage
  inputs, so `./gradlew build` runs anywhere. `./gradlew ephemeralTest` opts in.
- **The system tier has no `implementation(project())` compile access to main's internals** beyond
  what the API returns. This is not pedantry: when I first wrote it, the system test reached into
  `QuoteRepository`, the classpath refused to compile it, and the fix was to add a read endpoint.
  The compiler enforced the black-box boundary.
- **No version catalog.** With one module and a BOM managing almost everything, it would add
  indirection without removing duplication. It earns its place in a multi-module build.

## What a peer review changed

The design was reviewed by a second model. Three findings were acted on:

1. **The budget guard measured a capped quantity** (above). This was a real bug; the guard could
   never have fired in the situation it existed to detect.
2. **`check` was not Docker-free** - the coverage task pulled in the ephemeral suite. Fixed by
   excluding its execution data.
3. **The keystone rested on convention**, not enforcement. Fixed by `verifyTierClasspaths`.

Recommendations I deliberately did **not** implement in this pass, in rough priority order:

- **Delete the H2 tier and run slices on Testcontainers Postgres.** The strongest criticism: H2
  in PostgreSQL mode will not execute `jsonb`, `gen_random_uuid()`, arrays or `ON CONFLICT`
  subtleties, so the tier validates a schema production has never seen. The counter-argument is that
  a shared container costs 2-4s once per JVM against context builds measured at 5.5s. I judged this
  a genuine restructure rather than a fix, and left the migration-level evidence to decide it.
- **Count contexts with a `MergedContextConfiguration`-based `TestExecutionListener`** instead of
  parsing a log line. More robust and version-stable; the log format is an internal detail. The
  log-parsing guard now has a canary instead, which is the cheap mitigation.
- **A flake policy** (retry-once on the heavy tiers only, quarantine with owner and expiry,
  skipped-ratio ceiling). At several hundred test classes this is the number one trust risk and none
  of the current guards can see it.
- **A per-suite minimum-count ratchet**, because "zero tests" is where a suite starts dying, not
  where it ends up - a suite that is entirely `@Disabled` still reports a positive test count.
- **Banning `disabledWithoutDocker = true` on CI**, where Docker being broken should be a hard
  failure rather than a silently skipped tier.

## What is verified, and what is not

Verified by running: all tiers compile; the unit tier rejects `@SpringBootTest`; the budget guard
fails when exceeded; the classpath guard caught a real leak; context counts and timings above; a
full `clean build` is green at 43 tests plus 3 Docker-gated.

**Not verified: the ephemeral tier has never executed.** There is no Docker on the machine this was
built on, so `@Testcontainers(disabledWithoutDocker = true)` skipped it every time. It compiles and
that is all that is known. Running it in CI is the first thing to do.
