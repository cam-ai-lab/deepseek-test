# 8 Integration tests and Spring slices

::: {.callout .covers}
This chapter covers

- What an integration test integrates, and why the framework is part of your code
- Spring's test slices: `@WebMvcTest`, `@DataJpaTest` and friends
- Testing JSON binding, validation and error mapping with MockMvc
- The Spring TestContext cache: why the *number of contexts* is the number to watch
- How a single `@MockitoBean` can double a suite's run time
:::

**After this chapter you will be able to** choose the thinnest Spring slice that can see a given
risk, write MockMvc tests for an HTTP contract, and keep a Spring test suite from booting more
contexts than it needs.

::: {.callout .recall}
Warm-up

1. What does an architecture test check, and what scope is it? (section 7.1)
2. What's the difference between a level-1 and a level-2 test double? (4.4)
3. Why is testing validation with a plain `Validator` a unit test? (5.5)
:::

## 8.1 Your code is more than your code

Look at `QuoteController`:

```java
@PostMapping
ResponseEntity<QuoteResponse> createQuote(@Valid @RequestBody QuoteRequest request) {
    QuoteResponse quote = this.quoteService.createQuote(request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(quote.quoteId()).toUri();
    return ResponseEntity.created(location).body(quote);
}
```

A unit test could call this method directly. It would prove almost nothing, because nearly all the
behaviour that matters happens *outside* the method, in the framework:

- Is `POST /api/v1/quotes` actually mapped to it?
- Is the JSON body parsed into a `QuoteRequest`? What happens with `{not json`?
- Does `@Valid` actually trigger validation, and what does the 400 response look like?
- Does a `RateUnavailableException` become a 503 problem document?
- Is the `Location` header correct?

The annotations are code, just written declaratively. An **integration test** runs your code *with*
the real framework and technology it depends on, so these questions get real answers. In the
four-axes vocabulary, it widens the **scope** to include real third-party machinery.

## 8.2 Slices: start only what you need

Spring Boot can start the whole application in a test with `@SpringBootTest`. That's the broadest
scope — and the slowest, because it creates every bean, connects to the database, and runs
migrations. Most of the time you don't need all that. **Test slices** start just one layer.

![Figure 8.1 Three ways to start Spring in a test. Slices load one layer; @SpringBootTest loads everything.](images/08-slices.png)

| Annotation | Loads | Doesn't load | Use it to test |
| --- | --- | --- | --- |
| `@WebMvcTest(X.class)` | Controller X, `@ControllerAdvice`, Jackson, validation, MockMvc | Services, repositories, DataSource | Routing, binding, validation, status codes, error mapping |
| `@DataJpaTest` | JPA repositories, entity manager, DataSource, Flyway | Web layer, services | Entity mapping, queries, migrations |
| `@JsonTest` | Jackson and JSON helpers | Almost everything | Serialisation format of DTOs |
| `@RestClientTest` | `RestClient`/`RestTemplate` builder, mock server | Web, JPA | HTTP clients (alternative to WireMock) |
| `@SpringBootTest` | Everything | — | Whole-app wiring |

::: {.callout .mental}
Mental model: a slice is a scope dial

Think of slices as a dial on the *scope* axis. `@WebMvcTest` turns scope up just enough to include
Spring MVC, but no further. The rule: **turn the dial to the smallest setting where the risk is
visible**. Errors in JSON binding are visible at `@WebMvcTest`; you don't need a database to see
them.
:::

## 8.3 The web slice in the lab

