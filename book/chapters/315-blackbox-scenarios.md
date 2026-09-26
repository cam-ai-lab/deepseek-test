# 15 Writing black-box scenarios

::: {.callout .covers}
This chapter covers

- Porting the old system tier, test by test, into feature files
- Reading raw JSON without losing number scale
- Programming the WireMock container from steps
- Read-only SQL assertions, used sparingly
- Isolation between scenarios, and tags for work-in-progress and known bugs
- A build guard that keeps the suite black-box, and retiring the old tier safely
:::

**After this chapter you will be able to** write complete black-box scenarios for an HTTP service,
make them sensitive to exactly the wire details clients depend on, and migrate an existing suite
without losing coverage.

::: {.callout .recall}
Warm-up

1. Why does the black-box module have no `project(":")` dependency? (section 14.6)
2. What can the `blackbox_reader` role do, and what can't it? (14.4)
3. What's the difference between `isEqualTo` and `isEqualByComparingTo` on a `BigDecimal`, and which
   one does a contract test need? (3.5)
:::

## 15.1 From the old tier to features

A migration should never lose a check. So start with an inventory: every test in `src/systemTest`,
and where its intent lives afterwards.

| Old test | New home | Change in strength |
| --- | --- | --- |
| `prices_the_command_with_the_real_rate_lookup_and_persists_it` | `create_quote.feature`, `read_quote.feature` | Stronger: raw JSON tokens, stored scale, stored row count |
| `reports_404_as_a_problem_detail_for_an_unknown_quote` | `read_quote.feature`, `wire_contract.feature` | Same, plus checks the problem `title` |
| `really_makes_an_http_call_to_the_rate_service` | `create_quote.feature` | Stronger: checks product *and* currency asked for |
| `answers_503_when_the_rate_service_is_down` | `rate_service_failures.feature` | Stronger: 500 and 503 upstream, nothing stored, no quote echoed |
| `answers_400_without_calling_the_rate_service…` | `rate_service_failures.feature` (outline) | Stronger: four invalid inputs |
| `the_json_body…matches_the_golden_file` | `wire_contract.feature` | Field set, order and every number's exact token |
| *(none)* | `rate_service_failures.feature`: slow rate service | New: timeout through the real image |
| *(none)* | `known_bugs.feature` | New: the three bugs from the review, tagged |

Write this table *before* deleting anything. It is the migration's specification, and a reviewer can
check it line by line.

## 15.2 The features

```gherkin
# Listing 15.1 blackbox/src/test/resources/features/quotes/create_quote.feature (abridged)
Feature: Create a quote

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A valid command is priced with the upstream rate and persisted
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response status is 201
    And the response has a Location header pointing at the new quote
    And the quote total is "10425.00"
    And 1 quote is stored for my customer
    And the stored quote has total "10425.0000" and rate "4.2500"
    And the stored quote is for 12 months

  Scenario: The rate really is fetched over the network
    When I request a quote for 1000.00 USD over 6 months of product "WIDGET"
    Then the response status is 201
    And the quote rate is "4.25"
    And the rate service was asked for product "WIDGET" in "USD"

  Scenario: Two commands create two separate quotes
    When I request a quote for 50.00 USD over 1 month of product "WIDGET"
    And I request a quote for 50.00 USD over 1 month of product "WIDGET"
    Then the response status is 201
    And 2 quotes are stored for my customer
```

```gherkin
# Listing 15.2 read_quote.feature
Feature: Read a quote

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A quote that was never created is not found
    When I fetch a quote that does not exist
    Then the response is a problem of type "urn:problem:quote-not-found" with status 404

  Scenario: A created quote can be read back
    When I request a quote for 25000.00 USD over 24 months of product "WIDGET"
    Then the response status is 201
    When I fetch that quote
    Then the response status is 200
    And the fetched quote belongs to my customer
```

Why doesn't the read scenario check the total? Because on GET the service currently returns
`"total":27125.0000`, and asserting `"27125.00"` would fail for the known scale bug rather than for
anything this scenario is about. The scale contract gets its own scenario in `known_bugs.feature`.
**One scenario, one reason to fail** — chapter 3's rule applies to Gherkin too. (The first version of
this suite broke that rule: two untagged scenarios asserted scale on a GET, which would have made the
suite red for a bug it was already tracking elsewhere.)

