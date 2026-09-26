# 16 Hunting what nobody specified

::: {.callout .covers}
This chapter covers

- Why example-based tests only cover the inputs someone thought of
- Negative testing and boundaries at the system level
- Properties and invariants: rules that hold for every input
- Property-based testing with jqwik, and shrinking
- Fuzzing an HTTP API from its specification (Schemathesis)
- Exploratory testing: the human in the loop
- Finding the lab's overflow 500
:::

**After this chapter you will be able to** write down the invariants of a service, turn them into
generated tests at the right tier, and explain why "no 5xx for any valid input" is one of the most
valuable single checks you can run.

::: {.callout .recall}
Warm-up

1. What is boundary-value analysis? (section 5.2)
2. Why is a known bug written as the correct behaviour and then tagged? (15.7)
3. What does `NUMERIC(19, 4)` allow, in digits before and after the decimal point? (9.2)
:::

## 16.1 The space nobody visits

Every test in the lab so far is an **example**: a specific input, chosen by a person, with an
expected output. Examples are wonderful for communicating intent. But look at how they cover the
space of possible requests:

![Figure 16.1 Example tests visit a handful of points in the space of valid requests. Most of the space is never tried — and that's where the overflow lives.](images/16-input-space.png)

A person picks inputs they can imagine: 10,000, 1,500.55, 0.01. The boundary cases from chapter 5
push toward edges, but only on the edges someone *listed*. The validation rule for `amount` says up
to 15 integer digits. Nobody listed "the biggest valid amount" as a case for the *whole system* —
only for validation.

## 16.2 Finding the overflow

Let's reason it through, the way this chapter wants you to reason.

- `QuoteRequest` accepts `amount` with up to **15 integer digits**: `999999999999999.9999` is valid.
- The calculator computes `total = amount × (1 + rate × term / 1200)`. For any positive rate, total
  is larger than amount.
- The database column `total` is `NUMERIC(19, 4)`: 19 digits in total, 4 after the point, so at
  most **15 before it**.

So a valid amount near the top of the range, at any normal rate, produces a total with **16**
integer digits. For example, 960 trillion at 4.25% for 12 months totals about 1.0008 × 10^15.
PostgreSQL rejects the INSERT with *numeric field overflow*, Spring translates that into a
`DataIntegrityViolationException`, no handler maps it, and the client gets a **500**.

The same shape of bug exists for the rate: `annual_rate_percent` is `NUMERIC(9, 4)`, so a rate
service answering 100,000% or more also produces a 500 — and nothing in the service validates rates
at all (a negative rate is accepted too).

Written as a scenario — the correct behaviour, tagged as a known bug:

```gherkin
# Listing 16.1 features/quotes/known_bugs.feature
@known-bug
Scenario: The largest valid amount never causes a server error
  Given the rate service quotes 4.25% for product "WIDGET" in "USD"
  When I request a quote for 999999999999999.9999 USD over 12 months of product "WIDGET"
  Then the response status is not a server error
```

Notice the Then step: not "status is 201" but "not a server error". We haven't *decided* what the
right answer is — maybe the validation limit should be lower, maybe the column wider, maybe a 422
"amount too large for this term". What we know for certain is that **a 500 is never right for a
request that passed validation**. That's an invariant, and it's the idea behind the rest of this
chapter.

::: {.callout .mental}
Mental model: 500 means "we didn't think of this"

A 4xx says "your request is wrong, here's why". A 5xx says "something happened that we never
planned for". For inputs that pass validation, every 5xx (other than a deliberate 503 for a known
dependency outage) is a gap in the design. Hunting for them is one of the highest-yield things a
test suite can do.
:::

## 16.3 Negative testing

**Negative tests** check that the system *rejects* what it should, and fails *gracefully*. At the
system level, a useful checklist for an HTTP API:

