# 11 Test architecture as code

::: {.callout .covers}
This chapter covers

- Tiers as Gradle JVM Test Suites: source sets instead of tags
- Shared test fixtures, and `api` versus `implementation` visibility
- Build guards: classpath rules, a context budget, "zero tests is a failure"
- Coverage: what the number means, what it doesn't, and mutation testing
- Why every guard needs a canary
:::

**After this chapter you will be able to** structure a multi-tier suite so that the rules are
enforced by the build rather than by convention, read the lab's `build.gradle.kts` with confidence,
and use coverage as a tool rather than a target.

::: {.callout .recall}
Warm-up

1. Name the five items on the adapter checklist. (section 10.2)
2. Why does the lab use a singleton container rather than one per test class? (9.4)
3. What is "stub drift"? (10.4)
:::

## 11.1 Conventions don't survive growth

Suppose your team agrees: "unit tests must not start Spring." In a team of three, that agreement
holds because everyone remembers it. In a team of thirty, over three years, someone new will write
`@SpringBootTest` in the unit folder because it was the quickest way to get a bean — and nothing
will stop them. The unit tier gets a little slower. Then someone copies that test. Two years later
"unit tests" take eight minutes.

The lab's answer is to make the rule **physically impossible to break** rather than merely
discouraged. That's the theme of this chapter: **test architecture as code**.

## 11.2 Tiers are folders, not labels

A common way to separate tiers is tagging: `@Tag("integration")` on a class, and a Gradle filter
that includes or excludes tags. The lab deliberately doesn't do that. Instead, each tier is a
separate **Gradle JVM Test Suite** — its own source set, its own dependencies, its own task.

![Figure 11.1 Each tier is a source set with its own classpath. Arrows show what each tier can compile against.](images/11-suites.png)

```kotlin
// Listing 11.1 The unit tier: no Spring test support at all (build.gradle.kts, abridged)
testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation("org.assertj:assertj-core")
                implementation("org.mockito:mockito-junit-jupiter")
                implementation(archunit)
                implementation(testFixtures(project()))
            }                                                          // #1
        }

        val integrationTest = register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
                implementation(testcontainersPostgres)
                implementation(wiremock)
            }                                                          // #2
        }
    }
}
```

::: {.annotations}
1. No `spring-boot-starter-*-test`. A unit test that writes `@SpringBootTest` or `@MockitoBean`
   **does not compile** — the annotation isn't on the classpath.
2. The integration tier adds exactly the libraries it's allowed: web and JPA slices, Testcontainers,
   WireMock.
:::

Why is this better than tags? Two reasons:

- **Enforcement.** A tag is a string you can forget or mistype. A classpath is a wall.
- **Honest failure.** The lab's documentation records that an earlier, tag-based version had a
  filter that matched *no tests* in one tier — and the build reported success. With source sets, the
  tier is the folder; you can't accidentally select nothing.

::: {.callout .mental}
Mental model: make the wrong thing impossible, not just discouraged

Every rule about tests is either **enforced by a machine** or **hoped for**. Hoped-for rules decay.
When a rule matters — unit tests don't boot Spring, black-box tests don't see app code — find a way
to make breaking it a compile error or a build failure.
:::

## 11.3 Shared fixtures, and who can see what

`src/testFixtures` holds test code that several tiers share: `QuoteTestData` (builders for requests
and entities), `StubRateGateway`, `PostgresContainer`. Gradle's `java-test-fixtures` plugin compiles
it once and lets each suite depend on it with `testFixtures(project())`.

There's a subtle problem. `PostgresContainer` uses Testcontainers internally. If Testcontainers
leaked onto the *unit* tier's compile classpath through the fixtures, a unit test could start a
container. The lab prevents that with Gradle's visibility levels:

```kotlin
// Listing 11.2 Fixture dependencies: api vs implementation (build.gradle.kts)
testFixturesApi(platform(springBootBom))               // #1
testFixturesImplementation(wiremock)                   // #2
testFixturesImplementation(testcontainersPostgres)
```

::: {.annotations}
1. `api`: part of the fixtures' public surface; consumers see it at compile time.
2. `implementation`: used *inside* the fixtures only. It's on consumers' runtime classpath (so
   `PostgresContainer` works when called), but not on their compile classpath — so a consumer can't
   write `new PostgreSQLContainer(...)` itself.
:::

This only works if the fixtures **never expose** a Testcontainers or WireMock type in a public
signature — which is why `PostgresContainer` returns plain `String`s and `RateServiceStub` offers
intent-revealing methods like `stubRate(product, rate)` instead of handing out the `WireMockServer`.
Good encapsulation in test code has a concrete payoff here.

## 11.4 Guards

Enforcement through classpaths is strong but can be undone by an innocent-looking edit — someone
moves a dependency to `testFixturesApi` "for consistency", and the wall has a hole. So the lab adds
**guard tasks** that check the architecture of the build itself.

![Figure 11.2 What ./gradlew check runs: the tiers, plus three guards and a global "no empty suite" rule.](images/11-guards.png)

### 11.4.1 The classpath guard

```kotlin
// Listing 11.3 verifyTierClasspaths (build.gradle.kts, abridged)
val verifyTierClasspaths = tasks.register("verifyTierClasspaths") {
    val unitCompileClasspath = configurations.named("testCompileClasspath")
    doLast {
        val forbiddenMarkers = listOf("spring-test-", "spring-boot-test", "spring-boot-starter-test",
                                      "testcontainers-", "wiremock-")
        val offenders = unitCompileClasspath.get().files.map { it.name }
            .filter { name -> forbiddenMarkers.any { name.contains(it) } }
        if (offenders.isNotEmpty()) {
            throw GradleException("The unit tier must not compile against ...: $offenders")
        }
    }
}
```

