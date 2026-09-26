# Appendix B — Answers

Try the questions before reading these. Where a question asks for an opinion, the answer gives the
reasoning the chapter expects; yours may differ and still be sound.

## Chapter 1

1. Confidence to change, fast feedback, design pressure, executable documentation. **Confidence to
   change** comes from tests that keep passing while you change the code beneath them.
2. So tests can control time: with `Clock.fixed(...)`, `createdAt` can be asserted exactly and the test
   is repeatable. It's also the design-pressure benefit — the dependency on time becomes explicit.
3. Coverage says lines *ran*, not that anything *checked* them (chapter 11). And tests sample
   behaviour; they never prove correctness.
4. The mismatch comes from the database changing the scale and the HTTP layer echoing different
   objects. The calculator is pure arithmetic and involves neither.
5. People run a slow suite less often, so problems are found later, further from the change that
   caused them — and in bigger batches of changes, which makes the cause harder to find.

## Chapter 2

1. **Scope** — how much of the software really runs. **Visibility** — how much the test knows or
   shares with the inside. **Purpose** — which risk it guards. **Environment & phase** — where and
   when it runs.
2. Unit. The real calculator is pure and in-process; no real technology (database, HTTP, framework)
   is involved. "Unit" is about isolating behaviour, not about mocking every class.
3. They deserialise responses into the app's own `QuoteResponse` and start the app in their own JVM
   via `@SpringBootTest`. They share code with the system under test.
4. "Run our system-scope tests in an ephemeral environment instead of on the build machine." The
   question back: *what risk would that environment reveal that containers on the build machine
   don't?* (Deployment manifests? Platform routing?) If there's no answer, the move only adds cost.
5. Yes. `QuoteWireContractRegressionTest` is close: a frozen JSON body compared as raw text. The
   `@known-bug` round-trip scenario becomes a black-box regression test once fixed.
6. Purpose: contract/compatibility with the production database. Visibility: white-box is fine — it's
   about the migration itself. Smallest scope: the migration running on the real engine (a JPA slice
   or even a Flyway-only test). Cheapest environment: a container with **the same PostgreSQL version
   as production**, on every build. Address: integration / white / functional (compatibility) /
   container, every PR.

## Chapter 3

1. Both split a test into set-up, one action, and checks. Arrange–Act–Assert is the developer term
   (from TDD practice); Given–When–Then comes from behaviour-driven development and Gherkin.
2. **Repeatable**: time no longer varies between runs, so assertions on time are exact.
3. When scale is part of the contract: `QuoteCalculatorTest` (the total always has two decimals) and
   any wire-contract check of JSON numbers.
4. It hides the symptom and keeps the cause, which may be a real race condition in production code;
   and it teaches the team to ignore red.
5. Split into two tests — one per behaviour — so each has one reason to fail and its name can
   describe exactly what broke.

**Faded example (section 3.6):**

```java
@Test
void reports_an_unknown_quote_as_not_found() {
    UUID unknown = UUID.randomUUID();
    given(repository.findById(unknown)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.findQuote(unknown))
            .isInstanceOf(QuoteNotFoundException.class)
            .hasMessageContaining(unknown.toString());
}
```

## Chapter 4

1. A stub returns canned answers; a fake is a working lightweight implementation. `StubRateGateway`
   is a **fake**, despite its name.
