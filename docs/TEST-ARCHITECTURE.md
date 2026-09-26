# How the tests are organised, and why

This document explains the test setup in this repository. It assumes you can write Java and have
written a test or two, but it does not assume you know anything about Spring's test machinery. Every
term is explained the first time it is used.

> **Update: the system tier has been replaced.** Sections 1–11 were written when the top tier was an
> in-JVM "system" tier (`src/systemTest`, `@FullStackTest`). It has since been replaced by a Cucumber
> black-box suite that runs the real Docker image — see **section 12**. The earlier sections are kept
> because the reasoning (context cost, tiers as folders, guards) still applies to the unit and
> integration tiers; statements about the system tier describe the repository at tag
> `before-blackbox`.

---

## 1. The one-sentence version

Because a Spring application is slow to start up, tests reuse started-up copies of it; this
repository is arranged so the number of copies stays small no matter how many tests you add, and so
that the rules holding it together are enforced by the build instead of by good intentions.

If that sentence didn't land, read on. It will.

---

## 2. What actually costs time in a test suite

Imagine a test that needs the real application: the real database code, the real web layer, the real
configuration. Before that test can run, Spring has to build the application inside the test process.
That means:

- creating every object the application needs,
- working out which object depends on which and wiring them together,
- reading configuration values,
- opening a connection to the database.

Spring calls the finished result an **`ApplicationContext`**. In plain language: it is a fully
assembled copy of your application, sitting inside the test process, ready to be used.

We will call the act of building one **"starting up the app"**, and one startup costs roughly 1 to 2
seconds.

Now here is the measurement that drives everything else in this document. In the small version of
this repository, before the reorganisation described below:

- **one** test class that started the app took **74%** of the entire suite's running time,
- the **twenty-one** tests that needed nothing but plain Java took about **3%** of it.

So the cost of a test suite is not really about *how many tests* you have. It is about *how many
times you start the app up*. Everything that follows is about keeping that second number small.

---

## 3. Spring keeps a spare copy, and looks it up by fingerprint

Starting the app is expensive, so Spring does not throw the copy away when the test finishes. It
keeps it and offers it to the next test that can use it. This is a **cache**: a place to keep
something expensive you already made, so you don't make it again. (Think of leftovers in a fridge:
you cooked once, so the next meal takes two minutes instead of an hour.)

Two pieces of vocabulary you will see everywhere:

- a **cache hit** means the copy you needed was already there — fast, nothing was built;
- a **cache miss** means it wasn't there, so Spring builds a fresh copy and stores it.

The interesting question is *how Spring decides whether the copy it has is the right one*. It
doesn't use a name you chose. It builds a **fingerprint** out of every part of the test's
configuration. If two test classes have exactly the same fingerprint, they share one copy of the
app. If anything differs, Spring considers it a different situation and builds a second copy.

The things that go into the fingerprint include:

- which configuration classes and component classes the test asked for,
- which configuration *profiles* are active (a profile is a named set of settings, like "test" or
  "production"),
- which properties were overridden for the test,
- and — this one catches people out — **which real objects the test replaced with stand-in fakes**.

**A concrete example from this repository.** One test checked the exact JSON the service returns. To
do that, it replaced the real rate-lookup object with a fake, using an annotation called
`@MockitoBean`. That single line changed the fingerprint. From Spring's point of view it was now a
different situation from every other test, so it could never share a copy of the app with them. Two
test classes that both wanted "the whole app running" ended up starting it twice.

---

## 4. Why this becomes a serious problem in a big repository

In this repository there are a handful of test classes, so a few extra copies of the app cost a few
seconds. In a repository with several hundred test classes, written by twenty people, the same
mechanism produces something much worse, for two reasons.

**First, copies multiply.** Every slightly different test setup is another copy. Nobody decides "we
will have forty copies of the app" — it just accumulates, one reasonable-looking variation at a
time.

**Second, there is a hidden limit.** Spring's cache does not hold unlimited copies. It holds at most
**32**, and when it is full it throws out the copy that was used longest ago to make room. So in a
large suite you get a loop: a copy gets thrown out, a later test needs it, Spring builds it again,
and something else gets thrown out. Work is repeated and thrown away over and over. This is called
**thrashing**, and it means a big suite can be slow in a way that is invisible from the outside.

So the goal is: **keep the number of copies small, and don't let that number silently grow.**

---