```gherkin
# Listing 15.3 rate_service_failures.feature
Feature: The upstream rate service misbehaves

  Scenario Outline: An upstream failure is reported as 503 and nothing is saved
    Given the rate service answers <status> for product "WIDGET"
    When I request a quote for 1000.00 USD over 12 months of product "WIDGET"
    Then the response is a problem of type "urn:problem:rate-unavailable" with status 503
    And the response carries no quote
    And no quote is stored for my customer

    Examples:
      | status |
      | 500    |
      | 503    |

  Scenario: A rate service that never answers is reported as 503
    Given the rate service takes 5 seconds to answer for product "WIDGET"
    When I request a quote for 1000.00 USD over 12 months of product "WIDGET"
    Then the response status is 503
    And no quote is stored for my customer

  Scenario Outline: An invalid command is rejected without troubling the upstream
    When I request a quote:
      | productCode | amount   | currency | termMonths |
      | WIDGET      | <amount> | USD      | <term>     |
    Then the response status is 400
    And the rate service was never asked for product "WIDGET"

    Examples:
      | amount | term |
      | 0.00   | 12   |
      | -5.00  | 12   |
      | 100.00 | 0    |
      | 100.00 | 601  |
```

Notice the slow-rate scenario. The adapter test in chapter 10 checked the timeout with a 400 ms
setting passed in by the test. This scenario checks the timeout *as configured in the shipped image*
(3 s, from `application.yml`). If someone changes the production timeout to 30 s, the adapter test
still passes; this one fails. Same behaviour, different question: "does the adapter honour a
timeout?" versus "is the deployed product configured with one?"

::: {.callout .mental}
Mental model: black-box scenarios test configuration too

Lower tiers test code with the configuration *the test supplies*. The black-box tier tests code with
the configuration *you ship*. Timeouts, pool sizes, Jackson settings, error-handling properties —
anything set in `application.yml` or by environment variables — is only really tested here.
:::

## 15.3 Reading JSON without losing the details

The whole point of this tier is to see the wire as a client sees it. So steps read the **raw JSON
text**, and numbers are compared as the exact **token** the service wrote:

```java
// Listing 15.4 blackbox/src/test/.../support/Json.java (abridged)
public static String flatten(String json) {
    return json.replaceAll("\\s+", "");                                         // #1
}

public static void hasNumberToken(String json, String field, String expectedToken) {
    new BigDecimal(expectedToken);                                               // #2
    Pattern exactToken = Pattern.compile(
            Pattern.quote("\"" + field + "\":" + expectedToken) + "(?=[,}\\]])");  // #3
    assertThat(flatten(json))
            .as("field '%s' should appear as exactly the token %s", field, expectedToken)
            .containsPattern(exactToken);
}
```

::: {.annotations}
1. Whitespace is insignificant in JSON; strip it so comparisons are about content.
2. Fail fast if a feature file passes something that isn't a number at all.
3. The token must be followed by the end of the value — a comma, brace or bracket. Without this
   look-ahead, an expected `4.25` would match an actual `4.2500`, because one is a prefix of the other.
   The first version of the suite had exactly that bug: a check for scale that couldn't see scale.
:::

With that, `the quote total is "10425.00"` fails if the service ever sends `10425.0` or `10425.0000`.
Compare the old system test, which deserialised into `QuoteResponse` and compared with
`isEqualByComparingTo`: both of chapter 12's blind spots are gone.

The wire-contract feature replaces the golden file:

```gherkin
# Listing 15.5 wire_contract.feature (first scenario)
Scenario: The documented quote shape is exactly what comes back
  Given the rate service quotes 4.25% for product "WIDGET" in "USD"
  When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
  Then the response status is 201
  And the response has exactly the documented fields
  And the quote amount is "10000.00"
  And the quote rate is "4.25"
  And the quote total is "10425.00"
  And the field "createdAt" is an ISO-8601 UTC timestamp
```

`the response has exactly the documented fields` matches the flattened body against a pattern that
pins the field *names and order*, with a shape for each value. The last step checks `createdAt`'s
*format* rather than blanking it — the improvement chapter 6 recommended over the old golden file's
normalisation.

## 15.4 Programming the rate service

The WireMock container is controlled through its admin API. Steps talk to a small wrapper:

```java
// Listing 15.6 blackbox/src/test/.../support/RateStub.java (abridged)
public void quotes(String productCode, String annualPercentage, String currency) {
    this.wireMock.register(get(urlPathEqualTo("/rates/" + productCode))
            .withQueryParam("currency", equalTo(currency))                       // #1
            .willReturn(okJson("""
                    {"productCode":"%s","annualPercentage":%s,"currency":"%s"}
                    """.formatted(productCode, annualPercentage, currency))));  // #2
}

public void answers(String productCode, int status) {
    this.wireMock.register(get(urlPathEqualTo("/rates/" + productCode))
            .willReturn(aResponse().withStatus(status)));
}

public void reset() {                                                            // #3
    this.wireMock.resetMappings();
    this.wireMock.resetRequests();
}
```

