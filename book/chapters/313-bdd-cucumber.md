# 13 BDD and Gherkin for engineers

::: {.callout .covers}
This chapter covers

- Behaviour-driven development: where it came from and what it's really for
- Gherkin: features, scenarios, backgrounds, outlines, data tables and tags
- Step definitions, Cucumber expressions and custom parameter types
- `cucumber-spring`: dependency injection and per-scenario state
- Running Cucumber on the JUnit Platform from Gradle
- The anti-patterns that make Cucumber suites miserable
:::

**After this chapter you will be able to** write readable feature files, implement their steps
cleanly in Java, wire them with Spring, and recognise a Cucumber suite that's heading for trouble.

::: {.callout .recall}
Warm-up

1. State the reimplementation test for black-box-ness. (section 12.1)
2. What is shared-type blindness? (12.2)
3. What's the Given–When–Then shape called in plain developer tests? (3.1)
:::

## 13.1 BDD in one paragraph

Behaviour-driven development (BDD) grew out of test-driven development in the mid-2000s, when Dan
North noticed that people learning TDD got stuck on the word "test". He reframed tests as
*specifications of behaviour*, written as examples in a structured natural language: **Given** some
context, **When** something happens, **Then** some outcome. The original goal was conversation —
developers, testers and business people agreeing on concrete examples *before* building. Cucumber is
the best-known tool that turns those examples into executable tests.

For this lab, the plan says feature files are written by **developers and QA**, not by business
stakeholders. That's common and perfectly legitimate — but it changes the priorities. We use Gherkin
for **readability and a stable vocabulary** of black-box actions, not as a collaboration ritual.
Steps may be a little more technical ("the response status is 201") than they would be in a
business-authored suite.

::: {.callout .myth}
Misconception: "Cucumber is only worth it if business people write the features"

The collaboration benefit is real, but it's not the only one. Gherkin separates **what** a scenario
checks (the feature file) from **how** (step code). For a black-box suite that's valuable on its own:
scenarios read like a contract, and the HTTP plumbing lives in one place. The cost — an extra layer
of indirection — is justified when the suite is large or long-lived.
:::

## 13.2 Gherkin, by example

```gherkin
# Listing 13.1 A feature file (a teaching example; the lab's real features are in chapter 15)
@quotes
Feature: Create a quote
  A client asks for a loan quote; the service prices it with the rate
  service's current rate and stores it.

  Background:
    Given the rate service quotes 4.25% for product "WIDGET" in "USD"

  Scenario: A valid command is priced and stored
    When I request a quote for 10000.00 USD over 12 months of product "WIDGET"
    Then the response status is 201
    And the quote total is "10425.00"

  Scenario Outline: The term changes the total
    When I request a quote for 1000.00 USD over <months> months of product "WIDGET"
    Then the quote total is "<total>"

    Examples:
      | months | total   |
      | 1      | 1003.54 |
      | 12     | 1042.50 |
      | 24     | 1085.00 |
```

The elements:

| Keyword | Purpose |
| --- | --- |
| `Feature` | One capability. Free text below it is documentation. |
| `Background` | Steps run before *every* scenario in the file. Keep it short. |
| `Scenario` | One concrete example of the behaviour. |
| `Scenario Outline` + `Examples` | A template run once per row: Gherkin's parameterised test. |
| `Given` / `When` / `Then` | Context / action / outcome. `And` and `But` continue the previous kind. |
| `@tag` | Labels for selecting or excluding scenarios (`@quotes`, `@wip`, `@known-bug`). |
| `# comment` | Ignored. |

Two more structures come up constantly in API testing:

```gherkin
When I request a quote:
  | productCode | amount   | currency | termMonths |
  | WIDGET      | 10000.00 | USD      | 12         |

Then the response body is:
  """json
  {"type":"urn:problem:quote-not-found","status":404}
  """
```

The first is a **data table** — handy when a step needs several named values. The second is a
**doc string** — a multi-line block passed to the step as one `String`.

::: {.callout .mental}
Mental model: Given sets the stage, When is the single action, Then is observable

- **Given** puts the world into a state (stubs, existing data). It's not what's under test.
- **When** is the *one* action being specified — the same "one Act" rule as chapter 3.
- **Then** checks *observable* outcomes: what the client sees, or — for this suite — what a
  read-only query can see.
:::

## 13.3 Step definitions

Cucumber matches each step's text against **step definitions**: Java methods annotated with the
same keyword and a pattern.

