# 4 Test doubles demystified

::: {.callout .covers}
This chapter covers

- The five kinds of test double: dummy, stub, spy, mock and fake
- State verification versus interaction verification
- Three *levels* at which you can replace a dependency: object, network, container
- When to use a hand-written fake, Mockito, or WireMock
- How over-mocking produces tests that break on every refactor
:::

**After this chapter you will be able to** name the double in any test, choose the right kind for a
job, and recognise the "mock everything" anti-pattern.

::: {.callout .recall}
Warm-up

1. What does the "R" in FIRST stand for, and name two things that threaten it. (sections 3.2, 3.3)
2. Which axis distinguishes a regression test from a unit test? (2.4)
3. Why is `QuoteServiceTest` still a unit test even though it uses a real `QuoteCalculator`? (2.2.1)
:::

## 4.1 Why doubles exist

Our quote service depends on a rate service run by another team. To test `QuoteService` we need
*some* answer to "what's the rate for WIDGET?" — but we don't want the real rate service: it's slow,
it may be down, we can't make it answer "500" on demand, and its rates change daily so our expected
totals would too.

A **test double** is anything that stands in for a real dependency during a test. The term,
popularised by Gerard Meszaros, comes from film: a stunt double stands in for the actor when the
real one would be too expensive or dangerous. Just as in film, there are several kinds.

## 4.2 The five kinds

![Figure 4.1 The five kinds of double. Each adds a capability to the one before; a fake is different in kind — it really works.](images/04-doubles.png)

**Dummy.** Passed to satisfy a signature, never used. If a constructor needs a `Clock` but the
test's code path never reads the time, a dummy will do. Often just `null` or `mock(Clock.class)`.

**Stub.** Returns canned answers. "When asked for WIDGET, say 5.00." A stub has no opinion about
whether or how often it's called.

```java
given(rateGateway.rateFor("WIDGET", "USD")).willReturn(new Rate(new BigDecimal("5.00"), "USD"));
```

**Spy.** A stub that also *records* how it was called, so the test can inspect the calls
afterwards. Mockito mocks are spies in this sense: every call is recorded, and `then(...).should()`
checks the record.

**Mock.** Strictly speaking, a double programmed with *expectations* up front, which fails the test
if the calls don't match. In everyday Java talk, "mock" means "anything created by Mockito", and
the distinction between spy and mock has largely faded. What matters is *how you use it*: to supply
answers (stubbing) or to verify interactions (mocking).

**Fake.** A working, lightweight implementation. Not canned answers — real behaviour, just cheaper.
An in-memory repository backed by a `HashMap` is a fake. So is the lab's `StubRateGateway` — despite
its name.

```java
// Listing 4.1 StubRateGateway: a hand-written fake (src/testFixtures/.../rate/StubRateGateway.java)
public final class StubRateGateway implements RateGateway {
    private final BigDecimal defaultRate;
    private final Map<String, BigDecimal> ratesByProduct = new HashMap<>();

    public static StubRateGateway returning(String annualPercentage) {    // #1
        return new StubRateGateway(annualPercentage);
    }

    public StubRateGateway withRate(String productCode, String annualPercentage) {  // #2
        this.ratesByProduct.put(productCode, new BigDecimal(annualPercentage));
        return this;
    }

    @Override
    public Rate rateFor(String productCode, String currency) {              // #3
        return new Rate(this.ratesByProduct.getOrDefault(productCode, this.defaultRate), currency);
    }
}
```

::: {.annotations}
1. A readable factory: `StubRateGateway.returning("4.25")`.
2. Per-product configuration, fluent style.
3. Real (if simple) behaviour: looks up the product, falls back to a default.
:::

::: {.callout .myth}
Misconception: "A class named Stub is a stub"

Names in codebases are loose. `StubRateGateway` behaves like a small, real rate service — a fake.
`RateServiceStub` in the lab is a WireMock wrapper — a stub at the network level. Don't classify
doubles by their names; classify them by what they *do*: answer, record, verify, or actually work.
:::

## 4.3 State verification versus interaction verification

There are two fundamentally different ways to check that code did the right thing.

