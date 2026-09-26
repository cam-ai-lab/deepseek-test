# 3 The anatomy of a good test

::: {.callout .covers}
This chapter covers

- Arrange–Act–Assert and Given–When–Then: the same shape under two names
- The FIRST properties of a good test
- Determinism: controlling time, randomness and order
- Naming, assertions, and "one reason to fail"
- Where flaky tests come from
:::

**After this chapter you will be able to** read any test and judge its structure, spot the most
common sources of flakiness before they bite, and write assertions that fail with a useful message.

::: {.callout .recall}
Warm-up

1. Name the four axes of testing. (section 2.2)
2. Is `QuoteServiceTest` white-box or black-box? Why? (2.2.2)
3. What's the difference between a system test and an ephemeral test? (2.4)
:::

## 3.1 One shape, two names

Almost every good test has three parts, in this order:

![Figure 3.1 Every test builds a world, does one thing to it, and checks the result.](images/03-aaa.png)

- **Arrange** (or **Given**): build the world the test needs — objects, stubbed answers, rows in a
  database.
- **Act** (or **When**): do *one* thing — call one method, send one request.
- **Assert** (or **Then**): check the observable outcome.

"Arrange–Act–Assert" is the name developers use; "Given–When–Then" is the same idea as it appears
in behaviour-driven development and in Cucumber (chapter 13). Listing 3.1 shows the shape in the
lab.

```java
// Listing 3.1 A test with a clean Arrange–Act–Assert shape (QuoteServiceTest)
@Test
void prices_the_command_with_the_rate_from_the_gateway() {
    given(rateGateway.rateFor("WIDGET", "USD"))
            .willReturn(new Rate(new BigDecimal("5.00"), "USD"));                // #1
    given(repository.save(any(Quote.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

    QuoteResponse response = service.createQuote(new QuoteRequest("cust-1", "WIDGET",
            new BigDecimal("10000.00"), "USD", 12));                              // #2

    assertThat(response.customerId()).isEqualTo("cust-1");                       // #3
    assertThat(response.annualRatePercent()).isEqualByComparingTo("5.00");
    assertThat(response.total()).isEqualByComparingTo("10500.00");
    assertThat(response.createdAt()).isEqualTo(NOW);
    assertThat(response.quoteId()).isNotNull();
}
```

::: {.annotations}
1. **Arrange.** The gateway will answer 5.00%; the repository returns whatever it's given.
2. **Act.** Exactly one call to the thing under test.
3. **Assert.** Only on the returned value — the observable outcome.
:::

Blank lines separate the three parts. It sounds trivial; it isn't. When a test's parts are
tangled — an assertion in the middle of the setup, a second "act" after the first assertion — it's
a sign the test is checking more than one behaviour, and when it fails you'll have to read all of it
to know which behaviour broke.

::: {.callout .mental}
Mental model: one Act per test

If you find yourself writing *act, assert, act again, assert again*, you have two tests glued
together. Split them. The only common exception is a round trip — "create, then fetch" — where
the second act exists purely to *observe* the first.
:::

## 3.2 FIRST: five properties of a good test

A handy checklist, coined in the early 2000s and still accurate:

| Letter | Property | Meaning | In the lab |
| --- | --- | --- | --- |
| **F** | Fast | Runs in milliseconds (unit) or seconds (broader) | `QuoteCalculatorTest` needs no Spring, no I/O |
| **I** | Isolated | Doesn't depend on other tests or their order | Each test builds its own objects; the shared DB uses unique ids |
| **R** | Repeatable | Same result every run, on every machine | Fixed `Clock`, containers instead of a shared DB |
| **S** | Self-validating | Passes or fails by itself; no human reads a log to decide | Assertions, never `System.out.println` checks |
| **T** | Timely | Written close to the code it tests | Tests live next to the tier they belong to |

Repeatability is the one that most often goes wrong, so let's spend some time on it.

## 3.3 Determinism: controlling what you don't own

A test is deterministic when the only thing that can change its result is the code under test.
Four things routinely break that: **time**, **randomness**, **order**, and **the environment**.

### 3.3.1 Time

Suppose `QuoteService` called `Instant.now()` directly. How would you assert on `createdAt`? You
couldn't compare it to an exact value. You'd write something like "within two seconds of now" —
which passes on your laptop and fails on an overloaded CI runner at midnight on New Year's Eve.

