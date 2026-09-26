# 20 Choosing a test strategy

::: {.callout .covers}
This chapter covers

- The pyramid, the trophy and the honeycomb: what each shape is really saying
- Risk-based test selection: starting from what can go wrong
- A decision tree for "which test should I write?"
- The quote service's complete test strategy, as a one-page document
- How strategy evolves as a system and a team grow
:::

**After this chapter you will be able to** write a test strategy for a service, defend each of its
choices in terms of risk and cost, and use the book's vocabulary to lead a testing discussion.

::: {.callout .recall}
Warm-up — the whole book

1. Name the four axes, and place the new Cucumber suite on each. (chapter 2)
2. Name the three levels of test double. (4.4)
3. What drives the run time of a Spring test suite? (8.4)
4. What makes a test black-box? (12.1)
5. Which checks should block a merge? (19.2)
:::

## 20.1 Three shapes

You'll hear test strategies described by shapes. Each is a claim about *where most tests should
live*.

![Figure 20.1a The pyramid: most tests at unit level.](images/20-shape-pyramid.png)

![Figure 20.1b The trophy: most effort in integration tests, on a base of static analysis.](images/20-shape-trophy.png)

![Figure 20.1c The honeycomb: most tests exercise one service against its real infrastructure.](images/20-shape-honeycomb.png)

The percentages are illustrative, not prescriptions. Each shape is a heuristic about proportions, and each was right for the systems its authors built.

**The pyramid** (Mike Cohn, popularised by Martin Fowler): many unit tests, fewer integration tests,
very few end-to-end tests. Its logic is economic: lower tests are faster, cheaper and more precise,
so do as much as possible down there. It was a reaction to suites made mostly of slow, brittle UI
tests — the "ice-cream cone".

**The trophy** (Kent C. Dodds, for front-end applications): static analysis at the base, a thin layer
of unit tests, **integration tests as the biggest part**, a few end-to-end tests at the top. Its
logic: in UI code, the value is in how components work together, and unit tests of individual
components often test implementation details.

**The honeycomb** (Spotify, for microservices): few "implementation detail" tests, **mostly
integration tests** of each service against its real infrastructure, few "integrated" tests across
services. Its logic: a microservice often has little internal logic; its risk is in its interactions
with databases, queues and other services.

These shapes don't contradict each other as much as they seem to. They reflect **where the risk lives**
in different kinds of system:

| System | Where the risk lives | Shape that fits |
| --- | --- | --- |
| Logic-heavy domain (pricing, rules engines) | In the logic | Pyramid |
| UI application | In components working together | Trophy |
| Thin CRUD microservice | In the infrastructure and interactions | Honeycomb |

::: {.callout .myth}
Misconception: "There's one correct shape"

A shape is a summary of a strategy, not a strategy. Start from your system's risks; the shape is
whatever falls out. The quote service, as you'll see, is a mild pyramid with a honeycomb's respect for
real infrastructure — because it has one genuinely logic-heavy class and several infrastructure
boundaries.
:::

## 20.2 Start from risk

A strategy is a set of answers to one question: **what could go wrong, and what's the cheapest,
earliest test that would notice?** For the quote service:

| Risk | Consequence | Cheapest test that sees it | Axis address |
| --- | --- | --- | --- |
| Wrong arithmetic or rounding | Mispriced loans | Unit (calculator table, properties) | Unit / white / functional + regression / in-process |
| Prices change silently | Commercial surprise | Unit (frozen anchors) | Unit / white / regression / in-process |
| Invalid input accepted | Garbage stored | Unit (`Validator`) + web slice once | Unit / white / functional |
| JSON binding or error mapping wrong | Clients break | Web slice | Integration / white-grey / functional |
| Entity and migration disagree | Start-up failure / data loss | JPA slice on PostgreSQL | Integration / white / functional / container |
| Adapter misreads the rate API | 503s or wrong prices | Adapter test vs WireMock | Integration / grey / contract / in-process |
| Rate team changes their API | Production outage | Consumer-driven contract (Pact) | Integration / grey / contract / each team's CI |
| Assembled product's wire contract drifts | Clients break | Black-box scenarios | System / black / contract / containers |
| Shipped configuration wrong (timeouts) | Outage under failure | Black-box scenarios | System / black / functional / containers |
| Valid input crashes the system | 500s | System-scope properties / fuzzing | System / black / robustness / nightly |
| Too slow or falls over under load | Outage | Gatling smoke (pathologies) | System / black / performance / nightly |
| Design erodes | Slower delivery | ArchUnit | Unit / white / architecture |
| Deploy broken | Outage | Production smoke | System / black / functional / post-deploy |

Read the "cheapest test" column top to bottom and you'll see the shape emerge: many cheap unit tests
for the logic, focused slices for each technology, one boundary test per external dependency, a
modest black-box suite for the assembled product, and a handful of nightly and post-deploy checks.

## 20.3 Which test should I write?

The same reasoning as a decision tree, for day-to-day use:

![Figure 20.2 Which test should I write? Start from the risk; stop at the first "yes".](images/20-which-test.png)

The tree has a deliberate property: it asks about **risk**, never about **coverage** or about **which
class you just changed**. "I changed `QuoteController`, so I'll write a controller test" is a
habit; "I'm worried that a missing field gives a confusing error, which is about JSON binding, so a
web-slice test" is a strategy.