## 5. The fix, part one: tiers are folders, not labels

A large test suite needs categories. "These are fast tests I run constantly; these are slow ones that
need a database." The category a test belongs to is called its **tier**.

There are two ways to express a tier.

**The weak way: a label.** You write a word on the test class, and the build is told to run
everything with that word. It looks tidy, and it is what most projects do. But the label is only a
string. Nothing checks it. And the failure mode is nasty: we renamed a label from `"ephemeral"` to
`"ephemral"` — one letter — and the build went **green while running zero tests**. A whole category
of testing disappeared without a single warning. We also confirmed that Gradle's built-in safety net
for empty test runs does not catch this, because Gradle sees the test classes fine and it is JUnit
that quietly filters them out afterwards.

**The strong way: a folder.** In Gradle you can mark a folder as a **source set**. A source set is
its own little area of the project: its own files, its own list of libraries, and its own command to
run it. That means the tier is not written on the test — the tier *is where the test lives*. There is
no string to mistype, and the folder can control what the test is even allowed to use, which turns
out to be the most valuable part of all (see the next section).

We use three:

```
src/test/            UNIT         - plain Java. Fastest tier, no Spring at all, no Docker.
src/integrationTest/ INTEGRATION  - small slices of the app, one HTTP adapter test, and the
                                   checks that the real database is wired correctly.
src/systemTest/      SYSTEM       - the whole app, over real HTTP, on the real database.
                                   (Replaced by the blackbox/ subproject - see section 12.)
src/testFixtures/    (not a tier) - shared helper code the tiers all reuse.
```

There used to be a fourth tier whose single distinguishing feature was "runs on a real PostgreSQL
database". Once every database test moved onto real PostgreSQL, that tier had nothing unique left,
so it was removed rather than kept as a duplicate of the others.

One consequence is worth stating plainly: **running the tests now requires Docker**, because a real
database has to come from somewhere. The reasoning is in section 9.

There is also a fifth category that is *not* a tier: **regression tests**. A regression test does not
check that the code is correct — it records what the code does *today*, so that if somebody changes
it by accident, the test complains. Regression is a *purpose*, not a speed category, so those tests
live inside whichever tier matches how they work.

---

## 6. The fix, part two: the folder decides which libraries the test may use

This is the most important idea in the document, and it needs one more term.

A **classpath** is the list of libraries that are available at a particular moment — when code is
being compiled, or when it is being run. If a library is not on the classpath, code that mentions it
simply will not compile.

Each source set in this project has its own classpath. So the unit tier (`src/test`) has a
deliberately short list of libraries: a test framework, an assertion library, a mocking library, and
an architecture-checking library. What it does **not** have is Spring's *testing* library — the one
that provides `@SpringBootTest` and the rest.

The result is not a rule anyone has to remember. It is a fact about the compiler:

```
error: package org.springframework.boot.test.context does not exist
  symbol: class SpringBootTest
```

That is the real output from deliberately writing a unit test that tried to start the app. It did not
compile. A unit test **cannot** start the app, because the ability to do so isn't in the folder.

**Two honest details, so this isn't oversold:**

1. Spring's *core* libraries are still on that classpath, because the application itself is built on
   Spring and a test in the same project sees what the application sees. What is missing is
   specifically the test-support part. So the accurate claim is "a unit test cannot compile a
   reference to a Spring test annotation", not "no Spring is present". In practice that is the
   distinction that matters: you cannot accidentally start the app by writing an annotation.
2. Because this depends on a list of libraries staying correct, it is not left to trust. There is a
   check that fails the build if Spring's test support ever reaches that classpath — see section 8.

---

## 7. Sharing one copy of the app on purpose

The measurement in section 3 was the problem: two test classes that both wanted the whole app running
started it twice. Here is how they were made to share.

Rather than writing the setup on each test class, all the settings for "I want the whole application,
over real HTTP" were collected into a single reusable annotation called `@FullStackTest`. Every
black-box test uses it. Because they all use the same one, their fingerprints are identical, and
Spring hands them the same copy.

There is one subtlety worth spelling out, because it is easy to get wrong. A test can also point the
application at a stubbed-out external service by supplying a property value at runtime. That
mechanism is *also* part of the fingerprint — and, annoyingly, it is part of the fingerprint per
*declaration*: if two test classes each declare their own, they get different fingerprints even when
the value is the same. The fix is to write that declaration once, in a shared base class both tests
extend, so there is only one declaration and therefore one fingerprint.