It resolves the unit tier's compile classpath and fails if any forbidden jar appears. The comment in
the build file explains the stakes: "That classpath IS the mechanism which stops a unit test booting
a context — restore it rather than relaxing this check."

It's deliberately crude — matching jar *names* — and it only checks the *compile* classpath. Those
are reasonable trade-offs, but worth knowing: a guard's limits are part of its specification.

### 11.4.2 The context budget

Chapter 8 argued that the number of Spring contexts is the metric that governs suite speed. The
`verifyContextBudget` task measures it. Spring exposes no API for this, so the build switches on
debug logging for the context cache, and the task reads lines like this from the test reports:

```
DefaultContextCache@1b2c3d4e size = 2, maxSize = 32, parentContextCount = 0,
hitCount = 14, missCount = 2, failureCount = 0
```

`missCount` is the number of contexts actually **built**. (Not `size` — the cache is capped at 32
entries, so `size` can never show a suite that built 300 contexts and evicted most of them.) If any
test JVM built more than the budget — default 6, overridable with `-PspringContextBudget=<n>` — the
build fails with advice on what usually splits a context.

### 11.4.3 Zero tests is a failure

```kotlin
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = true
}
```

One line, and a whole class of silent failures disappears: a misconfigured suite, a renamed
package, a broken filter. "Nothing ran" is never success.

::: {.callout .myth}
Misconception: "A guard that passes is working"

A guard that can no longer *see* anything also passes. If someone removes the cache debug-logging
property, `verifyContextBudget` would find no statistics and — naively — report no violations. The
lab's task checks for that: if **no suite** reports any statistics at all, it fails with *"this guard
would silently pass."* Every guard needs a **canary**: a check that it's still observing. We've now
seen three: the ArchUnit import count (chapter 7), the "really PostgreSQL" tests (chapter 9), and
this one.
:::

## 11.5 Coverage: a flashlight, not a scoreboard

The lab runs JaCoCo across all tiers and fails the build below **85% line coverage**. What does
that number mean?

Line coverage is the fraction of executable lines that ran at least once during the tests. That's
all. It says a line *executed*; it says nothing about whether any test *checked* what that line did.

```java
@Test
void covers_everything_checks_nothing() {
    service.createQuote(request);     // 100% of createQuote's lines run. Nothing is asserted.
}
```

That test raises coverage and finds no bugs. So:

- **Low coverage is informative**: there's code no test even runs. Look there.
- **High coverage is not**: it's necessary for confidence but nowhere near sufficient.
- **Coverage as a target backfires** (Goodhart's law): people write tests that execute code rather
  than tests that check behaviour.

A threshold is still useful as a *floor* — it stops large amounts of untested code slipping in. Use
the report as a **flashlight** to find dark corners, not as a scoreboard.

::: {.callout .hood}
Under the hood: mutation testing

If coverage asks "did this line run?", **mutation testing** asks "would any test notice if this line
were wrong?" A tool such as PIT (Pitest) makes small changes to your bytecode — flips `<` to `<=`,
replaces `HALF_UP` with `HALF_DOWN`, removes a method call — and re-runs the tests. If the tests
still pass, the mutant "survived": you have code that is executed but not effectively checked. On
the lab's `QuoteCalculator`, the rounding test from chapter 5 would kill the `HALF_UP` mutant; the
assertion-free test above would kill nothing. Mutation testing is slow, so teams run it on core
modules or nightly, not on every build.
:::

## 11.6 A coverage trap in Part 3's plan

Coverage counts lines executed by *any* tier. Today, `QuoteService.findQuote` and the GET endpoint
are executed only by the **system** tier. When Part 3 replaces that tier with a black-box suite
running the app in a separate Docker container, JaCoCo — which instruments the *test* JVM — won't see
those lines run. Coverage drops, possibly below 85%, and the build fails.

That's not a reason to avoid the change; it's a reason to **add the cheap tests first**: a unit test
like chapter 3's exercise and slice tests like listing 8.2 — which, frankly, should have existed
anyway. The migration on `main` did exactly that, in that order. A build guard that fails for an honest reason is doing its job.

::: {.callout .tryit}
Try it: read the build like a map

Open `build.gradle.kts` and, for each of these, find the line that implements it: (1) the unit tier
cannot see Spring test support; (2) integration tests run after unit tests; (3) `check` runs the
context budget; (4) coverage includes all tiers; (5) an empty suite fails. *Needs only a text
editor.*
:::

::: {.callout .quiz}
Check your understanding

1. Give two reasons the lab uses Gradle test suites rather than JUnit tags to separate tiers.
2. Why must `PostgresContainer` return `String`s instead of the container object?
3. Why does `verifyContextBudget` read `missCount` rather than `size`?
4. What's a "canary" in the context of a test guard? Give two examples from the lab.
5. A teammate proposes raising the coverage threshold to 100%. What do you say?
:::

::: {.callout .summary}
Summary

- Rules that matter should be **enforced by the build**, not hoped for.
- Separate tiers as **source sets**: the classpath makes forbidden tools uncompilable, and a tier
  can't accidentally select nothing.
- Shared **test fixtures** need encapsulation: `implementation` dependencies and plain-type
  signatures keep heavy libraries off consumers' compile classpaths.
- Guards: a **classpath check**, a **context budget** (count contexts built), and
  **failOnNoDiscoveredTests**. Every guard needs a **canary**.
- **Coverage** is a flashlight: low is informative, high isn't proof. **Mutation testing** measures
  whether tests actually check what runs.
:::
