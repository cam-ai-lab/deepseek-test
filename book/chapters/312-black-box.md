# 12 What "black-box" really means

::: {.callout .covers}
This chapter covers

- The definition of a black-box test, and the common ways tests fall short of it
- Shared-type blindness: how a test inherits the system's assumptions
- A full case study of the POST/GET mismatch the lab's suite cannot see
- What a black-box test gives up, and why that's worth it at the top of the suite
- The design of the lab's new black-box tier, at a glance
:::

**After this chapter you will be able to** judge whether a given "end-to-end" test is really
black-box, explain shared-type blindness with a concrete example, and argue for (and against) a
black-box tier.

::: {.callout .recall}
Warm-up

1. On the visibility axis, what separates grey-box from black-box? (section 2.2.2)
2. Why is the golden-file test more sensitive to number scale than the typed system test? (6.3)
3. What does the `NUMERIC(19, 4)` column type do to a value's scale on the way back from the
   database? (9.2)
:::

## 12.1 A definition with teeth

The textbook definition: a **black-box test** exercises a system only through its public interface,
with no knowledge of its internals. That's accurate but too easy to satisfy on paper. Here's a
working definition you can check:

> A test is black-box if it could be **deleted, rewritten in another language, and pointed at a
> reimplementation of the system in another language**, and still be a valid test.

Apply that to the lab's `QuoteApiIntegrationTest`:

- It starts the application with `@SpringBootTest` — *in the test's own JVM*. It needs the
  application's classes on its classpath. A Go reimplementation couldn't be started this way.
- It receives responses as `QuoteResponse` — the application's own record type.
- It configures the app through Spring properties (`@DynamicPropertySource`), not through the
  environment variables a deployment would use.

It talks real HTTP — which feels black-box — but by the working definition it's firmly grey-box. And
as chapter 2 put it: visibility is about what a test *shares* with the system, not about the
transport.

::: {.callout .mental}
Mental model: the reimplementation test

Ask: "If the team rewrote this service from scratch in another language, would this test still run
unchanged?" If yes, it's black-box. If it would need the old code, the old types or the old
framework, it's grey- or white-box — however much HTTP it speaks.
:::

## 12.2 Shared-type blindness

Why does sharing types matter so much? Because a type is not just a container for data — it's a
set of *assumptions*. When the test uses the system's types, it inherits the system's assumptions,
and any bug that lives inside those assumptions becomes invisible.

![Figure 12.1 Shared-type blindness. Both sides use QuoteResponse, and the comparison ignores scale, so different JSON on the wire looks identical to the test.](images/12-shared-types.png)

Follow the data in the lab's system test:

1. The app serialises a `QuoteResponse` into JSON: `"total":10500.00`.
2. `TestRestTemplate` deserialises that JSON back into a `QuoteResponse`. The `BigDecimal` field now
   holds `10500.00`.
3. The test asserts `isEqualByComparingTo("10500.00")`.

Now imagine the JSON had said `"total":10500.0000` or even `"total":1.05E+4`. Step 2 would produce
a `BigDecimal` of the same *numeric value*, and step 3 compares only numeric value. The test would
pass for all three JSON documents — but a JavaScript client displaying the raw number, a client
comparing documents for changes, or a strict schema validator would each see three different
responses.

Three separate things conspire here, and each is a lesson:

- **The shared type** normalises the wire representation away before the test looks.
- **The comparison semantics** (`isEqualByComparingTo`) ignore the one property — scale — that
  differs.
- **The test's author and the system's author share a mental model**: "a number is a number".
  Black-box testing works partly because it forces you to state the contract from the *client's*
  point of view.

## 12.3 Case study: the POST/GET mismatch

Let's trace the bug end to end, because understanding *why* it happens is what lets you find the
next one.

![Figure 12.2 One quote, two JSON documents. The POST echoes in-memory values; the GET reads back what PostgreSQL stored.](images/12-post-get.png)

**Step 1: POST.** `QuoteService.createQuote` builds a `Quote` entity from the request's `BigDecimal`
(scale 2, because the client sent `10000.00`) and the rate service's value (scale 2: `4.25`). It
calls `repository.save(quote)` and converts the *returned entity* to the response. The returned
entity still holds the in-memory values — Hibernate doesn't re-read the row after an insert — so the
response says `"amount":10000.00,"annualRatePercent":4.25`.

**Step 2: the database.** PostgreSQL stores `amount` in a `NUMERIC(19,4)` column, so the stored
value is `10000.0000`. The same for `annual_rate_percent NUMERIC(9,4)`: `4.2500`.

**Step 3: GET.** `findQuote` loads the entity from the database. Hibernate maps `NUMERIC(19,4)` to a
`BigDecimal` with scale 4. The response says `"amount":10000.0000,"annualRatePercent":4.2500`.

A third difference hides in `createdAt`. The service takes `clock.instant()`, which on a modern JVM
can carry nanoseconds. PostgreSQL's `TIMESTAMP(6)` keeps microseconds. So the POST can return
`…T10:30:00.123456789Z` and the GET `…T10:30:00.123457Z`.

Which tests *could* have seen this?

