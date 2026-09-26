# 6 Regression anchors and golden files

::: {.callout .covers}
This chapter covers

- What makes a test a *regression* test, as opposed to a functional one
- Frozen expectations: the pricing regression table
- Golden-file (snapshot/approval) testing of a whole response
- Normalising values that legitimately change
- What golden files catch, what they silently miss, and how to review a failure
:::

**After this chapter you will be able to** decide when to freeze behaviour, build a golden-file
test that isn't brittle, and explain exactly which bugs it can and can't catch.

::: {.callout .recall}
Warm-up

1. Name the three partitions of `termMonths` and its four boundary values. (section 5.2)
2. Why does the calculator test use `isEqualTo` rather than `isEqualByComparingTo`? (5.3)
3. On which axis does "regression" live? (2.2.3)
:::

## 6.1 Two ways to know the right answer

A functional test knows the right answer because someone *reasoned it out*: 10,000 at 5% for a year
is 10,500. The expected value comes from the specification.

A regression test knows the right answer because the system *produced it once and someone agreed*.
The expected value comes from observed behaviour that was accepted as correct at some point. It
doesn't claim the value is right in some absolute sense; it claims **the value hasn't changed**.

That distinction changes how you react to a failure:

- A functional test fails → the code is wrong (or the spec changed). Fix the code.
- A regression test fails → *something changed*. You must decide whether the change was intended.
  If it was, update the anchor deliberately; if not, you've caught a regression.

The lab's comment on `QuotePricingRegressionTest` puts it perfectly: *"If a change makes one fail
you have re-priced an existing product, which is a commercial decision rather than a refactor — so
the failure should be reviewed, not 'fixed' by editing the expectation."*

## 6.2 Frozen prices

```java
// Listing 6.1 Pricing anchors (QuotePricingRegressionTest)
@ParameterizedTest(name = "golden: {0} at {1}% over {2} months = {3}")
@CsvSource({
        " 10000.00,  4.25,  12,  10425.00",
        " 10000.00,  5.00,  12,  10500.00",
        " 25000.00,  3.75,  60,  29687.50",
        "   500.00, 12.00,   6,    530.00",
        "   999.99,  0.99,   3,   1002.46",
        "123456.78,  7.99,  48, 162913.57",
})
void frozen_prices_do_not_drift(String amount, String annualPercentage, int termMonths,
        String expectedTotal) { ... }
```

Structurally it's identical to the functional table in chapter 5. The difference is *intent*, and
it's signalled three ways: the class name, the display name ("golden: …"), and the Javadoc. That
matters for the reader of a failure: a developer who sees `QuoteCalculatorTest` fail after a
refactor fixes the code; one who sees `QuotePricingRegressionTest` fail should go and talk to the
product owner.

::: {.callout .mental}
Mental model: a tripwire, not a judge

A functional test is a **judge**: it rules on whether behaviour is correct. A regression test is a
**tripwire**: it only tells you something moved. Tripwires are cheap and sensitive, and a tripped
wire always needs a human to look at what tripped it.
:::

## 6.3 Golden files: freezing a whole output

When the output is big — a JSON document, a PDF, a report — writing an assertion per field is
tedious and easy to get incomplete. A **golden-file test** (also called a *snapshot* or *approval*
test) stores the entire expected output in a file, and compares the actual output against it.

![Figure 6.1 The golden-file loop. Normalisation removes values that legitimately differ; any remaining difference needs a human decision.](images/06-golden.png)

The lab freezes the JSON response for a known command:

```json
// Listing 6.2 The golden file (src/testFixtures/resources/golden/quote-response.json)
{
  "quoteId": "<quoteId>",
  "customerId": "golden-customer",
  "productCode": "WIDGET",
  "amount": 10000.00,
  "currency": "USD",
  "termMonths": 12,
  "annualRatePercent": 4.25,
  "total": 10425.00,
  "createdAt": "<createdAt>"
}
```

```java
// Listing 6.3 Comparing against the golden file (QuoteWireContractRegressionTest)
@Test
void the_json_body_for_a_known_command_matches_the_golden_file() throws IOException {
    RateServiceStub.reset();
    RateServiceStub.stubRate("WIDGET", "4.25");

    String actual = this.rest.postForEntity("/api/v1/quotes", jsonEntity(GOLDEN_COMMAND), String.class)
            .getBody();                                                            // #1

    assertThat(flatten(actual)).isEqualTo(flatten(goldenFile()));
}

private static String flatten(String json) {
    return json.replaceAll("\\s+", "")                                             // #2
            .replaceAll("\"quoteId\":\"[^\"]*\"", "\"quoteId\":\"<quoteId>\"")      // #3
            .replaceAll("\"createdAt\":\"[^\"]*\"", "\"createdAt\":\"<createdAt>\"");
}
```

