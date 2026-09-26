# Black-box suite

A Cucumber suite that runs the **real image** of the service with `docker compose` and talks to it
only over HTTP. It has no dependency on the application's code — enforced by
`./gradlew :blackbox:verifyBlackboxIsolation`, not by good intentions — so assertions compare raw
JSON. That independence is what lets it catch a contract change rather than inherit it.

## Running it

```bash
./gradlew :blackbox:test                 # build the image, start the stack, run everything
./gradlew :blackbox:test -Ptags=@wip     # only work-in-progress scenarios
./gradlew :blackbox:test -Ptags=@known-bug
```

Needs Docker with Compose v2. The stack takes roughly 30–60 s to come up; the scenarios themselves
are fast.

Note the explicit `:blackbox:` prefix. Plain `./gradlew test` runs *every* project's `test` task,
which includes this one and therefore needs Docker. The in-process tiers are `./gradlew :test` and
`./gradlew :integrationTest`.

Reports: `blackbox/build/reports/cucumber/index.html`.

## Tags

| Tag | Meaning |
| --- | --- |
| *(none)* | Runs by default |
| `@wip` | Work in progress. Excluded by default; should be temporary and visible in review |
| `@known-bug` | Documents a real defect that is deliberately not fixed yet. Excluded by default |

## The step vocabulary

Keep this list small and shared. A step that exists only for one scenario usually means the scenario
is describing an implementation rather than a behaviour. Adding to this list is a review decision.

### Given — the world the scenario starts in

```gherkin
Given the rate service quotes 4.25% for product "WIDGET" in "USD"
Given the rate service quotes 4.25 for product "WIDGET"
Given the rate service answers 503 for product "WIDGET"
Given the rate service takes 5 seconds to answer for product "WIDGET"
```

### When — what the caller does

```gherkin
When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
When I request a quote:
  | productCode | amount   | currency | termMonths |
  | WIDGET      | 10000.00 | USD      | 12         |
When I fetch that quote
When I fetch a quote that does not exist
```

### Then — what the caller can observe

```gherkin
Then the response status is 201
Then the response has a Location header pointing at the new quote
Then the quote total is 10425.00
Then the quote amount is 10000.00
Then the quote rate is 4.25
Then the response has exactly the documented fields
Then the fetched JSON equals the created JSON
Then the response is a problem of type "urn:problem:rate-unavailable" with status 503
Then the response carries no quote
Then the rate service was never asked for product "WIDGET"
Then the created timestamp has microsecond precision
```

### Then — what reached the database

```gherkin
Then no quote is stored for my customer
Then exactly one quote is stored for my customer
Then the stored quote has total 10425.0000 and rate 4.2500
Then the stored quote is for 12 months
```

## Rules that keep it honest

**SQL only in `Then` steps, and only through `QuoteDb`.** The connection uses the `blackbox_reader`
role, which the database grants only `SELECT`, so a writing step fails at PostgreSQL rather than at
review. Setup always goes through the API.

**Prefer the API; use SQL only for what the API cannot show.** "Nothing was persisted" and "the
stored value kept its scale" are the two good reasons.

**Assert on raw JSON, never on a deserialised type.** The suite cannot see the application's classes,
and that is the point. `Json.hasNumberToken` compares the number's *text*, so a change from
`10425.00` to `10425.0` is a failure — a value comparison would miss it.

**Every scenario gets a unique `customerId`** (`bb-<uuid>`), so scenarios never see each other's
data and nothing is ever deleted.

**Stubs are global to the container,** so scenarios run serially and `Hooks` resets WireMock before
each one. If the suite gets slow enough to need parallelism, give each scenario its own product code
and enable `cucumber.execution.parallel.enabled`.

## Layout

```
src/main/java/.../stack/BlackboxStack.java    the compose stack, shared with Gatling
src/test/java/.../RunCucumberTest.java        the JUnit Platform entry point
src/test/java/.../config/BlackboxConfig.java  DI for the steps - a plain Spring context
src/test/java/.../support/                    ScenarioContext, QuoteDb, RateStub, Json
src/test/java/.../steps/                      the vocabulary above
src/test/resources/features/                  the scenarios
src/gatling/java/                             the performance smoke test
```