::: {.callout .mental}
Mental model: every test has a job description

Before writing a test, write its one-line job description: *"Catches: \<risk\>. Cheapest because:
\<reason\>."* If you can't, don't write it yet. If another test already has that job, you don't need
this one.
:::

## 20.4 The quote service's test strategy

Here's the complete strategy for the lab, as a team would actually write it down — short enough to
read in five minutes, precise enough to settle arguments.

---

**Quote service — test strategy** *(v1, after the black-box migration)*

**Principles.**
Test each risk at the cheapest tier that can see it. Rules that matter are enforced by the build. A
red merge gate always means "this change broke something."

**Tiers.**

| Tier | Location | May use | Must not use | Runs |
| --- | --- | --- | --- | --- |
| Unit | `src/test` | JUnit, AssertJ, Mockito, ArchUnit, jqwik, fixtures | Spring test support, containers, network | Every PR (blocking) |
| Integration | `src/integrationTest` | Spring slices, Testcontainers, WireMock | `@SpringBootTest` full context | Every PR (blocking) |
| Black-box | `blackbox/` | Cucumber, REST-assured, WireMock client, read-only SQL | Any application code | Every PR (blocking) |
| Performance smoke | `blackbox/src/gatling` | Gatling | — | Nightly (non-blocking) |
| Production smoke | `blackbox/` `@smoke` | Read-only scenarios | Writes to real customers | After deploy |

**Guards.** Classpath guard (unit tier), context budget ≤ 6 per JVM, black-box isolation guard,
`failOnNoDiscoveredTests`, line coverage ≥ 85% as a floor (not a target).

**Conventions.** Behaviour-describing names. One Act per test. Money built from strings; scale is
asserted where it's part of the contract. Shared test data through `testFixtures`. Unique data per
black-box scenario; no database cleaning.

**Tags.** `@wip` and `@known-bug` excluded by default. Known bugs are written as correct behaviour
with a ticket reference.

**Flaky tests.** Quarantined within one working day, owned, fixed or deleted within two weeks.
Retries are reported.

**Known gaps (accepted, with reasons).** No consumer-driven contract with the rate service yet
(needs the rate team; revisit when they adopt Pact). No ephemeral environments (deployment is simple;
revisit if routing grows). No authentication, so no security tests beyond input validation.

**Open bugs found by the suite.** POST/GET representation mismatch; overflow 500 for large valid
amounts; unknown product reported as 503. All specified as `@known-bug` scenarios.

---

Notice what this document is *not*: it's not a list of test cases, and it doesn't mention coverage
targets per class. It states **principles, tiers with boundaries, guards, conventions, and accepted
gaps**. The accepted gaps are the most important section for a reader outside the team: a strategy
that claims no gaps is either naïve or dishonest.

## 20.5 Strategy evolves

A test strategy is a living document. Signals that it needs revisiting:

- **The suite is slow.** Measure contexts, containers and image builds before adding hardware
  (chapters 8, 9, 14, 19).
- **Bugs escape to production.** For each escaped bug, ask: *which tier should have caught it, and why
  didn't it?* That question, asked consistently, improves a strategy more than any framework change.
- **Tests break on refactoring.** Too much interaction verification or white-box coupling (chapter 4).
- **Quarantine grows.** Shared state or timing assumptions in the design (chapter 19).
- **A new kind of risk appears.** A new dependency, authentication, a message queue, a UI: each needs
  its own row in the risk table of section 20.2.

## 20.6 Where to go from here

You now have a vocabulary — the four axes — that lets you place any test precisely, and a lab in which
you've seen each kind of test built for a reason. The best way to keep the knowledge is to use it:

1. Read the `blackbox/` module in the lab alongside Part 3, then run it: `./gradlew :blackbox:test`.
2. Fix the three known bugs, one at a time, and watch each `@known-bug` scenario turn green.
3. Write your own team's strategy on one page, in the format of section 20.4.
4. Next time someone says "integration test", ask which axis they mean.

::: {.callout .quiz}
Check your understanding — capstone

1. Explain why the pyramid and the honeycomb both make sense, using the idea of where risk lives.
2. For each of these risks, give the cheapest test and its four-axis address: (a) a new Flyway
   migration adds a `NOT NULL` column without a default; (b) the rate team changes a field name;
   (c) the read timeout in `application.yml` is set to 60 s by mistake; (d) a refactor changes
   `HALF_UP` to `HALF_EVEN`.
3. A colleague proposes deleting all unit tests because "the black-box suite covers everything".
   Give three concrete arguments against, from this book.
4. Why does the strategy document list accepted gaps? What happens if it doesn't?
5. A bug escaped to production: a GET for a quote created before a data migration returns 500.
   Walk through the "which tier should have caught it" question.
:::

::: {.callout .summary}
Summary

- **Pyramid, trophy and honeycomb** are heuristics about proportions; each fits systems where risk
  lives in a particular place.
- A strategy starts from **risks**, and assigns each to the **cheapest, earliest** test that sees it.
- Use a **decision tree** based on risk, not on coverage or on which file changed.
- A good strategy document is short: **principles, tiers and boundaries, guards, conventions, tags,
  flaky-test policy, accepted gaps**.
- Revisit it when the suite slows down, bugs escape, tests break on refactoring, or new risks appear.
:::