**State verification** checks the *result*: the return value, or the state of something afterwards.

```java
QuoteResponse response = service.createQuote(request);
assertThat(response.total()).isEqualByComparingTo("10500.00");     // state
```

**Interaction verification** checks the *calls* the code made to its collaborators.

```java
then(rateGateway).should().rateFor("GIZMO", "EUR");               // interaction
then(repository).should(never()).save(any(Quote.class));          // interaction
```

Both appear in the lab, and both are legitimate. The rule of thumb:

- **Prefer state verification** whenever there's an observable result. It tests *what* happened,
  so the test survives refactoring of *how*.
- **Use interaction verification** when the interaction *is* the behaviour — typically at the
  edge of the system, where the only observable effect is a call outward. "When the rate service
  fails, nothing is saved" is a good example: there's no return value to inspect (an exception is
  thrown), so the absence of a `save` call is the behaviour.

```java
// Listing 4.2 Interaction verification where it's the right tool (QuoteServiceTest)
@Test
void saves_nothing_when_the_rate_service_cannot_be_reached() {
    given(rateGateway.rateFor("WIDGET", "USD")).willThrow(new RateUnavailableException("boom"));

    assertThatThrownBy(() -> service.createQuote(new QuoteRequest("cust-1", "WIDGET",
            new BigDecimal("1000.00"), "USD", 12)))
            .isInstanceOf(RateUnavailableException.class);

    then(repository).should(never()).save(any(Quote.class));
}
```

::: {.callout .mental}
Mental model: verify the edges, not the joints

Verify interactions at the **edges** of your system — outgoing calls that are the whole point
(save, send, publish). Don't verify interactions at the **joints** between your own classes — which
private helper was called, in what order. Joints are implementation; they're supposed to be free to
change.
:::

## 4.4 Three levels of replacement

This is the idea from this chapter that you'll use most often in Parts 2 and 3. A dependency can be
replaced at different *levels*, and each level lets different real code run.

![Figure 4.2 Three levels at which the rate service can be replaced. Each level down, more of our real code runs.](images/04-double-levels.png)

**Level 1 — replace the Java interface.** `QuoteService` talks to a `StubRateGateway` or a Mockito
mock. Our HTTP adapter doesn't run at all. Fast and simple, but it can't find bugs in the adapter:
wrong URL, wrong query parameter, JSON field misnamed, timeout not configured.

**Level 2 — replace the network peer.** The real `HttpRateGateway` runs and makes a real HTTP call —
to a **WireMock** server that we control. Now the URL, the headers, the JSON parsing and the timeout
handling are all tested. This is what `HttpRateGatewayTest` does.

```java
// Listing 4.3 A level-2 double: WireMock answering real HTTP (HttpRateGatewayTest)
server.stubFor(get(urlPathEqualTo("/rates/WIDGET"))
        .withQueryParam("currency", equalTo("USD"))
        .willReturn(okJson("""
                {"productCode":"WIDGET","annualPercentage":4.25,"currency":"USD"}
                """)));

Rate rate = this.gateway.rateFor("WIDGET", "USD");        // real HTTP call to the stub

assertThat(rate.annualPercentage()).isEqualByComparingTo("4.25");
```

**Level 3 — replace nothing inside the app.** The application runs as its real Docker image and
reaches a WireMock *container* at a configured URL. Not a single line of the application is swapped
or wired differently for the test; only its environment is. That's the black-box suite of Part 3.

| Level | What's replaced | What real code runs | Speed | Lab example |
| --- | --- | --- | --- | --- |
| 1: object | The Java interface | Service logic only | Microseconds | `QuoteServiceTest` |
| 2: network | The remote server | + HTTP adapter, JSON, timeouts | Milliseconds | `HttpRateGatewayTest` |
| 3: environment | Nothing in the app | Everything, as packaged | Seconds | Cucumber suite |

Notice that each level answers a different question, so a mature suite uses *all three* — level 1
for logic, level 2 for the adapter, level 3 for the assembled product.

## 4.5 Choosing a double

A decision guide:

