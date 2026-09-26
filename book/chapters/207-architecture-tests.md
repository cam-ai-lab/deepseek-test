# 7 Architecture tests

::: {.callout .covers}
This chapter covers

- Why designs erode, and why code review alone doesn't stop it
- Testing structure instead of behaviour, with ArchUnit
- The lab's three dependency rules, and the gaps between them
- Importing the right bytecode so rules can't be fooled
- Writing new rules: layering, naming, and "no Spring in the core"
:::

**After this chapter you will be able to** turn an architectural decision into a failing test,
choose rules that are worth enforcing, and avoid rules that only generate noise.

::: {.callout .recall}
Warm-up

1. What does a regression test protect against that a functional test doesn't? (section 6.1)
2. Why is blanking `createdAt` in the golden file a risk? (6.4)
3. Explain "functional core, imperative shell" in one sentence. (5.1)
:::

## 7.1 Designs erode quietly

Every codebase starts with a shape in someone's head: "the rate adapter doesn't know about quoting";
"the domain doesn't depend on the web layer". Then, one day, someone under deadline pressure imports
`QuoteResponse` into `HttpRateGateway` because it's convenient. The code compiles, the tests pass,
the reviewer is busy. Six months later the packages are a tangle, and every change touches
everything.

Code review is a poor defence against this because the violation is *small and local* — one import
line — while the damage is *large and global*. What you want is for the design to be checked by a
machine on every build, like behaviour is. That's an **architecture test**: a test whose subject is
the *structure* of the code rather than its behaviour.

On the four axes, the lab's `ArchitectureTest` is **unit scope** (it analyses bytecode; nothing
runs), **white-box**, **architecture purpose**, **in-process / every build**. It runs in
milliseconds, which is what makes it viable as a permanent guard.

## 7.2 The lab's rules

![Figure 7.1 The package rules. Solid arrows are allowed dependencies; dashed ones are forbidden and enforced by ArchitectureTest.](images/07-packages.png)

```java
// Listing 7.1 Three dependency rules (ArchitectureTest)
private static final JavaClasses MAIN_CLASSES =
        new ClassFileImporter().importPath(mainClassesDirectory());          // #1

@Test
void the_rate_adapter_does_not_know_about_quoting() {
    noClasses().that().resideInAPackage("com.example.quotes.rate..")         // #2
            .should().dependOnClassesThat().resideInAPackage("com.example.quotes.quote..")
            .check(MAIN_CLASSES);
}

@Test
void the_rate_adapter_does_not_know_about_the_web_layer() { ... }

@Test
void the_quote_domain_does_not_depend_on_the_web_layer() { ... }
```

::: {.annotations}
1. Import compiled classes once, into a static field — importing is the slow part.
2. ArchUnit's fluent DSL reads almost like English. The `..` suffix means "this package and all
   sub-packages".
:::

When a rule fails, ArchUnit names every offending dependency — the class, the method, the line —
so the failure message is itself the to-do list.

## 7.3 Import exactly the right bytecode

A subtle but important detail: *which* classes does the rule check? If you import the whole
classpath, test classes are included — and test classes legitimately depend on everything. A rule
like "nothing in `quote` depends on `web`" could then be violated by a *test* in the `quote`
package, or accidentally satisfied because a test class was counted instead of the real one.

The lab solves this at the build level. Gradle passes the directory of compiled *main* classes as a
system property, and the test imports only that:

```kotlin
// Listing 7.2 Telling the test exactly what to analyse (build.gradle.kts)
testTask.configure {
    systemProperty(
        "mainClassesDir",
        layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
    )
}
```

There's a fallback for running from an IDE (find the directory containing `QuotesApplication`),
and the test prints how many classes it imported — a small but valuable canary. If it ever says
"imported 0 production classes", every rule would pass vacuously.

::: {.callout .myth}
Misconception: "A passing architecture test means the architecture is fine"