```java
// Listing 8.1 A web-slice test (QuoteControllerTest)
@WebMvcTest(QuoteController.class)                                            // #1
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;                                                   // #2

    @MockitoBean
    private QuoteService quoteService;                                         // #3

    @Test
    void creates_a_quote_and_points_at_it_with_a_location_header() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(this.quoteService.createQuote(any())).willReturn(new QuoteResponse(id, "cust-1",
                "WIDGET", new BigDecimal("1000.00"), "USD", 12, new BigDecimal("5.0000"),
                new BigDecimal("1050.00"), Instant.parse("2026-01-15T10:30:00Z")));

        this.mockMvc
                .perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_COMMAND))                               // #4
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/quotes/" + id)))
                .andExpect(jsonPath("$.quoteId").value(id.toString()))
                .andExpect(jsonPath("$.total").value(1050.00));
    }

    @Test
    void rejects_a_command_violating_the_validation_rules() throws Exception {
        this.mockMvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"customerId":"","productCode":"WIDGET","amount":0,"currency":"USD","termMonths":0}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        then(this.quoteService).shouldHaveNoInteractions();                     // #5
    }
}
```

::: {.annotations}
1. Load only the web layer, and only this controller.
2. MockMvc drives the full Spring MVC pipeline — filters, argument resolution, message converters,
   exception handlers — without opening a network socket.
3. The service is replaced by a Mockito mock registered as a Spring bean. We're testing the web
   layer, so everything below it is a double.
4. A JSON *string* goes in, exactly as a client would send it.
5. Interaction verification at an edge (chapter 4): invalid input must never reach the service.
:::

This one class covers routing, JSON parsing, bean validation, the 400/503 problem mappings and the
`Location` header, in well under a second once the slice is up.

::: {.callout .hood}
Under the hood: MockMvc is not HTTP

MockMvc calls Spring's `DispatcherServlet` directly with mock request and response objects. There
is no TCP connection, no Tomcat, no real HTTP parsing. That's why it's fast — and why a handful of
things (servlet container configuration, compression, TLS, real network timeouts) are invisible to
it. For those you need a running server: `@SpringBootTest(webEnvironment = RANDOM_PORT)` or, better
still, the black-box suite of Part 3.
:::

## 8.4 The Spring context is expensive — so Spring caches it

Starting a Spring context takes time: scanning, creating beans, connecting to a database, running
Flyway. In a large application that can be 5–30 seconds. If every test class started its own
context, a suite of 300 test classes would spend most of its time booting Spring.

So the Spring TestContext Framework **caches** contexts. The cache key is built from the test's
*configuration*: the configuration classes, active profiles, property sources, `@MockitoBean`
definitions, context customisers, and so on. Two test classes with identical configuration share
one context; any difference means a new one.

![Figure 8.2 The context cache. Identical configuration reuses a context; a single extra @MockitoBean builds another.](images/08-context-cache.png)

This has a consequence that surprises almost everyone: **the number of distinct test configurations
in your suite, not the number of tests, drives how long a Spring suite takes.** Add one
`@MockitoBean` to one test class "just to stub something", and you've created a new cache key — and
paid for another full context boot.

Typical culprits that split the cache:

- A `@MockitoBean` or `@MockitoSpyBean` that only one class declares.
- `@ActiveProfiles` or `@TestPropertySource` varying between classes.
- A `@DynamicPropertySource` method declared separately in each class (even if identical!).
- `@DirtiesContext`, which throws the context away after use.
- A nested `@TestConfiguration` in one class.

::: {.callout .myth}
Misconception: "Spring tests are slow because Spring is slow"

Spring *boots* slowly. A suite that boots three contexts and reuses them across 300 tests is fast;
a suite that boots 80 contexts is slow — with the same framework. When a Spring suite gets slow,
the first thing to measure is the number of contexts, not the number of tests.
:::

The lab takes this seriously enough to measure it. The build turns on debug logging for the context
cache, and a Gradle task called `verifyContextBudget` reads the cache statistics out of the test
reports and fails the build if any test JVM built more contexts than a budget (default 6). We'll
look at that task in chapter 11. For now, the lesson: **context count is a metric; treat it like
one.**

## 8.5 Designing for context reuse

The lab's system tier shows the patterns for sharing one context across many classes:

