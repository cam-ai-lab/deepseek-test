# 10 Testing at the boundary

::: {.callout .covers}
This chapter covers

- Ports and adapters: why the rate service hides behind an interface
- Adapter tests with WireMock: wire format, status codes, timeouts
- Error translation, and a design flaw the tests have frozen in place
- Contracts between teams: consumer-driven contract testing with Pact
- Why hand-written stubs drift from reality
:::

**After this chapter you will be able to** test an HTTP client thoroughly without a real server,
spot when a test is enshrining a questionable behaviour, and explain how consumer-driven contracts
keep two teams' assumptions in sync.

::: {.callout .recall}
Warm-up

1. Why is an in-memory database a risky double for repository tests? (section 9.1)
2. At which level does WireMock replace a dependency? What real code runs at that level? (4.4)
3. What does the Spring cache key include that makes `@MockitoBean` expensive? (8.4)
:::

## 10.1 Ports and adapters

The quote service needs rates. It could put a `RestClient` straight into `QuoteService`. Instead,
it defines an interface in *its own language*:

```java
// Listing 10.1 The port (rate/RateGateway.java)
public interface RateGateway {
    /**
     * @return the rate for the given product and currency
     * @throws RateUnavailableException if the rate could not be obtained
     */
    Rate rateFor(String productCode, String currency);
}
```

and a separate class that speaks the rate service's language — HTTP and JSON — and translates:

![Figure 10.1 The port speaks our language; the adapter speaks theirs and translates every failure into our exception.](images/10-adapter.png)

This is the **ports and adapters** (or *hexagonal*) architecture in miniature. Its testing payoff is
a clean split:

- **Behind the port**, `QuoteService` is tested with a level-1 double (chapter 4) — fast, no HTTP.
- **The adapter** is tested on its own, against a level-2 double: a real HTTP server we control.

Each test has one job. The service test doesn't care about JSON; the adapter test doesn't care
about pricing.

## 10.2 The adapter test

```java
// Listing 10.2 Adapter tests against WireMock (HttpRateGatewayTest)
@BeforeAll
static void startStubServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());   // #1
    server.start();
}

@BeforeEach
void buildGatewayAgainstTheStub() {
    server.resetAll();                                                             // #2
    RateProperties properties = new RateProperties(URI.create(server.baseUrl()),
            Duration.ofSeconds(2), Duration.ofMillis(400));                       // #3
    this.gateway = new HttpRateGateway(RestClient.builder(), properties);          // #4
}

@Test
void maps_the_upstream_payload_onto_a_rate() {
    server.stubFor(get(urlPathEqualTo("/rates/WIDGET"))
            .withQueryParam("currency", equalTo("USD"))
            .willReturn(okJson("""
                    {"productCode":"WIDGET","annualPercentage":4.25,"currency":"USD"}
                    """)));

    Rate rate = this.gateway.rateFor("WIDGET", "USD");

    assertThat(rate.annualPercentage()).isEqualByComparingTo("4.25");
    server.verify(getRequestedFor(urlPathEqualTo("/rates/WIDGET"))
            .withQueryParam("currency", equalTo("USD")));                           // #5
}

@Test
void gives_up_when_the_rate_service_is_slower_than_the_read_timeout() {
    server.stubFor(get(urlPathEqualTo("/rates/SLOW"))
            .willReturn(okJson("""
                    {"productCode":"SLOW","annualPercentage":1.00,"currency":"USD"}
                    """).withFixedDelay(2_000)));                                   // #6

    assertThatThrownBy(() -> this.gateway.rateFor("SLOW", "USD"))
            .isInstanceOf(RateUnavailableException.class);
}
```

::: {.annotations}
1. A random free port — never hard-code one (chapter 3).
2. Fresh stubs for every test: cheap things are fresh.
3. A 400 ms read timeout, deliberately short so the timeout test is quick.
4. **No Spring at all.** The adapter is constructed with `new`. It's an integration test of our code
   plus Spring's `RestClient` plus real HTTP — but it needs no application context. Fast and
   cache-free.
5. Verify the outgoing request: path *and* query parameter. A typo in the URL template would fail
   here.
6. The stub waits 2 s; the client gives up at 400 ms.
:::

The rest of the class covers: unknown JSON fields are ignored (so the provider can add fields
without breaking us), a 500 becomes `RateUnavailableException`, a 404 becomes
`RateUnavailableException`, and a payload missing `annualPercentage` becomes
`RateUnavailableException`.

That's a thorough adapter test. It checks the **wire format** in both directions, **status
handling**, **resilience** (timeouts), and **tolerance** (unknown fields). If you remember one
checklist from this chapter, make it that one.

::: {.callout .mental}
Mental model: the adapter checklist

For any adapter to a remote system, test: **request shape** (path, params, headers, body),
**response mapping** (including missing and extra fields), **each class of status code** (2xx, 4xx,
5xx), **timeouts**, and **malformed responses**. Each one is a separate failure mode in production.
:::

## 10.3 When a test freezes a mistake

Look at the 404 test's name: `treats_an_unknown_product_as_rate_unavailable`. It's a clear name,
and the test passes. But follow the consequence through the system:

1. A client asks for a quote on product `NOPE`.
2. The rate service says 404 — "no such product".
3. The adapter turns *every* `RestClientException` into `RateUnavailableException`.
4. `ApiExceptionHandler` turns that into **503 Service Unavailable — "please retry later"**.