A rule over an empty set of classes always passes. So does a rule whose package pattern has a typo
(`com.example.quote..` instead of `com.example.quotes.quote..`). Architecture tests need a canary
— a check that they're actually looking at something. ArchUnit can help: rules fail by default when
they match no classes (`archRule.failOnEmptyShould`), and printing the import count makes the
problem visible.
:::

## 7.4 Gaps worth closing

The three rules protect the `rate` package well. But look at figure 7.1 again and ask what *isn't*
forbidden:

- Nothing stops `QuoteController` calling `QuoteRepository` directly, bypassing the service.
- Nothing stops the domain (`QuoteCalculator`) from depending on Spring or JPA.
- Nothing stops `rate` from growing a dependency on `config`.

Here are rules that would close those gaps.

```java
// Listing 7.3 Rules the lab could add
@Test
void controllers_do_not_talk_to_repositories() {
    noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .check(MAIN_CLASSES);
}

@Test
void the_calculator_is_framework_free() {
    noClasses().that().haveSimpleName("QuoteCalculator")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.web..", "org.springframework.data..")
            .check(MAIN_CLASSES);
}

@Test
void layers_are_respected() {
    layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..web..")
            .layer("Quote").definedBy("..quote..")
            .layer("Rate").definedBy("..rate..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Quote").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Rate").mayOnlyBeAccessedByLayers("Quote", "Web")
            .check(MAIN_CLASSES);
}
```

Notice that the second rule allows `org.springframework.stereotype` (for `@Component`) but forbids
web and data dependencies. Deciding exactly *where the line is* is the real architectural work; the
test just holds the line once you've drawn it.

::: {.callout .mental}
Mental model: a rule is a decision with a guard

Only write an architecture rule for a decision the team has *actually made* and would defend in a
design review. A rule that encodes someone's preference generates friction and gets deleted. A rule
that encodes a real decision saves a design conversation every time it fails.
:::

## 7.5 Other structural checks

ArchUnit rules can check much more than package dependencies:

| Rule type | Example |
| --- | --- |
| Naming | Classes annotated `@RestController` end with `Controller` |
| Annotations | No field injection (`@Autowired` on fields) |
| Cycles | No cycles between top-level packages (`slices().matching(...).should().beFreeOfCycles()`) |
| Visibility | Repositories are package-private |
| Forbidden APIs | Nobody calls `Instant.now()` or `LocalDate.now()` outside `config` |

That last one is worth dwelling on: it turns the *determinism* lesson of chapter 3 into an enforced
rule. If someone writes `Instant.now()` in a service, the build fails and points them at the
injected `Clock`.

::: {.callout .tryit}
Try it: a forbidden-API rule

Write a rule that forbids calls to `java.time.Instant.now()` from any class outside
`com.example.quotes.config`. Hint: `noClasses().that().resideOutsideOfPackage(...)
.should().callMethod(Instant.class, "now")`. Then temporarily add `Instant.now()` to
`QuoteService` and watch the rule fail. *Needs a JDK: `./gradlew test`.*
:::

::: {.callout .quiz}
Check your understanding

1. Why is code review alone a weak defence against architectural erosion?
2. What would go wrong if `ArchitectureTest` imported the whole test classpath?
3. Name one architectural decision in the lab that is *not* currently enforced, and write the rule
   in words.
4. When should you *not* write an architecture rule?
5. How can an architecture test enforce the determinism practice from chapter 3?
:::

::: {.callout .summary}
Summary

- **Architecture tests** check structure, not behaviour. They're unit-scope, white-box, and fast
  enough to run on every build.
- ArchUnit expresses dependency, layering, naming and forbidden-API rules as ordinary tests with
  precise failure messages.
- **Import only production bytecode**, and add a canary so an empty import can't pass silently.
- The lab's three rules protect the `rate` package; controller→repository access and framework
  dependencies in the core are unguarded.
- Encode **decisions**, not preferences.
:::