The lab avoids this by injecting a `java.time.Clock`:

```java
// Listing 3.2 Time as a dependency (TimeConfig and QuoteServiceTest)
@Bean
Clock clock() {                         // production: the real system clock
    return Clock.systemUTC();
}

// in the test:
private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");
service = new QuoteService(rateGateway, new QuoteCalculator(), repository,
        Clock.fixed(NOW, ZoneOffset.UTC));  // test: time stands still
```

Now `createdAt` is asserted *exactly*. That's the design pressure of section 1.1 in action: the
wish to test made the dependency on time explicit, and the code is better for it — you could, for
example, now run the service "as of" a past date for a replay.

### 3.3.2 Randomness

The service creates ids with `UUID.randomUUID()`. The tests don't try to predict them; they assert
`quoteId().isNotNull()` and use the id they got back. That's the right call for identifiers. When
randomness affects *behaviour* — shuffling, sampling, retry jitter — inject a seeded `Random` or a
generator interface, exactly as with the clock.

### 3.3.3 Order and shared state

Tests that share mutable state — a static field, a database table, a stub server — can pass alone
and fail together, or pass in one order and fail in another. The lab has two shared resources:

- **One PostgreSQL container for the whole run** (chapter 9). `@DataJpaTest` wraps each test in a
  transaction that is rolled back, so rows don't leak between tests. The black-box suite in Part 3
  can't roll back — it talks HTTP — so it gives each scenario a unique `customerId` instead.
- **One WireMock server for the system tier.** Each test class calls `RateServiceStub.reset()`
  before it runs, so stubs from a previous class can't answer for this one.

### 3.3.4 The environment

Ports, file paths, time zones, locale, available memory. The lab picks random free ports for every
server it starts (`dynamicPort()` for WireMock, `RANDOM_PORT` for Spring, mapped ports for
containers). Hard-coding `8080` in a test is a flaky test waiting for two builds to run on the same
machine.

![Figure 3.2 The usual suspects behind a flaky test.](images/03-flakiness.png)

::: {.callout .myth}
Misconception: "It's flaky, just add a retry"

Retrying a flaky test hides the symptom and keeps the cause. Worse, the cause is sometimes a real
race condition in *production* code that the test happened to expose. Treat every flaky test as a
bug with an unknown location. Chapter 19 covers a quarantine policy so that flaky tests don't block
everyone while you investigate.
:::

## 3.4 Names that explain

Compare two names for the same test:

- `testCalculate3()`
- `rounds_half_up_so_a_half_cent_goes_to_the_customer()`

When the second one fails in CI, you know which business rule broke before you open the file. The
lab's convention is a sentence in `snake_case` describing the *behaviour*, not the method:
`saves_nothing_when_the_rate_service_cannot_be_reached`, `treats_an_unknown_product_as_rate_unavailable`.

A useful template: **\<does what\> when \<condition\>**, or **\<given state\>, \<outcome\>**. Avoid
names that restate the method (`testCreateQuote`) — they tell you what was called, not what was
expected. JUnit 5's `@DisplayName` lets you add human-readable class-level context, which the lab
uses: `@DisplayName("POST /api/v1/quotes")`.

## 3.5 Assertions that fail well

A test's assertion message is the first thing you'll read when it fails, often in a CI log at an
inconvenient hour. Make it count.

**Prefer specific assertions over boolean ones.**

```java
assertTrue(total.equals(expected));          // fails with: expected true but was false
assertThat(total).isEqualTo(expected);       // fails with: expected 10425.00 but was 10425.0
```

**Choose equality semantics deliberately.** This matters so much in the lab that it deserves its
own listing.

```java
// Listing 3.3 Two kinds of BigDecimal equality
BigDecimal a = new BigDecimal("10500.00");
BigDecimal b = new BigDecimal("10500.0000");

assertThat(a).isEqualByComparingTo(b);   // passes: 10500.00 compareTo 10500.0000 == 0
assertThat(a).isEqualTo(b);              // FAILS:  equals() also compares scale (2 vs 4)
```