::: {.annotations}
1. The stub answers only for the requested currency. If the service asked for the wrong one, no stub
   would match, WireMock would answer 404, and the scenario would fail with a 503.
2. It echoes the currency — unlike the old `RateServiceStub`, which always said `USD` (chapter 10's
   stub drift in miniature).
3. Stubs are global to the container, so a `@Before` hook calls this before every scenario: cheap
   things fresh (chapter 9).
:::

## 15.5 Read-only SQL, used sparingly

Two kinds of fact can't be seen through the API: that a failed request stored *nothing*, and what
the database actually holds. Both live in one support class, used only from `Then` steps:

```java
// Listing 15.7 blackbox/src/test/.../support/QuoteDb.java (abridged)
public int countFor(String customerId) {
    Integer count = this.jdbc.queryForObject(
            "SELECT count(*) FROM quotes WHERE customer_id = ?", Integer.class, customerId);
    return count == null ? 0 : count;
}

public BigDecimal storedTotalFor(String customerId) {
    return this.jdbc.queryForObject(
            "SELECT total FROM quotes WHERE customer_id = ?", BigDecimal.class, customerId);
}
```

Every query filters by *this scenario's* customer — which brings us to isolation.

## 15.6 Isolation

![Figure 15.1 Isolation in the black-box suite: unique data per scenario on a shared database, and global stubs reset before each scenario.](images/15-isolation.png)

The database is never cleaned. Instead:

- Each scenario gets a **unique `customerId`** (`bb-<uuid>`) from its `ScenarioContext`, sent in every
  command it makes.
- Every assertion is **scoped** to that customer or to a quote id the scenario created.
- WireMock stubs are **reset before each scenario**, and scenarios run **serially**, because stubs
  are shared by all of them.

When the suite grows slow enough to need parallelism, the plan's next step is to give each scenario a
unique *product code* too (`BB-17`), so stubs never overlap, and then enable Cucumber's parallel
execution. Doing that before you need it adds complexity for no benefit.

::: {.callout .myth}
Misconception: "Black-box tests must start from an empty database"

Cleaning the database between scenarios is slow, serialises everything, and — in a shared or
long-lived environment — is often impossible. Tests that assert only on data they created work on an
empty database *and* on a busy one. That makes the same scenarios usable later against staging.
:::

## 15.7 Tags: work in progress and known bugs

![Figure 15.2 The default tag filter. Known bugs are written as the correct behaviour, tagged, and excluded until fixed.](images/15-tags.png)

Two tags have special meaning, both excluded by default:

- **`@wip`** — a scenario being written. It should live for days, not months.
- **`@known-bug`** — a scenario that describes the **correct** behaviour and currently fails,
  linked to a ticket. It documents the bug precisely and becomes a regression test the moment the tag
  is removed.

From the review in chapter 1, three scenarios start life tagged `@known-bug`: the POST/GET
round trip, the overflow of a large valid amount (chapter 16), and the unknown product answered with
503 instead of a 4xx (chapter 10). Running `./gradlew :blackbox:test -Ptags=@known-bug` shows the
team's bug list as executable specifications.

```gherkin
# Listing 15.8 known_bugs.feature (abridged)
@known-bug
Feature: Defects the black-box suite exposes

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A created quote reads back exactly as it was returned
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    And I fetch that quote
    Then the response status is 200
    And the fetched JSON equals the created JSON

  Scenario: The largest valid amount never causes a server error
    When I request a quote for 999999999999999.9999 USD over 12 months of product "WIDGET"
    Then the response status is not a server error

  Scenario: An unknown product is the caller's problem, not an outage
    Given the rate service answers 404 for product "NOSUCH"
    When I request a quote for 1000.00 USD over 12 months of product "NOSUCH"
    Then the response status is a client error
```

Two of those Then steps are deliberately loose — "not a server error", "a client error" — because the
exact right answer is a product decision nobody has made yet. What *is* certain is that the current
answer is wrong. Chapter 16 returns to this idea.

::: {.callout .myth}
Misconception: "A known-bug scenario should assert the buggy behaviour, so it passes"

Asserting the bug turns the scenario into a tripwire that *protects* the bug: the day someone fixes
it, the test goes red. Always write the scenario for the correct behaviour and exclude it with a tag.
The fix then shows up as "this scenario now passes — remove the tag".

The lab's first draft of this file made exactly this mistake twice: one scenario expected GET to
return `10000.0000` and another expected `status is 500`. Both would have passed today and gone red
the day someone fixed the bug. Review known-bug scenarios with that question: *does this fail when the
bug is fixed, or when it isn't?*
:::

## 15.8 Keeping the suite black-box