The client is told to retry a request that can never succeed. Worse, automated clients with retry
policies will hammer both services. The correct answer is a 4xx: "unknown product" is a problem with
the request, not with availability.

The test didn't cause this, but it *enshrines* it. Anyone who fixes the behaviour will see this test
fail and may assume they broke something. This is a general lesson: **tests encode decisions, and
some decisions are wrong.** A test named after a behaviour tells you what the code does; it doesn't
tell you the behaviour was ever questioned.

The fix involves both the adapter and its test:

```java
// Listing 10.3 Distinguishing "not found" from "unavailable" (sketch)
catch (HttpClientErrorException.NotFound ex) {
    throw new UnknownProductException(productCode, ex);           // -> 422 or 404 to our client
}
catch (RestClientException ex) {
    throw new RateUnavailableException("Rate lookup failed for product " + productCode, ex);
}
```

and a renamed test: `reports_an_unknown_product_as_unknown_not_unavailable`.

::: {.callout .myth}
Misconception: "If there's a test for it, it's intended behaviour"

Tests record what someone *decided or observed* at the time. They're evidence of intent, not proof
of correctness. When a test's expectation looks wrong, the right move is to question it — ideally
with whoever owns the behaviour — not to preserve it because it's green.
:::

## 10.4 Who keeps the stub honest?

Every WireMock stub in the lab encodes an assumption about the rate service: the path is
`/rates/{product}`, the currency is a query parameter, the response has `annualPercentage`. Those
assumptions were written by the *quote* team. What happens when the *rate* team renames
`annualPercentage` to `annualRate`?

- The rate team's tests pass: they test their own service.
- The quote team's tests pass: WireMock still returns `annualPercentage`, because that's what the
  stub says.
- Production breaks: every quote returns 503.

This is **stub drift**, and it's the fundamental weakness of hand-written network stubs. Both sides
are tested; the *agreement between them* is not. The lab has a related, smaller symptom: its
system-tier stub always answers `"currency":"USD"`, even for EUR requests — harmless only because
the adapter ignores the response's currency (itself a gap: a mismatched currency is never
detected).

## 10.5 Consumer-driven contract testing

**Contract testing** makes the agreement itself the thing under test. The most common flavour is
**consumer-driven contracts**, and the most common tool is **Pact**.

![Figure 10.2 Consumer-driven contracts with Pact. The consumer's expectations are recorded, shared through a broker, and replayed against the real provider in the provider's own build.](images/10-pact.png)

1. **Consumer side (the quote team).** Instead of hand-writing a WireMock stub, the adapter test
   declares an *interaction*: "given product WIDGET exists, a GET to `/rates/WIDGET?currency=USD`
   returns 200 with a body containing a decimal `annualPercentage`." Pact runs a mock server from
   that declaration — so the adapter test works just like with WireMock — and *records* the
   interaction into a contract file (a "pact").
2. **Broker.** The pact is published to a Pact Broker (or PactFlow), versioned by consumer.
3. **Provider side (the rate team).** The rate service's build downloads every consumer's pacts and
   replays each request against the *real* rate service, checking the responses match. If the rate
   team renames `annualPercentage`, *their* build fails, naming the quote service as the consumer
   that would break.
4. **Deploy gate.** Before either side deploys, `can-i-deploy` asks the broker whether the versions
   about to run together have verified contracts.

Why "consumer-driven"? Because the consumer states only what it *actually uses*. The rate service
may return twenty fields; if the quote service reads one, the contract covers one. The provider is
free to change the other nineteen.

::: {.callout .hood}
Under the hood: contract tests vs integration tests

A contract test never runs both services at the same time. The consumer tests against a mock; the
provider tests against recorded requests. That's why contract tests are fast and can run in each
team's own pipeline — and why they don't replace a small number of real end-to-end checks.
In four-axes terms: purpose = **contract**; scope = **integration** on each side; environment =
**in-process + mock server**, in each team's own CI.
:::

When is Pact worth it? When the provider is another team with its own release cycle, and a breaking
change would be costly. It needs the provider team's cooperation — they must run verification in
their pipeline — so it's as much an organisational agreement as a technical one. For a provider you
can't influence (a public third-party API), you're limited to your own side: careful adapter tests,
schema validation of responses, and monitoring in production.

::: {.callout .tryit}
Try it: design the pact

Write, in plain words, the minimal set of interactions the quote service would put in a pact with
the rate service. Include at least one error case. Which fields of the response belong in the
contract, and which don't? *Discussion in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. What are the five items in the adapter checklist?
2. Why does `HttpRateGatewayTest` not need a Spring context, and why is that good?
3. Explain the chain of events that turns an unknown product into "503, please retry later".
4. What is stub drift, and why don't both teams' test suites catch it?
5. In consumer-driven contract testing, who writes the contract, and who verifies it?
:::

::: {.callout .summary}
Summary

- **Ports and adapters** split testing cleanly: services behind a port get level-1 doubles; the
  adapter gets a level-2 double (a real HTTP stub).
- Adapter tests should cover request shape, response mapping, status classes, timeouts and
  malformed responses.
- Tests can **enshrine mistakes**: the lab's 404→503 behaviour is tested, clearly named — and
  wrong.
- Hand-written stubs **drift** from the real provider; both sides stay green while production
  breaks.
- **Consumer-driven contracts** (Pact) record what the consumer uses and verify it against the real
  provider in the provider's own build.
:::