| Test | Sees POST JSON? | Sees GET JSON? | Scale-sensitive? | Catches it? |
| --- | --- | --- | --- | --- |
| `QuoteServiceTest` | No (Java objects) | No | — | No |
| `QuoteControllerTest` | Yes, but service is mocked | No | Partly (`jsonPath`) | No — the DB isn't involved |
| `QuoteRepositoryTest` | No | No | Uses `isEqualByComparingTo` | No |
| `QuoteApiIntegrationTest` | Via `QuoteResponse` | Via `QuoteResponse` | No | **No** — shared-type blindness |
| `QuoteWireContractRegressionTest` | **Yes, raw** | No | **Yes** | No — looks only at POST |
| Black-box: POST then GET, raw JSON | **Yes** | **Yes** | **Yes** | **Yes** |

The bug sits at an intersection: it needs the web layer *and* real persistence (scope: system), and
it needs raw, scale-sensitive comparison of two operations (visibility: black-box; purpose:
contract). No test in the lab occupies that intersection. This is figure 2.5's empty corner, made
concrete.

::: {.callout .myth}
Misconception: "The higher-level test must catch everything the lower ones miss"

Scope and visibility are independent. The lab's highest-scope test is *less* sensitive to this bug
than a lower-scope one (the golden file), because of what it shares with the system. Broad tests
are not automatically strict tests.
:::

## 12.4 What black-box testing costs

Nothing is free. Going fully black-box gives up real capabilities:

| You give up | Because | Mitigation |
| --- | --- | --- |
| Speed | Building an image and starting containers takes 30–60 s | Keep the black-box suite small; run it as a separate CI job |
| Precise failure location | A failure says *what* broke, not *which class* | Rely on the lower tiers for localisation |
| Easy setup of internal state | You can't call a repository to seed data | Seed through the API; allow read-only SQL for assertions only |
| In-process coverage measurement | JaCoCo in the test JVM can't see the app's container | Cover logic in lower tiers; optionally attach an agent to the container |
| Mocking internals | Nothing inside can be replaced | Control the *environment*: stub servers, config, data |

These costs are why the black-box tier is the **top** of the suite, not the bulk of it. You want a
few dozen scenarios that pin the product's contract, sitting on top of hundreds of fast tests that
pin its logic.

## 12.5 The plan at a glance

The lab's root contains `BLACKBOX-TEST-PLAN.md`, which designs a new tier to fill the empty corner.
Its key decisions, and the chapter where each is explored:

| Decision | Choice | Chapter |
| --- | --- | --- |
| What runs | The **real Docker image**, configured only by environment variables | 14 |
| Where | A local `docker compose` stack: app, PostgreSQL, WireMock | 14 |
| Who starts it | **Testcontainers** `ComposeContainer`, from a Cucumber hook | 14 |
| Language | Java + **Cucumber**, with `cucumber-spring` for dependency injection in steps | 13 |
| Rate service | A **WireMock container**, programmed by steps through its admin API | 14, 15 |
| Database | **Read-only SQL for assertions**, via a SELECT-only role | 14 |
| Shared code with the app | **None** — enforced by a build guard | 15 |
| Old system tier | **Removed**, after closing its coverage gaps | 11, 15 |

Notice the phrase "an extension of the Spring ecosystem". The team knows Spring's dependency
injection, so the *test suite itself* uses a small Spring context for its own plumbing — HTTP
client, stub client, a read-only `JdbcTemplate`. What it never does is load the *application's*
context. Using a familiar framework for test infrastructure is fine; sharing the system under test's
code is what breaks black-box.

::: {.callout .tryit}
Try it: see the mismatch by hand

With Docker running: start the dev database (`docker compose up -d`), start a WireMock container on
port 8081 (`docker run -d -p 8081:8080 wiremock/wiremock`), stub a rate with `curl -X POST
localhost:8081/__admin/mappings -d '{"request":{"urlPath":"/rates/WIDGET"},"response":{"status":200,
"jsonBody":{"annualPercentage":4.25}}}'`, run `./gradlew bootRun`, then POST a quote with `curl`
and GET it. Compare the two bodies character by character. *Needs a JDK and Docker.*
:::

::: {.callout .quiz}
Check your understanding

1. State the "reimplementation test" for black-box-ness. Apply it to `HttpRateGatewayTest`.
2. Name the three factors that make the lab's system test blind to the scale bug.
3. Why does the POST response carry the request's scale while the GET carries scale 4?
4. Which two properties must a test have to catch the POST/GET mismatch? Map them to axes.
5. The black-box suite uses Spring for dependency injection. Why doesn't that make it grey-box?
:::

::: {.callout .summary}
Summary

- A test is black-box if it would survive a **reimplementation of the system in another language**.
  Real HTTP alone doesn't make a test black-box.
- **Shared-type blindness**: using the system's types imports its assumptions and normalises away
  wire-level differences.
- The lab's POST/GET mismatch needs **system scope** and **black-box, scale-sensitive** comparison
  of two operations — a combination no existing test has.
- Black-box tests cost speed, localisation and easy setup. Keep them few, at the top of the suite.
- Using Spring for the *test's own* plumbing is fine; loading the *application's* code is not.
:::