1. **Is the dependency pure and fast** (like `QuoteCalculator`)? Don't double it. Use the real thing.
2. **Do you just need answers?** A stub (Mockito `given(...)`) or a small fake.
3. **Will many tests need realistic behaviour** from the same dependency? Write a **fake** once and
   share it — the lab puts `StubRateGateway` in `testFixtures` so every tier can use it.
4. **Is the call itself the behaviour** (save, send, publish)? Verify the interaction.
5. **Is the risk in the protocol** — URLs, JSON, status codes, timeouts? You need a level-2 double
   such as WireMock. No Java-level double can see a misnamed JSON field.
6. **Are you testing the assembled product?** Level 3.

::: {.callout .hood}
Under the hood: why a fake over Mockito, sometimes

The lab's comment on `StubRateGateway` says a fake is "preferred over a mocking framework for
anything beyond interaction verification: it behaves like the real thing, so a test that uses it is
testing an object graph rather than a script." A Mockito stub is a *script*: "when asked X, say Y."
If the code under test starts asking a slightly different question, the script returns `null` and
the test fails in a confusing way. A fake answers *any* reasonable question, so it's more robust to
refactoring. The price: a fake is code, and it can have bugs.
:::

## 4.6 The over-mocking trap

Here's a test pattern that looks rigorous and isn't.

```java
// Listing 4.4 Over-mocked: the test is a mirror of the implementation
@Test
void createQuote_callsEverythingInOrder() {
    given(rateGateway.rateFor(any(), any())).willReturn(rate);
    given(calculator.totalFor(any(), any(), anyInt())).willReturn(new BigDecimal("1"));  // mocked!
    given(repository.save(any())).willReturn(quote);

    service.createQuote(request);

    InOrder order = inOrder(rateGateway, calculator, repository);
    order.verify(rateGateway).rateFor(any(), any());
    order.verify(calculator).totalFor(any(), any(), anyInt());
    order.verify(repository).save(any());
}
```

What does this test prove? That `createQuote` calls three methods in the order it calls them.
It would pass if the calculator were wrong, if the wrong amount were passed, or if the result were
thrown away. And it will *fail* the day someone legitimately reorders the code (say, validating
the amount before fetching the rate). **It has high maintenance cost and near-zero bug-finding
power** — the worst combination.

Signs you're over-mocking:

- You mock pure, fast classes (`QuoteCalculator`, value objects, mappers).
- Your assertions are mostly `verify(...)` with `any()` arguments.
- A refactor that doesn't change behaviour breaks tests.
- The test reads like the implementation rewritten in Mockito.

::: {.callout .tryit}
Try it: classify the doubles

For each, name the kind of double and the level: (a) `mock(QuoteRepository.class)` with
`save` answering its argument; (b) the `WireMockServer` in `HttpRateGatewayTest`; (c)
`StubRateGateway.returning("4.25")`; (d) `then(repository).should(never()).save(...)`;
(e) the PostgreSQL container in `QuoteRepositoryTest`. *Hint for (e): is it a double at all?*
Answers in appendix B.
:::

::: {.callout .quiz}
Check your understanding

1. What's the difference between a stub and a fake? Which is `StubRateGateway`?
2. When is interaction verification the *right* tool? Give an example from the lab.
3. What kinds of bug can a level-2 double (WireMock) find that a level-1 double (Mockito) can't?
4. Why is mocking `QuoteCalculator` in a `QuoteService` test a bad idea?
5. A test uses `InOrder` to verify five collaborators with `any()` arguments. What would you say in
   code review?
:::

::: {.callout .summary}
Summary

- **Dummy, stub, spy, mock, fake**: from "never used" to "actually works". Classify by behaviour,
  not by class name.
- **State verification** checks results and survives refactors; **interaction verification** is
  for edges where the call *is* the behaviour.
- Dependencies can be replaced at three **levels**: the Java interface, the network peer, or the
  environment. Each level lets more real code run and answers a different question.
- Don't double pure, fast code. Share realistic **fakes** through test fixtures.
- **Over-mocking** creates tests that mirror the implementation: expensive to maintain, nearly
  useless at finding bugs.
:::