::: {.annotations}
1. The response is read as a raw `String` — not deserialised into `QuoteResponse`. That makes this
   the one test in the lab that sees the wire format as a client would.
2. Whitespace is insignificant in JSON, so strip it.
3. Values that change every run — a random UUID and the current time — are replaced by placeholders
   on *both* sides. This is **normalisation**.
:::

Because it compares text, it is sensitive to number *scale*: if `10425.00` ever became `10425.0`,
this test would fail — which the typed system test, comparing with `isEqualByComparingTo`, would not
notice.

## 6.4 What the golden file silently misses

Golden files are powerful and have sharp edges. Every one of these applies to the lab.

**It only checks the inputs you froze.** The golden command sends `"amount":10000.00`. What does the
service return if a client sends `"amount":10000`? The service echoes the request's scale, so it
returns `"amount":10000` — and the golden file can't know, because it never sends that input. The
test freezes *one point* in the input space, and the scale behaviour varies across the space.

**It only checks one operation.** It freezes the POST response. The GET response for the same quote
has a different shape (`10000.0000`, `4.2500`), and nothing freezes that. This is the scale bug
from chapter 1 again: the golden file is sensitive enough to see it, but it's *pointed in the wrong
direction*.

**Its normalisation can hide things.** Blanking `createdAt` entirely means the test can't notice if
the timestamp format changes from `2026-01-15T10:30:00Z` to `1736937000` (epoch seconds) — a
breaking change for every client. Better: replace the *value* but check the *shape*, for example
with a regex like `\d{4}-\d{2}-\d{2}T[^"]+Z`.

**It freezes whatever the system did when it was recorded — bugs included.** If the output was
wrong when you approved it, the golden file protects the bug. This is the fundamental trade of
regression testing: it guards against change, not against being wrong.

::: {.callout .myth}
Misconception: "A golden-file failure means the test is broken — just regenerate it"

Many snapshot tools offer a one-command "update all snapshots". That's the tripwire's *reset
switch*, and pressing it without reading the diff defeats the test's entire purpose. A healthy
team treats a golden-file diff like a code-review diff: someone reads it, and the commit message
says why the contract changed.
:::

## 6.5 Making golden files robust

Some practices that separate useful golden-file tests from brittle ones:

1. **Normalise the minimum.** Blank only what is genuinely non-deterministic (ids, timestamps), and
   prefer shape checks to blanking.
2. **Make the system deterministic where you can** instead of normalising. A fixed `Clock` removes
   the need to blank `createdAt` at all — though in a system test that means configuring the
   running app's clock, which has its own costs.
3. **Store golden files next to the tests, in version control**, so diffs show up in code review.
4. **Cover the operations clients actually use** — here, both POST and GET.
5. **Choose a comparison that matches the contract.** The lab compares whitespace-stripped text,
   which is order-sensitive: if Jackson started emitting fields in a different order, the test
   would fail even though most JSON clients wouldn't care. A structural comparison (for example
   with JsonUnit or JSONAssert) can ignore order while staying strict on values and — if you
   configure it so — on number scale. Decide which of those your clients care about.

::: {.callout .tryit}
Try it: point the tripwire the other way

Sketch (on paper or in code) a second golden-file test that POSTs the golden command, then GETs the
created quote and compares the *GET* body against a golden file. What would that file contain
today? Would you commit it as-is? *Discussion in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. How should your reaction differ when a functional test fails versus a regression test?
2. What is normalisation in a golden-file test, and what's the risk of overdoing it?
3. Name two bugs or changes the lab's golden-file test cannot detect, and say why.
4. The golden-file test reads the response as a `String`. Why is that essential to its purpose?
5. "Update all snapshots" — when is that the right thing to do?
:::

::: {.callout .summary}
Summary

- **Regression tests** freeze observed, accepted behaviour. A failure means *something changed*;
  a human must decide whether it was intended.
- Signal intent clearly (class names, display names, comments) so the person reading a failure
  knows whether to fix code or consult the business.
- **Golden files** freeze a whole output. They need **normalisation** for non-deterministic values,
  and they only protect the inputs and operations they were recorded with.
- A golden file freezes bugs too. Review diffs; never regenerate blindly.
- The lab's golden test can see scale differences but only looks at POST — so the POST/GET mismatch
  slips past it.
:::