**The result, measured:**

| Tier | Copies of the app built | Reuse |
| --- | --- | --- |
| unit | 0 | — |
| integration | 2 | 95% of lookups were hits |
| system | **1**, shared by 2 test classes | — |

And here is what that buys, in the suite's own timing output:

```
quote API, black box        5 tests   5.46s   <- the one that pays for building the app
wire contract regression    1 test    0.02s   <- reuses the same copy, essentially free
```

That second line is the whole point. A second full application test now costs two hundredths of a
second, because it reuses a copy that already exists.

---

## 8. The guards

A guard is a check in the build that fails loudly when something quietly goes wrong. Guards exist
because the dangerous failures in a test suite are the silent ones: the build goes green while
testing less than you think it is.

| Guard | What it protects against |
| --- | --- |
| `verifyContextBudget` | The number of app copies creeping up |
| `verifyTierClasspaths` | The unit tier's "no Spring test support" property decaying |
| `failOnNoDiscoveredTests` | A tier that finds no tests at all |
| `ArchitectureTest` (ArchUnit) | The code's internal layering quietly breaking down |

**`verifyContextBudget`** reads the statistics Spring prints about its cache and fails the build if
too many copies of the app were built. It also fails if it finds *no* statistics at all — because a
guard that silently stops observing things is worse than no guard. This is the same reasoning as a
smoke alarm that beeps when its battery dies.

One correction is worth describing, because it was a genuine bug in the first version. The guard
originally checked **how many copies were sitting in the cache**. That is the wrong number, and
subtly so: the cache holds at most 32 copies, so that number can never go above 32 no matter how much
work the suite actually did. A suite that built three hundred copies would throw most away, report
"32 in the cache", and pass the check — in exactly the situation the check existed to catch. It now
checks **how many copies were built**, which grows without limit and reflects the real cost.

**`verifyTierClasspaths`** checks the list of libraries in section 6 and fails if Spring's test
support, or the container-testing and HTTP-stubbing libraries, ever appear on the unit tier's
classpath. This matters because the arrangement depends on a list of libraries, and lists of
libraries get edited. It proved its worth immediately: on its very first run it caught one of those
libraries leaking into the unit tier through the shared helper code.

**`failOnNoDiscoveredTests`** is built into Gradle and fails a tier that discovers zero tests. It
catches a different problem from the label typo in section 5 — it catches a tier being wired up
incorrectly. Both are worth having.

**`ArchitectureTest`** uses a library called ArchUnit to read the compiled code and check rules about
how the parts of the application are allowed to depend on each other — for example, that the rate-
lookup code never reaches into the quoting code. These are the rules that quietly erode as a project
and a team grow.

---

## 9. Decisions worth explaining

**The default build now needs Docker, and that is a deliberate reversal.** An earlier version of
this repository kept an in-memory database so that the ordinary build would run on any laptop with
no setup. That sounded appealing, but it meant the default build validated against a database that
production does not use — and worse, it constrained the migrations. Any PostgreSQL-specific feature
would have had to be avoided so the in-memory database could also run it, which means the production
schema was being shaped by a test database. Since a container was already required for some tests,
keeping a Docker-free subset only meant the Docker-free subset was the one you trusted least.

**The system tier could not see the database, and that was enforced by the compiler.** When this tier
was first written, one test reached directly into the database repository to confirm a row had been
saved. It failed to compile, because that tier's classpath has no database library. The right fix was
not to add the library: a "black box" test that inspects the database isn't a black box test. The fix
was to add a read endpoint to the service, so the test could confirm the saved data through the same
public HTTP interface a real client would use. The build enforced the design instead of a comment in
a document enforcing it.

**Shared helper code (in `src/testFixtures`) contains no Spring at all.** Every tier that uses it compiles
against it, including the unit tier, so a single Spring type in it would have undone section 6. The
same reasoning applies to the HTTP-stubbing library: it is a private implementation detail of the
helper, not something exported to the tiers, so a tier that wants to use that library directly has to
say so explicitly.

**There is no version catalog.** A version catalog is a file listing every library version in one
place, which pays off in a project split into many modules. This project is one module and already
gets its versions from a single Spring-provided list, so a catalog would add indirection without
removing any duplication.

---

## 10. What a second opinion changed

