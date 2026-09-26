# 2 The four axes of testing

::: {.callout .covers}
This chapter covers

- Why one-dimensional labels like "integration test" cause so many arguments
- The four independent axes: scope, visibility, purpose, and environment/phase
- Placing every test in the lab on all four axes
- Telling apart terms that are commonly confused — including "system test" versus "ephemeral test"
:::

**After this chapter you will be able to** classify any test precisely, explain why two people
arguing about "integration tests" might both be right, and use the axes as a checklist when you
design a new test.

::: {.callout .recall}
Warm-up

1. What are the four things a test suite buys you? (section 1.1)
2. Why does the cost of a bug rise the later it is found? Name one of the three forces. (1.2)
3. Why did the lab's POST-then-GET test miss the scale bug? (1.5)
:::

## 2.1 The problem with a single label

Walk into any engineering team and ask what an "integration test" is. You'll hear:

- "A test that talks to a real database."
- "A test that starts Spring."
- "A test that calls another service."
- "Anything that isn't a unit test."
- "The tests QA runs in the integration environment."

All five are in common use. They are not five opinions about one thing; they are answers to *four
different questions* squeezed into one word. The first three are about **how much of the system**
the test exercises. The fourth is a definition by exclusion. The fifth is about **where** the test
runs.

The same happens with "system test", "end-to-end test", "black-box test", "acceptance test" and
"regression test". People treat them as rungs on one ladder — unit, integration, system,
acceptance — when they actually describe different properties that can be combined almost freely.
A regression test can be a unit test. A black-box test can be tiny. An acceptance test can run on a
laptop.

The fix is to stop using one label and start using four coordinates.

## 2.2 The four axes

![Figure 2.1 Every test has a position on four independent axes. The words on each branch are values on that axis, not rungs on a single ladder.](images/02-four-axes.png)

| Axis | The question it answers | Typical values |
| --- | --- | --- |
| **Scope** | How much of the software is really executing? | unit, integration, system, end to end |
| **Visibility** | How much does the test know about, or reach into, the inside? | white-box, grey-box, black-box |
| **Purpose** | Why does the test exist — what risk does it guard? | functional, regression, contract, performance, acceptance, security |
| **Environment & phase** | Where does it run, and when in the delivery process? | in-process, container, ephemeral environment, staging, production; laptop, PR, nightly, post-deploy |

"Independent" is the key word. Knowing a test's scope tells you nothing certain about its purpose.
Knowing its visibility tells you nothing certain about where it runs. Let's take each axis in turn.

### 2.2.1 Scope: how much is real

Scope is about the amount of *real, production* code and infrastructure a test executes.

![Figure 2.2 Scope as nested boxes. Each level includes more real parts than the one inside it.](images/02-scope-ladder.png)

- **Unit** — one class or a small cluster of classes, with everything else replaced or absent.
  `QuoteCalculatorTest` runs one class. `QuoteServiceTest` runs `QuoteService` plus the real
  `QuoteCalculator`, with the gateway and repository mocked. Both are unit tests.
- **Integration** — a few real parts wired together, usually including one piece of real
  technology: a database, an HTTP stack, a JSON mapper. `QuoteRepositoryTest` runs Spring Data,
  Hibernate, Flyway and a real PostgreSQL. `QuoteControllerTest` runs the Spring MVC stack, Jackson
  and bean validation.