2. When the call *is* the behaviour and there's no result to inspect — `saves_nothing_when_the_rate_
   service_cannot_be_reached` verifies `save` was never called.
3. Wrong URL or query parameters, JSON field names and types, status-code handling, timeouts,
   malformed responses — everything in the HTTP adapter, which a level-1 double bypasses.
4. It's pure and fast, so mocking it adds nothing but coupling; the test stops checking that the
   price is right and breaks when the calculator's signature changes.
5. "This test mirrors the implementation: it would pass if the results were wrong and fail if we
   reorder harmless calls. Replace it with state verification of the outcome, and verify only
   interactions that are the behaviour."

**Try it (section 4.6):** (a) a stub (Mockito mock used for stubbing), level 1; (b) a stub at level 2
(network); (c) a fake, level 1; (d) interaction verification with a spy/mock, level 1; (e) not a
double — it's the *real* database engine, disposable. Using real infrastructure is the opposite of
doubling it.

## Chapter 5

1. Partitions: null, empty, blank (only whitespace), valid (1–64 non-blank characters), too long
   (≥ 65). Boundaries: `""`, `" "`, one character, 64 characters, 65 characters.
2. To build `BigDecimal`s from their exact decimal text. A `double` such as 1500.55 can't be
   represented exactly, and `new BigDecimal(double)` exposes the binary approximation.
3. It lands exactly on a half cent (0.005 of interest) — the only kind of input where `HALF_UP` and
   `HALF_EVEN` disagree.
4. Not inconsistent. The calculator's contract includes scale ("always two decimals"), so it's
   checked. The service test is about orchestration, where numeric value is the point.
5. Listing 5.4 calls the `Validator` directly, in-process, with no framework running. The controller
   test runs Spring MVC's pipeline to check that `@Valid` is actually applied — the *wiring*.

## Chapter 6

1. Functional failure → the code is (probably) wrong; fix it. Regression failure → something
   changed; decide whether it was intended, possibly with the business, and update the anchor only
   deliberately.
2. Replacing values that legitimately change (ids, timestamps) with placeholders. Overdoing it hides
   real changes, such as a timestamp format change.
3. Any two of: scale differences for other input formats (`10000` vs `10000.00`); the GET response's
   shape; changes to `createdAt`'s format (fully blanked); a bug that was present when recorded.
4. Deserialising would normalise the wire representation (scale, formatting) and blind the test —
   shared-type blindness (chapter 12).
5. Only after reading every diff and confirming each change is intended, with the reason recorded.

**Try it (section 6.5):** Today the GET golden file would contain `"amount":10000.0000`,
`"annualRatePercent":4.2500` and `"total":10425.0000`. Committing it would *freeze the bug* — the
tripwire would then fire when someone fixes it. Better: decide the intended contract (probably two
decimals for money, matching POST), write the golden file for that, and mark the test as a known bug
until the fix lands.

## Chapter 7

1. The violation is tiny and local (one import) while the damage is global and gradual; reviewers
   under time pressure miss it.
2. Test classes would be analysed too: they could violate rules they're allowed to break, or satisfy
   rules by accident.
3. For example: "controllers must not use repositories directly", or "`QuoteCalculator` must not
   depend on Spring web, JPA or Spring Data".
4. When it encodes a preference rather than a decision the team would defend in a design review.
5. A forbidden-API rule: no calls to `Instant.now()` / `LocalDate.now()` outside the `config`
   package, forcing use of the injected `Clock`.

## Chapter 8

1. Any three of: request mapping, JSON parsing, `@Valid` validation, the 400 problem response, the
   503 mapping, the `Location` header.
2. `@JsonTest`: it loads Jackson and nothing else, so it's fast and focused. `@SpringBootTest` would
   start the database and everything else for a serialisation question.
3. The merged test configuration. Changed by: configuration classes, active profiles, test property
   sources, `@DynamicPropertySource` methods, `@MockitoBean`/`@MockitoSpyBean` definitions, context
   customisers, `@DirtiesContext` (which discards it).
4. A network stub doesn't alter the Spring configuration, so all full-stack tests keep one cache key;
   a `@MockitoBean` is part of the key.
5. Real servlet-container behaviour and real HTTP: connection handling, compression, TLS, real
   timeouts. A test with a running server — `@SpringBootTest(RANDOM_PORT)` or the black-box suite.

## Chapter 9

1. It's a **fake**: a working, lightweight implementation of a database that diverges from the real
   one in types, dialect, locking and migrations.
2. A mapping check: Hibernate refuses to start if an entity and the Flyway-created table disagree.
3. PostgreSQL stores microseconds; nanosecond precision would be truncated and the equality check
   would fail for a reason that isn't a mapping bug.
4. Per-class: strongest isolation, simplest to reason about. Singleton: one start-up for the run
   (minutes saved) and a stable URL, so Spring contexts can be shared.
5. Transaction rollback (works when the test owns the transaction, e.g. `@DataJpaTest`); unique data
   per test (works everywhere, including black-box and shared environments); truncation (simple, but
   serialises tests); separate schemas (maximum isolation, maximum set-up cost).

## Chapter 10

1. Request shape, response mapping (missing and extra fields), each status class, timeouts,
   malformed responses.
2. The adapter can be constructed with `new` from a `RestClient.Builder` and properties. No context
   means no boot time and no cache interactions — fast and simple.
3. Rate service 404 → `RestClientException` → adapter wraps it as `RateUnavailableException` →
   `ApiExceptionHandler` maps that to 503 "retry later".
4. The consumer's stub encodes assumptions about the provider that the provider can silently break.
   Each team tests its own side; nobody tests the agreement.
5. The **consumer** writes it (by declaring interactions in its tests); the **provider** verifies it
   in its own build against the real service.

**Try it (section 10.5):** Minimal interactions: (1) given WIDGET exists, `GET /rates/WIDGET?currency=USD`
→ 200 with a decimal `annualPercentage`; (2) given NOPE doesn't exist, `GET /rates/NOPE?currency=USD`
→ 404. Only `annualPercentage` belongs in the response contract — the quote service reads nothing
else. If you fix the currency gap (chapter 10), `currency` joins it. Fields you don't read stay out,
so the provider can change them freely.

## Chapter 11

1. Enforcement (a classpath makes forbidden annotations uncompilable; a tag is a forgettable string)
   and honest failure (a tier can't silently select zero tests).
2. So that Testcontainers never appears in a consumer's compile classpath; exposing the container
   type would force it onto every tier, including unit.
3. `size` is capped by the cache's LRU maximum (32), so it can't show how many contexts were *built*;
   `missCount` grows with every build.
4. A check that the guard is still observing something. Examples: the ArchUnit import count; the
   "really PostgreSQL" tests; the context-budget task failing when no statistics are found.
5. It would push people to write assertion-free tests of trivial code to hit the number. Coverage is a
   flashlight: keep a floor, look at uncovered code, and consider mutation testing for core logic.

## Chapter 12

1. "Could the test be rewritten in another language and pointed at a reimplementation in another
   language?" `HttpRateGatewayTest` fails it: it constructs `HttpRateGateway` directly — it's a
   white/grey-box test of our adapter, which is exactly its job.
2. Shared types (`QuoteResponse`) normalise the wire; `isEqualByComparingTo` ignores scale; and the
   test and system authors share the "a number is a number" mental model.
3. POST returns the in-memory entity built from the request and rate values; GET returns what
   PostgreSQL's `NUMERIC(19,4)` / `NUMERIC(9,4)` columns hand back, with scale 4.
4. System scope (web + persistence) and black-box, scale-sensitive comparison of raw JSON from two
   operations (visibility: black-box; purpose: contract).
5. Its Spring context contains only test plumbing (HTTP client, stub client, read-only JDBC). No
   application code is loaded or shared; it would work unchanged against a reimplementation.

## Chapter 13

1. Getting people stuck on "test" to think in behaviour and examples, and fostering conversation
   between business and engineering. The lab uses Gherkin mainly for readability and a stable step
   vocabulary written by developers and QA.
2. A Scenario Outline runs a whole scenario once per `Examples` row; a data table passes structured
   data into a single step.
3. No match → undefined step; two matches → ambiguous step. Both fail the run.
4. So each scenario gets fresh state and can't depend on another. Static fields leak state between
   scenarios, creating order dependence.
5. The application's own code or context (e.g. `@SpringBootApplication` or a component scan of
   `com.example.quotes`) — it would make the suite grey-box and reintroduce shared-type blindness.

**Try it (section 13.7):**

```gherkin
Scenario: A quote is priced with the upstream rate
  Given the rate service quotes 5.00% for product "WIDGET" in "USD"
  When I request a quote for 1000.00 USD over 12 months of product "WIDGET"
  Then the response status is 201
  And the quote total is "1050.00"