`isEqualByComparingTo` asks "are these the same *number*?" `isEqualTo` on a `BigDecimal` asks "are
these the same *value with the same scale*?" In a calculation test, you usually want the former.
In a contract test — "what does the client receive?" — you want the latter, because `10500.00` and
`10500.0000` are different strings on the wire. The lab's system test used the first kind where it
needed the second, which is part of why it's blind to the scale bug. We'll fix that in chapter 15.

**One reason to fail.** Several assertions in one test are fine *as long as they all check the same
behaviour*. Listing 3.1 has five assertions, all about "the response reflects the gateway's rate".
A test that asserts the price *and* that an audit log was written *and* that an email was queued
has three reasons to fail; split it.

::: {.callout .hood}
Under the hood: soft assertions

AssertJ stops at the first failing assertion by default. If you want to see *all* the mismatches in
one run — handy when comparing many fields of a response — use `SoftAssertions`:
`SoftAssertions.assertSoftly(softly -> { softly.assertThat(...)...; })`. It collects every failure
and reports them together.
:::

## 3.6 Worked example: improving a weak test

Here's a test you might find in an older codebase, testing the same service.

```java
// Listing 3.4 A weak test
@Test
void test1() throws Exception {
    QuoteService s = new QuoteService(gw, new QuoteCalculator(), repo, Clock.systemUTC());
    when(gw.rateFor(any(), any())).thenReturn(new Rate(new BigDecimal("5"), "USD"));
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    QuoteResponse r = s.createQuote(new QuoteRequest("c", "W", new BigDecimal("100"), "USD", 12));
    assertTrue(r.total().doubleValue() == 105.0);
    verify(repo).save(any());
    assertTrue(r.createdAt().isBefore(Instant.now().plusSeconds(1)));
}
```

Problems, in the language of this chapter:

1. **Name** says nothing.
2. **Real clock** — the `createdAt` assertion is a timing gamble.
3. **`doubleValue() == 105.0`** — comparing money as a `double`, and with a boolean assertion that
   will report "expected true".
4. **Three reasons to fail**: the total, the save interaction, the timestamp.
5. **`any()` matchers** on the gateway — if the service passed the wrong product code, this test
   wouldn't notice.

And the improved version, which is essentially what the lab has:

```java
// Listing 3.5 The same intent, done well
@Test
void prices_the_command_with_the_rate_for_its_product_and_currency() {
    given(rateGateway.rateFor("WIDGET", "USD")).willReturn(new Rate(new BigDecimal("5.00"), "USD"));
    given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));

    QuoteResponse response = service.createQuote(QuoteTestData.request("100.00", 12));

    assertThat(response.total()).isEqualByComparingTo("105.00");
}
```

The fixed clock lives in `setUp`, the save interaction gets its own test
(`persists_exactly_what_it_returns`), and the timestamp gets its own assertion elsewhere.

::: {.callout .tryit}
Try it: faded example

Here's a half-finished test for the not-found path of `QuoteService.findQuote`. Fill in the gaps
(marked `___`), keeping to one Act and a behaviour-describing name. *A solution is in appendix B.*

```java
@Test
void ___() {
    UUID unknown = UUID.randomUUID();
    given(repository.findById(unknown)).willReturn(___);

    assertThatThrownBy(() -> ___)
            .isInstanceOf(QuoteNotFoundException.class)
            .hasMessageContaining(___);
}
```
:::

::: {.callout .quiz}
Check your understanding

1. What do Arrange–Act–Assert and Given–When–Then have in common, and where does each name come
   from?
2. Which FIRST property does an injected `Clock` protect, and how?
3. When should you use `isEqualTo` rather than `isEqualByComparingTo` on a `BigDecimal`? Give an
   example from the lab.
4. Why is "just add a retry" a dangerous response to a flaky test?
5. A test asserts the response body *and* that an email was sent. What would you do, and why?
:::

::: {.callout .summary}
Summary

- Good tests have one **Arrange**, one **Act** and one **Assert** section — also known as
  **Given–When–Then**.
- **FIRST**: fast, isolated, repeatable, self-validating, timely.
- **Determinism** means controlling time (inject a `Clock`), randomness, order and the environment
  (random ports, unique data).
- Names should describe **behaviour and condition**, not the method called.
- Choose assertion semantics deliberately — especially **`isEqualTo` versus `isEqualByComparingTo`**
  for money, where scale may or may not be part of the contract.
- Flaky tests are bugs with unknown locations; never paper over them with retries.
:::