This design was reviewed by a different model before it was finished, and three of the review's
findings were acted on:

1. **The budget guard measured the wrong number**, as described in section 8. This was a real bug.
2. **The default build was not actually Docker-free.** The coverage task was pulling in the container
   tier, so the "no Docker needed" claim was false. Fixed by excluding it.
3. **The rule in section 6 was only a convention**, not enforced — it worked, but only as long as
   nobody edited a library list. Fixed by adding `verifyTierClasspaths`, which then immediately found
   a real leak.
4. **Run the database tests against real PostgreSQL instead of an in-memory database.** This was the
   strongest criticism and has now been done in full: the in-memory database is gone, the persistence
   slice runs on a PostgreSQL container, and the extra tier it created was collapsed. See section 9.

Several other recommendations were deliberately **not** implemented yet, in rough priority order:

- **Count app copies using a purpose-built hook** instead of reading a log line. Spring's log format
  is an internal detail that can change between versions. The current approach has a safety net that
  fails when the statistics disappear, which is the cheap mitigation.
- **A data-isolation policy for the shared container.** Now that every database test shares one
  container for the run, tests could start stepping on each other's rows. The persistence slice rolls
  back automatically; the whole-application tests do not, and nothing enforces that yet.
- **A policy for unreliable tests.** At several hundred test classes, tests that fail occasionally
  are the main reason people stop trusting a suite. None of the current guards can see that.
- **A minimum number of tests per tier**, because "zero tests" is where a tier *starts* dying. A tier
  where every test has been switched off still reports a healthy-looking number.

---

## 11. What has been verified, and what hasn't

**Verified by running it.** All three tiers compile. The unit tier refuses to compile a test that
references `@SpringBootTest`. The budget guard fails when the budget is set too low. The classpath
guard caught a real leak. All the timings and counts in this document are measured output. The build
is green at 98.2% line coverage.

**Where the verification happens, and why that changed.** This repository was built on a machine with
no container runtime at all, so anything involving a database can only be verified in continuous
integration. That is less a flaw than a consequence of the deliberate choice in section 9: if the
database tests use a real database, they need a real database from somewhere. The unit tier is the
exception — it runs anywhere, with nothing installed.

The move to PostgreSQL was compile-checked locally and then confirmed in CI on its first run: the
container starts, Flyway applies the migration to the real engine, the persistence slice's
plain-JDBC assertions confirm the running engine really is PostgreSQL, and no test skipped.

---

## 12. The black-box migration

The system tier had one flaw no guard could fix: it started the application *inside the test JVM*
and read responses into the application's own `QuoteResponse` type, comparing numbers by value. It
therefore could not see anything that type normalises away. The clearest case: POST returns
`"amount":10000.00` while GET for the same quote returns `"amount":10000.0000`, because the column is
`NUMERIC(19,4)`. Both deserialise to the same `BigDecimal` value, and the test passed.

It was replaced, following `BLACKBOX-TEST-PLAN.md`, by `blackbox/`:

- a **separate Gradle subproject with no dependency on the application** — `verifyBlackboxIsolation`
  fails the build if one appears — so a scenario cannot import the response type even by accident;
- the **real Docker image**, configured only by environment variables, started with PostgreSQL and a
  WireMock container through Testcontainers `ComposeContainer`;
- **Cucumber** scenarios that assert on **raw JSON tokens**, with `cucumber-spring` supplying a small,
  test-only Spring context for the steps (never the application's);
- **read-only SQL** for the few facts the API cannot show, through a PostgreSQL role granted only
  `SELECT`.

The context budget now covers the unit and integration tiers only; the black-box tier boots no Spring
context of the application's. Coverage is measured over those two tiers too — JaCoCo in the test JVM
cannot see an application running in another container — so the read path gained unit and web-slice
tests *before* the system tier was deleted.

Three defects the old tier could not see are recorded in `known_bugs.feature`, written as the correct
behaviour and excluded by default. CI reports on every run how many still reproduce.

Getting the suite to run took several CI iterations; the causes (a Dockerfile entrypoint the image
couldn't satisfy, Compose v1 vs v2 container naming, a log dump that looked at the wrong project, and
undefined Cucumber steps) are recorded in PR #2 and in chapter 14 of the book in `book/`. The last of
them is why the fast CI job now runs a Cucumber dry run (`./gradlew :blackbox:test -PdryRun`), which
needs no Docker.