```java
// Listing 13.2 Step definitions for listing 13.1 (simplified from the lab's QuoteSteps)
public class QuoteSteps {

    @Autowired private RequestSpecification api;          // #1
    @Autowired private ScenarioContext scenario;

    @When("I request a quote for {bigdecimal} {word} over {int} months of product {string}")  // #2
    public void requestAQuote(BigDecimal amount, String currency, int termMonths, String product) {
        scenario.response(given(api)
                .contentType(ContentType.JSON)
                .body(Map.of("customerId", scenario.customerId(),
                        "productCode", product, "amount", amount,
                        "currency", currency, "termMonths", termMonths))
                .post("/api/v1/quotes"));
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int status) {
        assertThat(scenario.response().statusCode()).isEqualTo(status);
    }

    @Then("the quote total is {string}")
    public void theQuoteTotalIs(String expected) {
        Json.hasNumberToken(scenario.response().asString(), "total", expected);    // #3
    }
}
```

::: {.annotations}
1. Collaborators are injected by Spring (section 13.5).
2. A **Cucumber expression**. `{bigdecimal}`, `{word}`, `{int}` and `{string}` are built-in
   parameter types; `{string}` matches text in double quotes.
3. The expected total is passed as a *string* and compared as the exact **token** in the raw JSON —
   `10425.00` must appear as `10425.00`, not `10425.0` or `10425.0000`. In this suite scale *is* the
   contract. The `Json` helper is shown in chapter 15.
:::

Built-in parameter types worth knowing: `{int}`, `{long}`, `{float}`, `{double}`, `{bigdecimal}`,
`{biginteger}`, `{word}` (no spaces), `{string}` (quoted), and `{}` (anything). You can define your
own:

```java
@ParameterType("USD|EUR|GBP")
public Currency currency(String code) {
    return Currency.getInstance(code);
}
// now usable as {currency} in any step expression
```

::: {.callout .hood}
Under the hood: what "glue" means

Cucumber finds step definitions and hooks by scanning the **glue** packages you configure. A step
text that matches no definition is *undefined*; one that matches two is *ambiguous*. Both fail the
run. That strictness is a feature: it stops two engineers from quietly writing competing
definitions of "the response status is 201".
:::

## 13.4 Hooks

Hooks run code around scenarios:

| Annotation | When | Typical use in this suite |
| --- | --- | --- |
| `@BeforeAll` (static) | Once, before any scenario | Start the Docker stack |
| `@Before` | Before each scenario | Reset WireMock stubs |
| `@After` | After each scenario | Attach the last response to the report on failure |
| `@AfterAll` (static) | Once, at the end | Rarely needed; a shutdown hook stops the stack |

Hooks can be restricted with a tag expression: `@Before("@needs-slow-stub")`.

## 13.5 cucumber-spring: dependency injection for steps

Step classes need collaborators: an HTTP client configured with the stack's base URL, a WireMock
admin client, a `JdbcTemplate`. Cucumber can create them with several DI integrations; for a team
that knows Spring, `cucumber-spring` is the natural choice.

![Figure 13.1 cucumber-spring builds a small, test-only Spring context. The application's own context is never loaded.](images/13-cucumber-spring.png)

```java
// Listing 13.3 The pointer class (blackbox/src/test/.../config/CucumberSpringConfiguration.java)
@CucumberContextConfiguration                                        // #1
@ContextConfiguration(classes = BlackboxConfig.class)
public class CucumberSpringConfiguration {
}
```

```java
// Listing 13.4 The suite's beans (blackbox/src/test/.../config/BlackboxConfig.java, abridged)
@Configuration                                                       // #2
@Lazy                                                                // #3
@ComponentScan(basePackages = "com.example.quotes.blackbox")
public class BlackboxConfig {

    @Bean
    public RequestSpecification api() {
        return new RequestSpecBuilder()
                .setBaseUri(BlackboxStack.baseUrl())
                .setContentType(ContentType.JSON)
                .build();
    }

    @Bean
    public WireMock wireMock() {
        URI admin = URI.create(BlackboxStack.wiremockAdminUrl());
        return new WireMock(admin.getHost(), admin.getPort());
    }

    @Bean
    public DriverManagerDataSource assertionDataSource() {             // #4
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(BlackboxStack.jdbcUrl());
        dataSource.setUsername("blackbox_reader");
        dataSource.setPassword("blackbox_reader");
        return dataSource;
    }

    @Bean
    public JdbcTemplate assertionJdbcTemplate(DriverManagerDataSource assertionDataSource) {
        return new JdbcTemplate(assertionDataSource);
    }

    // ... plus QuoteDb and RateStub, small wrappers the steps use
}
```

::: {.annotations}
1. Exactly one class in the glue carries `@CucumberContextConfiguration`. It only *points* at the
   configuration. Why a separate class? Because this one is also scanned as glue, and cucumber-spring
   refuses a glue class that is also a Spring component — Spring and Cucumber would each create an
   instance. Merging the two classes is the obvious simplification, and it fails at start-up.
2. A plain `@Configuration`. The component scan covers only the suite's own package — no
   `@SpringBootApplication`, nothing of the application.
3. `@Lazy`: every bean here reaches the running stack, so eager creation would start Docker the
   moment the context loads. That matters for the dry run (chapter 15), which still builds a Spring
   context but never needs the stack.
4. The read-only database role (chapter 14).
:::

`ScenarioContext` is the suite's **world object**: the state one scenario builds up as its steps
run — the last response, the id of the quote it created, the created JSON, and a unique `customerId`.

```java
// Listing 13.5 Per-scenario state (blackbox/src/test/.../support/ScenarioContext.java, abridged)
@Component
@ScenarioScope                                                       // #1
public class ScenarioContext {
    private final String customerId = "bb-" + UUID.randomUUID();
    private Response response;
    private UUID quoteId;
    private String createdJson;

    public String customerId() { return this.customerId; }
    public Response response() { return this.response; }
    public void response(Response response) { this.response = response; }
    // ... quoteId and createdJson accessors
}
```

::: {.annotations}
1. **Scenario scope**: a fresh instance for every scenario, discarded afterwards. Step classes
   themselves are also scenario-scoped by cucumber-spring.
:::

::: {.callout .myth}
Misconception: "Share state between steps with static fields"

Static fields survive from one scenario to the next, so a scenario can pass only because the
previous one left something behind — the order-dependence of chapter 3, now in Gherkin. Use a
scenario-scoped world object. If you find you need data *across* scenarios, that's a design smell:
each scenario should set up its own world.
:::

## 13.6 Running it on the JUnit Platform

Cucumber runs as a JUnit Platform engine, so Gradle's normal `test` task runs it.

```java
// Listing 13.6 The suite entry point
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.example.quotes.blackbox")
public class RunCucumberTest {
}
```

```properties
# Listing 13.7 blackbox/src/test/resources/junit-platform.properties
cucumber.plugin=pretty, html:build/reports/cucumber/index.html, junit:build/test-results/cucumber.xml
cucumber.execution.parallel.enabled=false
```

The tag filter isn't in this file: the Gradle task sets it (`not @wip and not @known-bug` by
default, overridable with `-Ptags=...`), so one command-line switch controls which scenarios run.
Parallel execution is off because the scenarios share one WireMock container (chapter 15).