The module has no dependency on the application today. A guard keeps it that way:

```kotlin
// Listing 15.9 verifyBlackboxIsolation (blackbox/build.gradle.kts, abridged)
val verifyBlackboxIsolation = tasks.register("verifyBlackboxIsolation") {
    group = "verification"
    val classpaths = listOf("compileClasspath", "testCompileClasspath", "runtimeClasspath", "testRuntimeClasspath")
        .map { configurations.named(it) }
    doLast {
        val projectDependencies = classpaths
            .flatMap { it.get().incoming.resolutionResult.allComponents }            // #1
            .map { it.id }
            .filterIsInstance<org.gradle.api.artifacts.component.ProjectComponentIdentifier>()
            .map { it.projectPath }
            .filter { it != ":blackbox" }
            .distinct()
        if (projectDependencies.isNotEmpty()) {
            throw GradleException("The black-box suite must not depend on any project, but it " +
                "depends on $projectDependencies. ...")
        }
        val applicationBytecode = classpaths.flatMap { it.get().files }               // #2
            .filter { it.path.contains("classes/java/main") || it.name == "application.jar" }
        if (applicationBytecode.isNotEmpty()) {
            throw GradleException("The application's compiled code is on the black-box classpath: ...")
        }
    }
}
tasks.check { dependsOn(verifyBlackboxIsolation) }
```

::: {.annotations}
1. Checks the *resolved* dependency graph, so a project dependency arriving transitively is caught
   too — not just one declared directly in this file.
2. A second net: the application's compiled classes or jar on any classpath, however they got there.
:::

Together with the missing classpath entry, this makes shared-type blindness impossible to reintroduce
by accident.

One more guard runs on every PR, and it needs no Docker at all. `./gradlew :blackbox:test -PdryRun`
asks Cucumber to match every step of every scenario to a definition *without executing anything*.
Undefined and ambiguous steps still fail. It exists because the suite's first CI run revealed that
most of its assertion steps were undefined: the feature files wrote `the quote total is 10425.00`
while the definition expected a quoted `{string}` — a mismatch that no compiler catches. Two
details make the dry run work: the Gradle task skips the image build when `-PdryRun` is set, and
`BlackboxConfig` is `@Lazy`, because Cucumber still builds a Spring context in a dry run and eager
beans would try to start the stack.

## 15.9 Retiring the old tier, safely

The order matters:

1. **Port** every old test (table 15.1) and see the new scenarios pass (or fail as `@known-bug`).
2. **Close the coverage gap** first: add the `findQuote` unit tests (chapter 3's exercise) and the
   GET slice tests (listing 8.2). Otherwise the 85% gate fails for reasons unrelated to the change
   (chapter 11).
3. **Delete** `src/systemTest`, `FullStackTest`, `SystemTestBase` and `RateServiceStub`, the
   `systemTest` suite in `build.gradle.kts`, and its entries in the context budget and coverage lists.
4. **Delete** the golden file; `wire_contract.feature` replaces it.
5. **Update the docs**, which already contain stale claims.

Step 2 before step 3 is the whole trick. A migration that temporarily lowers a quality gate "just for
this PR" usually never raises it again.

::: {.callout .tryit}
Try it: tighten two scenarios

(1) The step `the response is a problem of type {string} with status {int}` checks the status, the
`type` and the presence of a `title`. Extend it to check the `Content-Type` header is
`application/problem+json` and that the body's `status` field matches. (2) The validation outline in
listing 15.3 never varies the currency. Add rows for a two-letter and a four-letter currency code.
Run `./gradlew :blackbox:test -PdryRun` first, then the real suite. *Solutions in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. Why write the migration table before deleting any old tests?
2. How does `Json.hasNumberToken` check scale, and why does it need the look-ahead at the end of the pattern?
3. The adapter test already checks timeouts. What does the slow-rate scenario add?
4. How do scenarios stay isolated on a database that is never cleaned?
5. Why is a known bug written as the correct behaviour and tagged, rather than asserting the bug?
:::

::: {.callout .summary}
Summary

- Migrate with an **inventory**: every old test mapped to its new home, ideally *stronger*.
- Read **raw JSON** and compare numbers as exact **tokens**, so scale is part of every check.
- Program the **WireMock container** from steps, resetting stubs before each scenario.
- Use **read-only SQL** only in Then steps, for facts the API can't show.
- Isolate with **unique data per scenario**; run serially until parallelism is needed.
- **`@wip`** and **`@known-bug`** are excluded by default; known bugs are written as the correct
  behaviour.
- A **build guard** keeps the suite black-box; a **dry run** catches undefined steps without Docker;
  close coverage gaps **before** deleting the old tier.
:::
