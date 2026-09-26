# Appendix C — Glossary

**Acceptance test.** A test whose purpose is to check that the system does what the business asked
for. Defined by *who decides what's correct*, not by size. (Ch. 2)

**Adapter.** Code that translates between your domain's language (a port) and an external system's
protocol, e.g. `HttpRateGateway`. (Ch. 10)

**Arrange–Act–Assert (AAA).** The three-part structure of a test: set up, do one thing, check the
result. Same idea as Given–When–Then. (Ch. 3)

**ArchUnit.** A Java library for testing code structure: dependencies, layers, naming. (Ch. 7)

**Background (Gherkin).** Steps run before every scenario in a feature file. (Ch. 13)

**Black-box test.** A test that uses only the public interface and shares no code with the system;
it would survive a reimplementation in another language. (Ch. 2, 12)

**Boundary-value analysis.** Choosing test inputs on and next to the edges between partitions.
(Ch. 5)

**Canary (for a guard).** A check that a guard is still observing something, so it can't pass
vacuously. (Ch. 7, 9, 11)

**Closed / open workload.** Load models where users wait for responses (closed) or requests arrive
at a fixed rate regardless (open). (Ch. 18)

**Consumer-driven contract.** A contract written by the consumer's tests (what it actually uses) and
verified in the provider's build. Pact is the common tool. (Ch. 10)

**Context cache (Spring).** Spring's reuse of application contexts across test classes with
identical configuration. (Ch. 8)

**Contract test.** A test whose purpose is to check an agreement between two parties. (Ch. 2, 10)

**Coverage (line).** The fraction of executable lines that ran during tests. Says nothing about
whether they were checked. (Ch. 11)

**cucumber-spring.** Cucumber's Spring integration: dependency injection for step classes and
scenario scope. (Ch. 13)

**Determinism.** A test's result depends only on the code under test — not on time, randomness,
order or environment. (Ch. 3)

**Dummy.** A double passed only to satisfy a signature, never used. (Ch. 4)

**End-to-end (E2E) test.** A test spanning several deployed systems as a user experiences them.
(Ch. 2)

**Ephemeral environment.** A short-lived, production-like deployment for one PR or branch, destroyed
afterwards. Describes *where* tests run, not what they test. (Ch. 2, 17)

**Equivalence partitioning.** Dividing inputs into groups the code should treat alike and testing one
representative per group. (Ch. 5)

**Exploratory testing.** Skilled, time-boxed, charter-driven investigation where tests are designed
and run on the fly. (Ch. 16)

**Fake.** A working lightweight implementation of a dependency, e.g. `StubRateGateway`, or an
in-memory database. (Ch. 4, 9)

**Flaky test.** A test that passes and fails on the same code. (Ch. 3, 19)

**Flyway.** A database migration tool; in the lab it owns the schema. (Ch. 9)

**Four axes.** Scope, visibility, purpose, environment & phase — the book's framework for placing any
test. (Ch. 2)

**Given–When–Then.** BDD's name for the AAA structure; Gherkin's keywords. (Ch. 3, 13)

**Glue.** The packages Cucumber scans for step definitions and hooks. (Ch. 13)

**Golden file / snapshot / approval test.** A test comparing output against a stored, approved copy.
(Ch. 6)

**Grey-box test.** A test that drives the public interface but also looks inside or shares code with
the system. (Ch. 2)

**Integration test.** A test in which your code runs with some real technology it depends on —
framework, database, HTTP. (Ch. 2, 8)

**Interaction verification.** Checking which calls code made to collaborators. (Ch. 4)

**Invariant / property.** A statement that holds for every input in a set. (Ch. 16)

**jqwik.** A property-based testing engine for the JUnit Platform. (Ch. 16)

**Known bug (`@known-bug`).** A scenario describing correct behaviour that currently fails, excluded
by tag until fixed. (Ch. 15)

**Mock.** A double used to verify interactions; in everyday Java, anything made by Mockito. (Ch. 4)

**MockMvc.** Spring's in-process driver for the MVC pipeline; no real HTTP. (Ch. 8)

**Mutation testing.** Making small changes to code and checking that tests notice; measures test
effectiveness. PIT is the common Java tool. (Ch. 11)

**Negative test.** A test checking that invalid input is rejected gracefully. (Ch. 16)

**Normalisation.** Replacing legitimately varying values (ids, times) before a golden-file
comparison. (Ch. 6)

**Percentile (p95, p99).** The latency under which that percentage of requests complete. (Ch. 18)

**Port.** An interface in the domain's own language for something external, e.g. `RateGateway`.
(Ch. 10)

**Property-based testing.** Generating many inputs to check an invariant, with shrinking on failure.
(Ch. 5, 16)

**Quarantine.** Moving a confirmed flaky test out of the blocking path, with an owner and deadline.
(Ch. 19)

**Regression test.** A test that freezes accepted behaviour so that any change is noticed. (Ch. 2, 6)

**Scenario Outline.** A Gherkin scenario template run once per row of its Examples table. (Ch. 13)

**Scenario scope.** A bean lifetime of one Cucumber scenario. (Ch. 13)

**Schemathesis.** A tool that generates HTTP requests from an OpenAPI schema and checks responses.
(Ch. 16)

**Scope.** How much real software a test executes: unit, integration, system, end to end. (Ch. 2)

**Shared-type blindness.** A test inheriting the system's assumptions — and missing its wire-level
bugs — by using the system's own types. (Ch. 12)

**Shrinking.** Reducing a failing generated input to the simplest one that still fails. (Ch. 16)

**Singleton container.** One container per test JVM, started lazily and shared. (Ch. 9)

**Slice test.** A Spring test that starts one layer only (`@WebMvcTest`, `@DataJpaTest`, …). (Ch. 8)

**Smoke test.** A quick, shallow check that something is alive and basically works — often run after
a deploy. (Ch. 2, 17)

**Soak / load / stress / spike test.** Performance tests asking, respectively: stable over time? OK at
expected load? Where does it break? Does it survive bursts? (Ch. 18)

**Spy.** A double that records calls for later inspection. (Ch. 4)

**Staging.** A long-lived, shared, production-like environment. (Ch. 17)

**State verification.** Checking the result or resulting state rather than the calls made. (Ch. 4)

**Stub.** A double that returns canned answers. (Ch. 4)

**Stub drift.** Hand-written stubs diverging from the real provider's behaviour. (Ch. 10)

**Synthetic monitoring.** Scheduled scripted checks against production. (Ch. 17)

**System test.** A test of one whole deployable, with its external dependencies substituted. (Ch. 2)

**Test double.** Anything that stands in for a real dependency in a test. (Ch. 4)

**Testcontainers.** A library that starts disposable Docker containers from test code. (Ch. 9, 14)

**Test fixtures (Gradle).** A source set of shared test code consumed by several test suites.
(Ch. 11)

**Unit test.** A test of a unit of behaviour in isolation, in-process, without real I/O. (Ch. 2, 5)

**Visibility.** How much a test knows about or shares with the inside: white, grey, black. (Ch. 2)

**White-box test.** A test that uses internal structure directly. (Ch. 2)

**WireMock.** A programmable HTTP stub server, usable as a library or a container. (Ch. 4, 10, 14)