The HTML report shows every scenario with its steps, marking the failing step — a report a product
owner or QA engineer can read without opening an IDE.

![Figure 13.2 From feature text to HTTP call: Cucumber matches the text, the step uses injected clients, the scenario context holds the state.](images/13-gherkin-flow.png)

## 13.7 Anti-patterns

**Imperative, UI-script steps.** *"When I set the field amount to 10000 And I set the field currency
to USD And I click submit"*. These describe *how*, not *what*; they break when the mechanics change
and bury the behaviour. Prefer declarative steps: *"When I request a quote for 10000 USD…"*.

**Conjunctive steps.** *"Given the rate is 4.25% and the customer exists and the product is
active"* as one step. It can't be reused and hides three preconditions. Split it.

**Step explosion.** Twenty slightly different phrasings of "the status is 201". Keep a small,
reviewed **step vocabulary** (the plan puts it in `blackbox/README.md`) and grow it deliberately.

**Logic in feature files.** Loops, conditionals or computed values in Gherkin. Features describe
examples; logic belongs in step code.

**A giant Background.** If every scenario starts with ten Given steps, readers can't see what
matters for each scenario. Keep Background to the context that's truly shared.

**Asserting in Given steps, acting in Then steps.** Each keyword has a job; mixing them makes
failures confusing.

::: {.callout .tryit}
Try it: rewrite a bad scenario

Rewrite this scenario declaratively, in at most four steps:

```gherkin
Scenario: quote
  Given I set header "Content-Type" to "application/json"
  And I set body field "productCode" to "WIDGET"
  And I set body field "amount" to "1000"
  And I set body field "currency" to "USD"
  And I set body field "termMonths" to "12"
  And the wiremock mapping for "/rates/WIDGET" returns "{\"annualPercentage\":5}"
  When I POST to "/api/v1/quotes"
  Then the status code should be 201 and the total should be 1050
```

*One possible answer is in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. What problem was BDD originally trying to solve, and how does this lab use it differently?
2. What's the difference between a Scenario Outline and a data table?
3. What happens when a step matches no definition? Two definitions?
4. Why is `ScenarioContext` scenario-scoped, and what goes wrong with static fields instead?
5. The suite uses a Spring context. Name one thing that must never be in it, and why.
:::

::: {.callout .summary}
Summary

- **BDD** specifies behaviour as Given–When–Then examples. This lab uses Gherkin mainly for
  **readability and a stable step vocabulary**, written by developers and QA.
- Gherkin's building blocks: **Feature, Background, Scenario, Scenario Outline/Examples, data
  tables, doc strings, tags**.
- **Step definitions** bind text to Java with Cucumber expressions; undefined or ambiguous steps
  fail the run.
- **cucumber-spring** gives steps dependency injection from a small, test-only context; a
  **scenario-scoped world object** carries state within one scenario.
- Avoid imperative steps, conjunctive steps, step explosion and logic in features.
:::
