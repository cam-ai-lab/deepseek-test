# 1 Why we test, and what tests actually buy you

::: {.callout .covers}
This chapter covers

- The four things a test suite buys you, and the one thing it can never buy
- Why *where* a bug is found matters more than *whether* it is found
- Feedback loops, and why speed is a feature of a test suite
- A guided tour of the lab: the quote service and its existing tests
:::

**After this chapter you will be able to** name what a given test is protecting, explain the cost
curve of defects to a sceptical colleague, and find your way around the lab.

## 1.1 Four things tests buy you

Ask ten engineers why they write tests and you'll hear "to find bugs" from nine of them. That's
true, but it's the least interesting answer. A good test suite buys you four distinct things, and
confusing them is the root of most bad testing decisions.

**1. Confidence to change.** This is the big one. Software that nobody dares to change is dead
software. A suite that runs in minutes and reliably goes red when behaviour breaks is what lets a
team refactor on a Tuesday afternoon without a change-approval meeting. Notice that this benefit
comes almost entirely from tests that already pass — their value is in *staying* green while you
change things underneath them.

**2. Fast feedback.** A test tells you, in seconds, something that would otherwise take you minutes
of clicking or hours of waiting for a bug report. The speed matters as much as the answer; we'll
come back to this in section 1.3.

**3. Design pressure.** Code that is hard to test is usually hard to use. When you struggle to
write a test because a class creates its own HTTP client, reads the system clock and writes to a
database in the same method, the test isn't the problem — it's the messenger. In the lab you'll see
the result of listening to that pressure: the quote service depends on a `RateGateway` interface
and an injected `Clock`, precisely so that tests can control both.

**4. Executable documentation.** A test named `rounds_half_up_so_a_half_cent_goes_to_the_customer`
tells the next developer a business rule in plain words, *and it can't go out of date* — the day
the rule changes, the test fails. Few other forms of documentation can make that promise.

And the one thing tests can never buy you: **proof of correctness**. Tests sample behaviour. They
show that the program works for the inputs you thought of. Edsger Dijkstra put it bluntly: testing
can show the presence of bugs, never their absence. We'll meet a vivid example in chapter 16, where
the lab accepts an input that passes validation and then crashes — an input none of its 40-odd
tests ever tried.

::: {.callout .mental}
Mental model: every test is an answered question

A test is a question you have written down so that a machine can ask it again, forever.
*"If the rate service is down, do we answer 503?"* When you look at any test, ask: **what
question is this answering, and would I care if the answer changed?** If you can't say, the test
is probably noise.
:::

## 1.2 Where a bug is found matters more than whether it is found

Every bug is eventually found. The question is by whom, and when. A rounding error spotted while
you're typing costs you ten seconds. The same error spotted by a customer, after it has mispriced
four thousand loans, costs an incident, a data fix, an apology and possibly a regulator.

![Figure 1.1 The relative cost of fixing a defect grows steeply the later it is found. The exact numbers vary by study and organisation; the shape does not.](images/01-defect-cost.png)

The exact ratios in figure 1.1 are illustrative — published studies disagree wildly on the
numbers — but no one disputes the shape. Three forces drive it:

- **Distance.** The later a bug is found, the further the person fixing it is from the moment the
  code was written. Context has to be rebuilt.
- **Blast radius.** A bug in production has already affected users and data. You fix the code *and*
  the consequences.
- **Coordination.** A bug found in a unit test involves one person. A bug found in staging involves
  a release, a rollback, maybe several teams.

This is the economic argument behind everything in this book. We will build tests at many levels
— unit, integration, black-box, performance — and the reason is not thoroughness for its own sake.
**Each level exists to catch a class of bug as early and as cheaply as that class can possibly be
caught.** Rounding bugs can be caught in a millisecond unit test. A wrong SQL column type can't —
you need a real database. A mismatch between what the service sends over the wire and what a
client expects can't even be caught with a real database — you need to look at the actual HTTP
traffic. Part of becoming a testing expert is knowing, for each kind of risk, the earliest place
it can be caught.

## 1.3 Feedback loops