- **One composed annotation.** `@FullStackTest` wraps `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  and `@AutoConfigureTestRestTemplate`. Every full-stack test uses it, so they can't drift apart.
- **One base class for dynamic properties.** `SystemTestBase` declares the single
  `@DynamicPropertySource` that points the app at the containers. If each class declared its own
  (identical) method, each would get its own cache key.
- **No `@MockitoBean` in full-stack tests.** Remote dependencies are stubbed *over the network*
  (WireMock) instead of by replacing beans, which keeps the configuration identical.

That last point connects back to the three levels of doubles in chapter 4: a level-2 double
(network) doesn't change the Spring configuration; a level-1 double registered as a bean
(`@MockitoBean`) does.

## 8.6 A gap: the GET endpoint

`QuoteControllerTest` covers POST thoroughly. It doesn't test `GET /api/v1/quotes/{id}` at all —
neither the 200 nor the 404 path. Those are covered only by the system tier. That's a legitimate
choice, but it has a cost we'll pay in Part 3: when the system tier is removed, the coverage goes
with it. Listing 8.2 shows the missing slice tests.

```java
// Listing 8.2 Web-slice tests for the read path (a sketch; main has the real ones)
@Test
void returns_a_stored_quote() throws Exception {
    UUID id = UUID.randomUUID();
    given(this.quoteService.findQuote(id)).willReturn(new QuoteResponse(id, "cust-1", "WIDGET",
            new BigDecimal("1000.0000"), "USD", 12, new BigDecimal("5.0000"),
            new BigDecimal("1050.00"), Instant.parse("2026-01-15T10:30:00Z")));

    this.mockMvc.perform(get("/api/v1/quotes/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quoteId").value(id.toString()));
}

@Test
void answers_404_as_a_problem_for_an_unknown_quote() throws Exception {
    UUID id = UUID.randomUUID();
    given(this.quoteService.findQuote(id)).willThrow(new QuoteNotFoundException(id));

    this.mockMvc.perform(get("/api/v1/quotes/{id}", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:problem:quote-not-found"));
}
```

These reuse the existing `@MockitoBean QuoteService`, so they add **zero** new contexts — they join
the cache entry `QuoteControllerTest` already creates. On `main`, the black-box migration added
tests like these (`returns_a_stored_quote` and `reports_an_unknown_quote_as_a_404_problem`) *before*
deleting the system tier — the ordering chapter 15 insists on.

::: {.callout .tryit}
Try it: split the cache on purpose

Add a new `@WebMvcTest(QuoteController.class)` class that also declares `@MockitoBean Clock clock`.
Run `./gradlew integrationTest` with the context-cache debug logging (already configured in the
build) and find the line `DefaultContextCache@… size = …, missCount = …` in the XML report under
`build/test-results/integrationTest`. How many misses were there before and after? Then remove the
extra class. *Needs a JDK and Docker.*
:::

::: {.callout .quiz}
Check your understanding

1. Name three behaviours of `QuoteController` that a plain unit test can't verify.
2. Which slice would you use to test that `QuoteResponse` serialises `createdAt` as an ISO-8601
   string? Why not `@SpringBootTest`?
3. What is the Spring TestContext cache key built from? Name four things that change it.
4. Why does stubbing the rate service with WireMock, rather than `@MockitoBean`, help context reuse?
5. What can MockMvc *not* see, and which kind of test can?
:::

::: {.callout .summary}
Summary

- Framework annotations are code. **Integration tests** run your code with the real framework so
  routing, binding, validation and error mapping get tested.
- **Slices** (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, …) start one layer. Use the thinnest slice
  where the risk is visible.
- **MockMvc** drives the full MVC pipeline without a network, which makes it fast but blind to
  real-server concerns.
- Spring **caches contexts by configuration**. The number of distinct configurations — not tests —
  drives suite time. Measure it.
- Share one configuration via composed annotations and base classes, and prefer network-level
  doubles to `@MockitoBean` in broad tests.
:::