```

## Chapter 14

1. The image is what's deployed: it includes packaging, the JRE, the start-up command and
   environment-variable configuration, none of which `bootRun` exercises.
2. Gradle builds both a boot jar and a `-plain.jar`; a wildcard would match two files and break the
   Dockerfile's `COPY`. The lab renames the boot jar to `application.jar` for the same reason.
3. The reader role would have no privileges on the `quotes` table (created later by Flyway as
   `quotes`), so every assertion query would fail with a permission error.
4. An open port only means the server socket is listening; readiness means the app has fully
   started, including Flyway migrations.
5. The `blackbox` module has no dependency on the application (classpath), the isolation guard fails
   if one is added, and the database role can't write.
6. The container logs. In one case the app never started (its entrypoint named a class the image
   didn't contain); in the other it had started, and Testcontainers was looking for it under the wrong
   (Compose v1) name. The dumped logs showed a crash in the first case and a healthy start in the
   second.

## Chapter 15

1. It's the migration's specification: it ensures no check is lost and lets a reviewer verify the
   mapping line by line.
2. It searches the raw JSON text for the exact token (`"total":10425.00`), so `10425.0` or
   `10425.0000` don't match. The look-ahead requires the token to end there (followed by `,`, `}` or
   `]`); without it, `4.25` would match `4.2500`, because one is a prefix of the other.
3. It tests the timeout **as configured in the shipped image** (3 s from `application.yml`), not a
   value injected by the test.
4. Unique `customerId` per scenario, assertions scoped to the scenario's own data, stubs reset before
   each scenario, serial execution.
5. Asserting the bug would protect it: the fix would turn the test red. Specifying the correct
   behaviour makes the fix show up as "remove the tag".

**Try it (section 15.9):**

(1) Extend the existing step in `QuoteSteps`:

```java
@Then("the response is a problem of type {string} with status {int}")
public void responseIsAProblemOfType(String type, int expectedStatus) {
    assertThat(status()).isEqualTo(expectedStatus);
    assertThat(response().contentType()).startsWith("application/problem+json");
    assertThat(body()).contains("\"type\":\"" + type + "\"");
    assertThat(response().jsonPath().getInt("status")).isEqualTo(expectedStatus);
    assertThat(body()).contains("\"title\":");
}
```

(2) The table already has a `currency` column, so vary it in the outline:

```gherkin
Scenario Outline: An invalid command is rejected without troubling the upstream
  When I request a quote:
    | productCode | amount   | currency   | termMonths |
    | WIDGET      | <amount> | <currency> | <term>     |
  Then the response status is 400
  And the rate service was never asked for product "WIDGET"

  Examples:
    | amount | currency | term |
    | 0.00   | USD      | 12   |
    | -5.00  | USD      | 12   |
    | 100.00 | USD      | 0    |
    | 100.00 | USD      | 601  |
    | 100.00 | US       | 12   |
    | 100.00 | USDX     | 12   |