![Figure 1.2 The inner development loop. Everything in a test suite's design is about making this loop fast and trustworthy.](images/01-feedback-loop.png)

Figure 1.2 is the loop you run hundreds of times a day. Two properties of your tests determine how
well it works:

- **Speed.** If the loop takes 5 seconds you'll run it after every small change. If it takes 5
  minutes you'll batch changes, and when something breaks you'll have to work out *which* change
  did it. If it takes 50 minutes you'll stop running it locally and let CI tell you — so now your
  loop is an hour long.
- **Trust.** If a red test reliably means "you broke something", you stop and look. If red
  sometimes means "the CI runner was slow today", you learn to click *re-run*. Once a team learns
  that reflex, the suite is dying: real failures get re-run too.

A slow but trustworthy suite gets run less often. A fast but flaky suite gets ignored. You need
both, and a large part of this book — the tier structure in chapter 11, the context budget in
chapter 8, the flaky-test policy in chapter 19 — is machinery for protecting speed and trust as a
suite grows from 30 tests to 3,000.

::: {.callout .myth}
Misconception: "More tests is always better"

Every test has a cost: time to run, time to maintain, and the risk of being flaky. A test that
checks nothing important, or duplicates another, or breaks every time someone renames a private
method, has *negative* value. The goal is not the most tests; it is the most confidence per minute
of feedback and per hour of maintenance.
:::

## 1.4 A tour of the lab

Time to meet the service we'll be testing. Figure 1.3 shows its shape.

![Figure 1.3 The quote service. A controller receives commands, the service orchestrates a rate lookup, a pure calculation and a save.](images/01-quotes-architecture.png)

The whole happy path is in one method, shown in listing 1.1.

```java
// Listing 1.1 QuoteService.createQuote — the use case (src/main/.../quote/QuoteService.java)
QuoteResponse createQuote(QuoteRequest request) {
    Rate rate = this.rateGateway.rateFor(request.productCode(), request.currency());   // #1
    BigDecimal total = this.calculator.totalFor(request.amount(), rate.annualPercentage(),
            request.termMonths());                                                      // #2
    Quote quote = Quote.create(UUID.randomUUID(), request.customerId(), request.productCode(),
            request.amount(), request.currency(), request.termMonths(), rate.annualPercentage(),
            total, this.clock.instant());                                               // #3
    return QuoteResponse.from(this.repository.save(quote));                             // #4
}
```

::: {.annotations}
1. Ask the rate service (through an interface) for today's rate.
2. Pure arithmetic: simple interest, rounded half-up to cents.
3. Build the entity. Note `this.clock.instant()` rather than `Instant.now()` — the clock is
   injected so tests can freeze time.
4. Persist and convert to the response record.
:::

Four collaborators, four steps. Everything that can go wrong in this service goes wrong in one of
those four lines — or in the HTTP layer around them. Keep that in mind; when we ask "which test
catches this bug?" the answer will always point at one of those seams.

The main packages are:

| Package | What lives there |
| --- | --- |
| `quote` | The use case: controller, service, calculator, entity, repository, request/response records |
| `rate` | The outbound port `RateGateway`, its HTTP adapter, and the `Rate` value |
| `web` | `ApiExceptionHandler`: turns exceptions into RFC 9457 problem responses |
| `config` | The `Clock` bean |

And the existing tests, which we'll study in detail in Part 2:

| Tier | Test class | What it checks |
| --- | --- | --- |
| Unit | `QuoteCalculatorTest` | Arithmetic and rounding, exhaustively |
| Unit | `QuotePricingRegressionTest` | Frozen "golden" prices |
| Unit | `QuoteServiceTest` | Orchestration, with mocks |
| Unit | `ArchitectureTest` | Package dependency rules |
| Integration | `QuoteControllerTest` | JSON binding, validation, error mapping (`@WebMvcTest`) |
| Integration | `QuoteRepositoryTest` | Entity ↔ table mapping on real PostgreSQL (`@DataJpaTest`) |
| Integration | `HttpRateGatewayTest` | The HTTP adapter against a WireMock server |
| System | `QuoteApiIntegrationTest` | Real HTTP into the whole running app |
| System | `QuoteWireContractRegressionTest` | The JSON response against a golden file |

::: {.callout .tryit}
Try it: find your way around

Clone the lab and, without running anything, answer these by reading code: (1) Which class turns a
`RateUnavailableException` into an HTTP 503? (2) What is the maximum `termMonths` a client may
send? (3) Where is the database schema defined — in Java or in SQL? *Answers: `ApiExceptionHandler`;
600 (`@Max(600)` on `QuoteRequest`); in SQL, `V1__create_quotes.sql`, applied by Flyway.*
:::

## 1.5 A bug the suite can't see

Here is a small spoiler that will motivate half the book. Send the service this command:

```json
{"customerId":"c-1","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}
```

With the rate service answering 4.25%, the `POST` returns `"amount":10000.00` and
`"annualRatePercent":4.25`. Now `GET` the same quote by its id. You get `"amount":10000.0000` and
`"annualRatePercent":4.2500`. Same quote, two different JSON documents. A client that caches the
first response and compares it with the second sees a change that never happened; a client that
formats numbers from the raw JSON shows different values on different screens.

The suite has an in-JVM "system test" that does *exactly* this — POST, then GET — and it passes.
Why? Because it deserialises both responses into the application's own `QuoteResponse` Java type
and compares numbers with `isEqualByComparingTo`, which deliberately ignores scale. The test is
looking at the system through the system's own glasses.

Hold on to that sentence. By the end of Part 3 you'll understand exactly what kind of test can see
this bug, why, and how to build it.

## 1.6 What "good" looks like for this book

By the end of the book you should be able to look at any test — in this lab or at work — and say:

- which of the **four axes** it sits on (scope, visibility, purpose, environment/phase);
- what **risk** it protects against, and whether a cheaper test could protect against the same risk;
- what would make it **flaky**, and how to prevent that;
- whether it belongs in the **merge-blocking** path of CI, a nightly job, or nowhere.

That last list is, in effect, the syllabus. Chapter 2 starts with the axes.

::: {.callout .quiz}
Check your understanding

1. Name the four things a test suite buys you. Which one comes almost entirely from tests that
   *pass*?
2. Why does the quote service inject a `Clock` instead of calling `Instant.now()`?
3. A colleague says, "our suite has 100% line coverage, so we know the code is correct." What's
   wrong with that statement?
4. Why can't a unit test of `QuoteCalculator` catch the POST/GET mismatch in section 1.5?
5. Give one reason a slow test suite leads to *later* bug discovery, not just slower builds.
:::

::: {.callout .summary}
Summary

- Tests buy **confidence to change**, **fast feedback**, **design pressure** and **executable
  documentation**. They never buy proof of correctness.
- The cost of a defect rises steeply the later it is found. Each kind of test exists to catch a
  class of bug at the earliest, cheapest point possible.
- A suite's **speed** and **trustworthiness** determine whether people actually use it.
- The lab is a quote service with a rate-service dependency, PostgreSQL, and a three-tier test
  suite — which still misses a real bug because its top tier sees the app through the app's own
  types.
:::