- **System** — one whole deployable unit, with its *external* dependencies substituted. The lab's
  `QuoteApiIntegrationTest` (misleadingly named, as you'll see) boots the entire application; only
  the rate service is replaced by a stub.
- **End to end (E2E)** — several deployed systems together, as a user would experience them: the
  quote service, the real rate service, the API gateway, maybe a web UI.

::: {.callout .myth}
Misconception: "A unit is a class"

A "unit" is a unit of *behaviour* you can test in isolation, not a class. Testing `QuoteService`
with the real `QuoteCalculator` inside it is still a unit test — the calculator is pure, fast and
deterministic, so there's no reason to mock it. Mocking every collaborator of every class produces
tests that mirror the implementation line by line and break on every refactor (chapter 4).
:::

### 2.2.2 Visibility: how much the test knows

Visibility is about the test's relationship with the inside of the system.

![Figure 2.3 Visibility runs from white-box, where the test calls internals, to black-box, where it knows only the public interface.](images/02-visibility.png)

- **White-box** — the test calls internal methods, knows the class structure, constructs objects
  directly. Every unit test is white-box by nature.
- **Grey-box** — the test drives the system through its public interface but *also* peeks inside:
  checks a database row, reads a log, inspects a metric, or — importantly — **shares code with the
  application**, such as its DTO classes.
- **Black-box** — the test knows only the public contract: URLs, JSON, status codes, documented
  behaviour. It shares no code with the implementation. It could be rewritten in another language
  and still work.

Here is the subtle point that section 1.5 foreshadowed. The lab's system tests call the app over
real HTTP — that *looks* black-box. But they deserialise responses into the app's own
`QuoteResponse` record. That's a shared type, which makes them grey-box. And that shared type is
exactly what hides the scale bug. **Visibility is not about the transport; it's about what the test
shares with the thing it tests.**

### 2.2.3 Purpose: why the test exists

Purpose is about the risk a test guards against.

| Purpose | Guards against | Example in the lab |
| --- | --- | --- |
| **Functional** | The system doing the wrong thing | `prices_the_command_with_the_rate_from_the_gateway` |
| **Regression** | Behaviour that used to be right changing silently | `QuotePricingRegressionTest` — frozen prices |
| **Contract** | Two parties disagreeing about an interface | `QuoteWireContractRegressionTest`; `HttpRateGatewayTest` for the consumer side |
| **Performance** | Too slow, or falls over under load | The Gatling smoke test (chapter 18) |
| **Acceptance** | Not doing what the business asked for | Cucumber scenarios reviewed with a product owner |
| **Security** | Unauthorised access, injection, data leaks | (none yet — the service has no auth) |
| **Architecture** | The design eroding over time | `ArchitectureTest` |

Two traps. First, **every test becomes a regression test once it passes** — that's why regression
is sometimes treated as a *use* of tests rather than a kind. The lab uses the word more narrowly: a
test whose expected values were *frozen from observed behaviour*, where a failure means "you changed
something on purpose or by accident — decide which". Second, **purpose doesn't dictate scope**. The
pricing regression test is a unit test; the wire-contract regression test is a system test. Each
lives at the scope where its mechanism belongs.

### 2.2.4 Environment and phase: where and when

The last axis bundles two closely related questions: *where* the test runs, and *when* in the
delivery process.

![Figure 2.4 Delivery phases, from the developer's laptop to production. Each phase trades speed for realism.](images/02-phases.png)

Environments, from most controlled to most realistic:

- **In-process** — everything in the test's own JVM. Unit tests, and Spring slice tests using mocks.
- **Container** — real infrastructure started for the test run on the build machine: PostgreSQL via
  Testcontainers, a WireMock server, eventually the app's own Docker image. Created and destroyed
  by the run.
- **Ephemeral environment** — the application *deployed* into a short-lived, production-like
  environment (say, a Kubernetes namespace per pull request), tested, then torn down.
- **Staging** — a long-lived, shared, production-like environment.
- **Production** — smoke tests after a deploy, synthetic monitoring on a schedule.

Phases are the points in the pipeline where tests run: on a laptop, on every pull request, on the
main branch or nightly, before a release, after a deploy. We'll spend chapters 17 and 19 on this
axis.

## 2.3 Putting it together: the lab on four axes

Let's place every test in the lab. Doing this once, carefully, is worth more than any definition.

| Test | Scope | Visibility | Purpose | Environment / phase |
| --- | --- | --- | --- | --- |
| `QuoteCalculatorTest` | Unit | White | Functional | In-process / every build |
| `QuotePricingRegressionTest` | Unit | White | Regression | In-process / every build |
| `QuoteServiceTest` | Unit | White | Functional | In-process / every build |
| `ArchitectureTest` | Unit (bytecode) | White | Architecture | In-process / every build |
| `QuoteControllerTest` | Integration (web slice) | White/grey | Functional | In-process / every build |
| `QuoteRepositoryTest` | Integration (JPA slice) | White | Functional (mapping) | Container (PostgreSQL) / every build |
| `HttpRateGatewayTest` | Integration (adapter) | Grey | Contract (consumer side) | In-process + WireMock / every build |
| `QuoteApiIntegrationTest` | System | Grey (shared DTOs) | Functional | In-process app + containers / every build |
| `QuoteWireContractRegressionTest` | System | Grey→black (raw JSON string) | Contract + regression | In-process app + containers / every build |
| *Cucumber suite (Part 3)* | System | **Black** | Functional + contract | Containers incl. app image / every PR |
| *Gatling smoke (ch. 18)* | System | Black | Performance | Containers / nightly |

Figure 2.5 plots two of the axes, scope and visibility, for the same tests.

![Figure 2.5 The lab's tests on the scope × visibility plane. The empty top-right corner — broad and truly black-box — is what Part 3 fills.](images/02-quadrant.png)

Look at the empty region in the top right of figure 2.5. The lab has plenty of narrow, white-box
tests and a couple of broad grey-box ones, but nothing broad *and* truly black-box. That gap is
exactly where the scale bug lives.

::: {.callout .mental}
Mental model: the test's address

Give every test a four-part address, like a postal address: *scope / visibility / purpose / where
& when*. "Unit / white / regression / every build." "System / black / contract / every PR." Once
you can write the address, you can argue about whether it's the *right* address — which is the
conversation that actually matters.
:::

## 2.4 Terms people confuse, untangled

With the axes in hand, most confusing pairs of terms dissolve. Each row names the axis where the
difference actually lies.

| Often confused | The real difference | Axis |
| --- | --- | --- |
| Integration test vs system test | How much is real: a few parts, or the whole deployable | Scope |
| System test vs end-to-end test | One deployable with stubbed neighbours, or several real systems | Scope |
| System test vs black-box test | Size vs knowledge. A system test can be grey-box (the lab's is); a black-box test can be small | Scope vs visibility |
| System test vs ephemeral test | *What* is tested vs *where* it runs. A system test can run in an ephemeral environment, or not | Scope vs environment |
| Regression test vs unit test | Why it exists vs how big it is. They combine freely | Purpose vs scope |
| Acceptance test vs end-to-end test | Who defines "correct" (the business) vs how much runs. Acceptance tests can be small | Purpose vs scope |
| Smoke test vs sanity test | Both are quick, shallow "is it alive?" checks. Usage varies by team; treat them as synonyms unless your team defines them | Purpose |
| Contract test vs integration test | Contract tests check an *agreement* between parties, and can run without the other party | Purpose vs scope |
| Component test vs system test | Often synonyms. Some teams use "component" for one service in isolation and "system" for several | Scope (team-dependent) |

::: {.callout .myth}
Misconception: "Ephemeral testing is a kind of test"

"Ephemeral" describes an *environment's lifetime*: created for a run, destroyed afterwards. The
lab's PostgreSQL container is ephemeral; so is a per-PR Kubernetes namespace. You can run unit
tests, system tests or performance tests in an ephemeral environment. Asking "should we do system
tests or ephemeral tests?" is like asking "should we eat lunch or eat indoors?"
:::

## 2.5 Using the axes to design a test

The axes aren't just for classifying existing tests; they're a design checklist. When you're about
to write a test, answer the four questions in this order:

1. **Purpose first.** What risk am I guarding? ("A client might see different numbers from POST and
   GET.")
2. **Visibility second.** What must the test *not* share with the implementation for it to see that
   risk? (It must not use the app's DTOs — it has to look at raw JSON.)
3. **Scope third.** What's the *smallest* scope where the risk is visible? (The scale comes from the
   database round trip, so I need at least the web layer *and* real persistence. That's the whole
   app.)
4. **Environment last.** Where's the cheapest place that scope can run reliably? (Containers on the
   build machine — no need for a deployed environment.)

Answer: a system-scope, black-box, contract-purpose test running against containers on every PR.
That's precisely the Cucumber suite of Part 3 — and we derived it from the risk, not from a
fashion.

::: {.callout .tryit}
Try it: address three tests

Pick three tests from your own codebase (or from the lab) and write each one's four-part address.
Then, for each, ask: *is there a cheaper address that would guard the same risk?* A system test
whose risk is pure arithmetic should be a unit test. A unit test full of mocks whose risk is "the
SQL is wrong" can't see that risk at all.
:::

::: {.callout .quiz}
Check your understanding

1. Name the four axes, and give the question each one answers.
2. `QuoteServiceTest` uses a real `QuoteCalculator` and a mocked `QuoteRepository`. What's its scope?
   Why isn't it an integration test?
3. The lab's system tests call the app over real HTTP. Why are they grey-box rather than black-box?
4. A teammate proposes "moving our system tests to ephemeral tests". Rephrase the proposal using the
   axes, and say what question you'd ask back.
5. Can a regression test also be a black-box test? Give an example from the lab or a hypothetical.
6. Using the design order in section 2.5, derive the address of a test for the risk "the Flyway
   migration uses a PostgreSQL feature that breaks on the production database version".
:::

::: {.callout .summary}
Summary

- One-word labels like "integration test" conflate different questions. Use four axes instead:
  **scope**, **visibility**, **purpose**, **environment & phase**.
- The axes are **independent**. Regression tests can be unit tests; black-box tests can be small;
  system tests can run in or out of ephemeral environments.
- **Visibility is about what a test shares with the implementation**, not about the transport. A
  test over real HTTP that uses the app's DTOs is grey-box.
- The lab has no broad, truly black-box tests — the empty corner where its scale bug hides.
- Design a test from its **purpose**, then visibility, then smallest scope, then cheapest
  environment.
:::