| Category | Examples for `POST /api/v1/quotes` |
| --- | --- |
| Malformed | Invalid JSON, wrong content type, empty body |
| Wrong types | `"amount":"ten"`, `"termMonths":1.5`, arrays where objects belong |
| Missing / null | Each required field absent; each set to `null` |
| Boundaries | 0, 0.01, max digits, max digits + 1, 600, 601 |
| Unexpected extras | Unknown fields; a very long `customerId` |
| Encoding | Unicode in `customerId`; leading/trailing spaces; `"usd"` in lower case |
| Hostile | SQL-ish strings, very large bodies, deeply nested JSON |

For each, the question is the same: *does it answer with a sensible 4xx and a problem document, or
does it fall over?* Two lab examples worth checking:

- `"termMonths"` is a primitive `int`, so it can never be `null`. Depending on the JSON mapper's
  settings (Jackson 2 and Jackson 3 differ here), a command with the field **missing** either becomes
  0 and fails with "must be greater than or equal to 1", or fails deserialisation with a generic
  "malformed request". Neither says "termMonths is required". Which one does the shipped image do? A
  black-box scenario answers that in one line; reading the code won't.
- `"currency":"usd"` passes validation (three characters), is sent to the rate service in lower case,
  and is stored as-is. Is that intended? No test says.

## 16.4 Properties and invariants

A **property** is a statement that should hold for *every* input in some set. Writing them down is
valuable even before you automate anything, because it forces precision. For the quote service:

| Invariant | Tier where it's cheapest to check |
| --- | --- |
| For any non-negative rate and term, `total ≥ amount` | Unit (calculator) |
| The total always has exactly two decimal places | Unit (calculator) |
| Doubling the term doubles the interest, to within a cent | Unit (calculator) |
| Any request that passes validation never gets a 5xx (except 503 for rate outages) | System (black-box) |
| Every successful POST can be fetched by GET with an identical body | System (black-box) |
| A failed POST stores nothing | System (black-box) |

Notice the split. Arithmetic invariants are cheapest at unit level. Invariants that involve the
database, the HTTP layer and configuration can only be checked at system scope — which is exactly
where the overflow and the POST/GET mismatch live.

## 16.5 Property-based testing with jqwik

**Property-based testing** automates this: a library generates hundreds of inputs, checks the
property for each, and — crucially — when one fails, **shrinks** it to the simplest failing input.

![Figure 16.2 The property-based loop: generate, check the invariant, and on failure shrink to a minimal reproducible case.](images/16-fuzz-loop.png)

In Java, jqwik is a JUnit Platform engine that works much like `@ParameterizedTest`:

```java
// Listing 16.2 Calculator properties with jqwik (unit tier)
class QuoteCalculatorProperties {

    private final QuoteCalculator calculator = new QuoteCalculator();

    @Property
    void total_is_never_less_than_the_amount(
            @ForAll @BigRange(min = "0.01", max = "1000000000") @Scale(4) BigDecimal amount,   // #1
            @ForAll @BigRange(min = "0", max = "100") @Scale(4) BigDecimal rate,
            @ForAll @IntRange(min = 0, max = 600) int termMonths) {
        assertThat(calculator.totalFor(amount, rate, termMonths))
                .isGreaterThanOrEqualTo(amount.setScale(2, RoundingMode.HALF_UP));            // #2
    }

    @Property
    void total_always_has_two_decimal_places(
            @ForAll @BigRange(min = "0.01", max = "999999999999999.9999") @Scale(4) BigDecimal amount,
            @ForAll @BigRange(min = "0", max = "100") @Scale(4) BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 600) int termMonths) {
        assertThat(calculator.totalFor(amount, rate, termMonths).scale()).isEqualTo(2);
    }
}
```

::: {.annotations}
1. Generators constrained to the domain: amounts with four decimal places, rates up to 100%.
2. Careful: the amount has four decimals and the total two, so compare with the amount rounded the
   same way. Writing properties precisely often surfaces exactly this kind of subtlety.
:::