```

Run `./gradlew :blackbox:test -PdryRun` first: if you renamed a column but not the step's table
handling, the dry run won't catch it (tables aren't typed), but it will catch any step text you
changed.

## Chapter 16

1. Validation allows 15 integer digits; for any positive rate the total is larger than the amount and
   can need 16; `total NUMERIC(19,4)` holds 15; PostgreSQL rejects the INSERT; the exception has no
   handler; the client gets 500.
2. The correct status isn't decided yet (lower limit? wider column? 422?), but a 500 is certainly
   wrong. The invariant "no 5xx for valid input" holds whatever the decision.
3. Unit: "total ≥ amount" — pure arithmetic. System: "GET returns the same body as POST" — involves the
   database and HTTP layers, which only exist at system scope.
4. Reduces a failing generated input to the simplest input that still fails, making the bug easy to
   understand and reproduce.
5. Property-based testing explores input spaces systematically around stated invariants; exploratory
   testing finds things nobody thought to state — confusing messages, inconsistent naming,
   deployment quirks.

**Try it (section 16.7):** Charter: "Explore GET /api/v1/quotes/{id} with malformed, unknown and
concurrent requests to discover crashes and inconsistent responses." Invariants: fetching twice gives
identical bodies (system, black-box); a malformed id gives a 400 problem, not a 500 (web slice);
every successful POST's id is fetchable (system, black-box).

## Chapter 17

1. Push each test to the lowest rung where its risk is visible; each rung up needs fewer tests.
2. The deployment itself: manifests, platform-injected configuration, probes, ingress, TLS, DNS,
   service mesh, and a shareable URL for review.
3. Any three of: contention, drift from production, unowned data, and queueing for access.
4. It's read-only or uses clearly marked synthetic data that's excluded from reporting and cleaned up;
   it never touches real customers.
5. Scenarios assert only on data they created, so other teams' data and leftovers don't affect them.

**Try it (section 17.5):** Safe: the 404 for a random id; fetching a known synthetic reference quote.
Dangerous: "a valid command is priced and stored" — it creates a quote and needs a stubbed rate. A
production version would use a synthetic customer marked as such, accept the real rate (asserting
only status and shape), and be cleaned up or excluded from reports. Many teams decide it shouldn't
run in production at all.

## Chapter 18

1. Soak; stress; spike; load.
2. It blends many fast requests with a few slow ones and hides the tail, which is what users notice.
3. Closed: fixed users who wait for each response, so load falls when the system slows (hiding
   problems). Open: requests arrive at a fixed rate regardless, as real traffic does.
4. The stack's URL is needed when the fields (protocol, scenario) are initialised in the constructor;
   a static initialiser runs before that.
5. Latency on shared CI runners is noisy, so a latency gate would fail randomly — flaky by design.

## Chapter 19

1. Fast, deterministic, and about the change being merged.
2. Failure isolation, parallelism (wall-clock time is the slower job, not the sum), and independent
   re-runs of infrastructure hiccups.
3. The application's stack traces live in the container logs. Testcontainers uses a random project
   name and removes the stack when the JVM exits, so logs must be streamed while it runs.
4. Detect, quarantine quickly, assign an owner, fix or delete by a deadline, measure the quarantine.
5. As a stopgap for infrastructure noise, provided every retry is reported and retried tests enter the
   quarantine process.

## Chapter 20

1. The pyramid fits logic-heavy systems, where risk lives in logic that's cheap to unit-test. The
   honeycomb fits thin services, where risk lives in interactions with infrastructure.
2. (a) A JPA slice or Flyway test on a real PostgreSQL container, ideally with existing rows present:
   integration / white / functional / container. (b) A consumer-driven contract verified in the
   provider's build: integration / grey / contract / each team's CI. (c) A black-box scenario with a
   slow stub: system / black / functional / containers. (d) The calculator's half-cent unit test (or
   the pricing anchors): unit / white / functional + regression / in-process.
3. Speed (seconds vs minutes, so the inner loop suffers); precision (black-box failures don't locate
   the fault); coverage of the input space (exhaustive arithmetic tables and properties are only
   affordable at unit level). Also: black-box tests can't see internal design erosion.
4. So readers know what *isn't* protected and why, and so the gaps are revisited deliberately. Without
   it, people assume coverage that doesn't exist.
5. Old rows didn't match an assumption of new code. It needed real persistence with *pre-existing
   data* — a migration test on a container seeded with rows in the old shape (integration tier), and a
   rehearsal against production-like data in staging. The strategy's risk table should gain a row for
   "data written by older versions".