By default jqwik tries 1,000 combinations per property and deliberately favours edge values —
minimums, maximums, zero — which is boundary-value analysis done by a machine. If a property fails
for, say, `amount = 734.2291, rate = 12.0001, term = 17`, jqwik shrinks toward simpler values and
reports the smallest input that still fails, plus a seed so you can replay it exactly.

::: {.callout .myth}
Misconception: "Random tests are flaky tests"

Property-based tests are random but **reproducible**: every run prints its seed, and a failing seed
is stored and replayed first on the next run. They're not flaky in the chapter 3 sense — the
randomness is in the *inputs*, which are recorded, not in the environment.
:::

At system scope, the same idea applies: generate valid commands, send them to the black-box stack,
and check "no 5xx" and "GET equals POST". It's slower — each case is a real HTTP round trip — so you
run tens of cases rather than thousands, typically in a nightly job.

## 16.6 Fuzzing from the API specification

If the service publishes an **OpenAPI** description of its endpoints (Spring can generate one with
springdoc-openapi), a tool can derive test inputs from the specification itself. **Schemathesis** is
a Python tool that does exactly this: it reads the schema, generates requests — valid ones, edge
cases and deliberately invalid ones — sends them to a running service, and checks responses against
generic invariants: no 5xx, responses match the documented schema, documented status codes only.

Against the black-box stack, a run looks roughly like this (check the current CLI documentation;
options change between major versions):

```bash
schemathesis run http://localhost:<app-port>/v3/api-docs
```

This is where "use some Python if it helps" from the plan earns its place: the team doesn't write
the fuzzer, it just points one at the same compose stack the Cucumber suite uses. On this service,
it would find the overflow within seconds, because it generates maximum-length decimals as a matter
of routine. It would also flag the scale difference between POST and GET if the OpenAPI schema
declared a fixed number format.

::: {.callout .hood}
Under the hood: where fuzzers sit on the four axes

Scope: **system**. Visibility: **black-box** (it knows only the published schema). Purpose:
**robustness / contract**. Environment: **containers or ephemeral environment**, usually
**nightly**, because runs are long and their findings need triage rather than blocking a merge.
:::

## 16.7 Exploratory testing

Automated generation is powerful, but not creative. **Exploratory testing** is a skilled human
investigating the system with a goal, designing and running tests on the fly, and learning as they
go — typically in a time-boxed session with a *charter*: "Explore how the quote service handles
extreme amounts and rates, to discover crashes and confusing responses."

It finds what no one specified *and* what no generator was told to look for: confusing error
messages, inconsistent naming between fields, a `Location` header that uses `http` behind a TLS
proxy. Its output isn't a green tick; it's **new examples and new invariants** — which you then
automate at the cheapest tier that can see them. That's the loop this whole book describes: humans
discover, machines guard.

::: {.callout .tryit}
Try it: write the charter and the invariants

Write a one-sentence exploratory charter for the GET endpoint. Then list three invariants for it
(for example, "fetching the same id twice returns identical bodies"). For each, name the cheapest
tier that could check it. *Discussion in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. Walk through why a valid amount can produce a 500 in the lab.
2. Why does the overflow scenario assert "not a server error" rather than a specific status?
3. Give one invariant that's cheapest at unit level and one that needs system scope. Why the
   difference?
4. What does shrinking do, and why is it valuable?
5. Where do exploratory testing and property-based testing each find bugs that example tests miss?
:::

::: {.callout .summary}
Summary

- Example tests visit the inputs people imagine. Most of the valid input space is never tried.
- The lab's **overflow**: validation allows 15 integer digits, the total needs 16, the column holds
  15 → a 500 for a valid request.
- **Negative testing** checks graceful rejection across malformed, missing, boundary, extra and
  hostile inputs.
- Write down **invariants**; check each at the cheapest tier that can see it.
- **Property-based testing** (jqwik) generates inputs, favours edges, and **shrinks** failures to
  minimal reproducible cases.
- **Schemathesis** fuzzes an HTTP API from its OpenAPI spec; run it against the same stack, nightly.
- **Exploratory testing** discovers; automation guards what was discovered.
:::
