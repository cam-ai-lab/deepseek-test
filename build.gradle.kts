plugins {
    java
    // Compiles the shared test vocabulary once and publishes it as a consumable variant, so
    // every tier can reuse it instead of copy-pasting builders and fakes.
    `java-test-fixtures`
    id("org.springframework.boot") version "4.1.1"
    jacoco
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "Quote service plus the test architecture that keeps a large suite fast and trustworthy."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springBootBom = "org.springframework.boot:spring-boot-dependencies:4.1.1"
val wiremock = "org.wiremock:wiremock-standalone:3.13.1"
val archunit = "com.tngtech.archunit:archunit:1.5.1"
val testcontainersPostgres = "org.testcontainers:testcontainers-postgresql"

dependencies {
    implementation(platform(springBootBom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // The only database engine involved, in every environment. There is deliberately no
    // in-memory database: a migration or query that PostgreSQL rejects must fail in tests too.
    runtimeOnly("org.postgresql:postgresql")

    // testFixturesApi, not implementation: the fixture types are part of the surface every
    // tier compiles against, so their types must be visible downstream. THIS is what makes
    // `testFixtures(project())` work across suites in a single-module build.
    //
    // WireMock and Testcontainers are deliberately `implementation`, not `api`: no fixture exposes
    // either type in a public signature, so neither library lands on a consumer's compile
    // classpath. verifyTierClasspaths enforces that for the unit tier.
    testFixturesApi(platform(springBootBom))
    testFixturesImplementation(wiremock)
    testFixturesImplementation(testcontainersPostgres)
}

// ---------------------------------------------------------------------------------------------
// Test tiers as Gradle JVM Test Suites: real source sets, real configurations, real tasks.
//
// The tier is therefore a property of WHERE the code lives, not a string on a class. That is
// what lets the unit tier simply not have the Spring test artifacts on its classpath.
// ---------------------------------------------------------------------------------------------
testing {
    suites {

        // UNIT. Deliberately declares no spring-boot-starter-*-test dependency: a unit test
        // that reaches for @SpringBootTest or @MockitoBean does not compile.
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation("org.assertj:assertj-core")
                implementation("org.mockito:mockito-junit-jupiter")
                implementation(archunit)
                implementation(testFixtures(project()))
            }
            targets {
                all {
                    testTask.configure {
                        // Tells ArchitectureTest exactly which bytecode to analyse, so a rule can
                        // never accidentally be satisfied - or violated - by a test class.
                        systemProperty(
                            "mainClassesDir",
                            layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
                        )
                    }
                }
            }
        }

        // INTEGRATION. Spring slices, plus the checks that the real database is wired correctly.
        val integrationTest = register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(platform(springBootBom))
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
                implementation(testcontainersPostgres)
                runtimeOnly("org.postgresql:postgresql")
                runtimeOnly("org.flywaydb:flyway-database-postgresql")
                // Declared explicitly because testFixtures only exports WireMock as an
                // `implementation` dependency: a suite that compiles against WireMock says so.
                implementation(wiremock)
            }
        }

        // SYSTEM. Black box: real HTTP into a running app, all remote dependencies stubbed. Note
        // there is still no JPA on this classpath - it must not be able to look at the database.
        val systemTest = register<JvmTestSuite>("systemTest") {
            dependencies {
                implementation(platform(springBootBom))
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-starter-restclient-test")
                implementation(testcontainersPostgres)
                runtimeOnly("org.postgresql:postgresql")
                runtimeOnly("org.flywaydb:flyway-database-postgresql")
            }
        }
    }
}

val testTask = tasks.named<Test>("test")
val integrationTestTask = tasks.named<Test>("integrationTest")
val systemTestTask = tasks.named<Test>("systemTest")

// Every tier gets the same baseline, including the measurement hook that feeds the context
// budget gate below.
tasks.withType<Test>().configureEach {
    // A suite that discovers nothing is a misconfiguration, never a pass. Because a suite's
    // tier is its source set (not a tag filter), this actually works - see docs/ for why the
    // tag-based equivalent silently passed.
    failOnNoDiscoveredTests = true
    systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

integrationTestTask { shouldRunAfter(testTask) }
systemTestTask { shouldRunAfter(integrationTestTask) }

// ---------------------------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------------------------

// Fails if the suite boots more distinct ApplicationContexts than budgeted. Spring exposes no
// API for this, so it is read from the TestContext cache statistics Spring logs at JVM
// shutdown. It fails when the statistics are MISSING as well as when they exceed budget: a
// guard that quietly stops observing is worse than no guard.
val verifyContextBudget = tasks.register("verifyContextBudget") {
    group = "verification"
    description = "Fails if any test JVM boots more Spring ApplicationContexts than the budget."
    dependsOn(testTask, integrationTestTask, systemTestTask)
    outputs.upToDateWhen { false }

    val resultsRoot = layout.buildDirectory.dir("test-results")
    val budget = providers.gradleProperty("springContextBudget").map(String::toInt).orElse(6)

    doLast {
        // size = <n> is capped by the cache's LRU maxSize, so it can never exceed 32 no matter how
        // many contexts were actually created - a suite thrashing 300 configurations still reports
        // size = 32. The quantity that grows without bound, and therefore the one worth gating, is
        // missCount: how many ApplicationContexts were actually BUILT.
        val cacheLine = Regex(
            """DefaultContextCache@([0-9a-f]+) size = (\d+).*?hitCount = (\d+), missCount = (\d+), failureCount = (\d+)"""
        )
        var foundAny = false

        listOf("test", "integrationTest", "systemTest").forEach { suite ->
            val dir = resultsRoot.get().dir(suite).asFile
            val xml = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
            if (xml.isEmpty()) {
                throw GradleException("Suite '$suite' produced no test results in $dir.")
            }
            // Per JVM: peak cache occupancy, and total contexts built.
            val perJvm = mutableMapOf<String, Triple<Int, Int, Int>>()
            xml.forEach { file ->
                file.useLines { lines ->
                    lines.forEach { line ->
                        cacheLine.find(line)?.let { m ->
                            foundAny = true
                            val (jvm, size, hits, misses, failures) = m.destructured
                            val boots = misses.toInt() + failures.toInt()
                            val previous = perJvm[jvm]
                            if (previous == null || boots > previous.first) {
                                perJvm[jvm] = Triple(boots, size.toInt(), hits.toInt())
                            }
                        }
                    }
                }
            }
            if (perJvm.isEmpty()) {
                // Legitimate: the unit tier has no Spring tests, so it builds no contexts.
                // Whether the measurement still works at all is checked once, below.
                logger.lifecycle("  $suite: no Spring contexts (this tier builds none)")
                return@forEach
            }
            perJvm.forEach { (jvm, s) ->
                val (boots, peakSize, hits) = s
                val total = hits + boots
                val reuse = if (total == 0) 0 else hits * 100 / total
                logger.lifecycle(
                    "  $suite/${jvm.take(8)}: $boots context boots, peak cached $peakSize, " +
                        "$hits reuses (${reuse}% hit)"
                )
                if (boots > budget.get()) {
                    throw GradleException(
                        "Suite '$suite' built $boots ApplicationContexts in one JVM, over the budget " +
                            "of ${budget.get()}. Merge test configurations (a differing @MockitoBean, " +
                            "@ActiveProfiles, @TestPropertySource or @DynamicPropertySource is enough to " +
                            "split a context) or raise -PspringContextBudget=<n> deliberately."
                    )
                }
            }
        }
        if (!foundAny) {
            // The canary. If no suite reported statistics at all, the logging property has been
            // dropped or Spring renamed the log line, and this guard has silently stopped
            // observing - which is worse than not having it.
            throw GradleException(
                "No TestContext cache statistics were found in any suite. The " +
                    "logging.level.org.springframework.test.context.cache property is probably " +
                    "no longer set, so this guard would silently pass."
            )
        }
    }
}

// The keystone invariant - "a unit test cannot boot a Spring context" - currently rests on a
// dependency block staying correct. That is a convention, and conventions do not survive twenty
// contributors. This task is the actual enforcement: it fails if the Spring *test* artifacts,
// Testcontainers or WireMock ever reach the unit tier's compile classpath. That would happen
// silently if someone moved Spring to `api` in main, or added spring-boot-starter-test to
// testFixturesApi "for consistency".
//
// Note it forbids Spring's TEST artifacts, not Spring itself: spring-core/beans/context are on
// this classpath transitively through the application, and that is fine and unavoidable. What
// must never appear is the test support that lets a unit test boot a context.
val verifyTierClasspaths = tasks.register("verifyTierClasspaths") {
    group = "verification"
    description = "Asserts the unit tier cannot see Spring test support, Testcontainers or WireMock."
    val unitCompileClasspath = configurations.named("testCompileClasspath")
    doLast {
        // Checked by jar name: crude, but it needs no resolution API and every artifact here comes
        // from Maven Central with its real name.
        val forbiddenMarkers = listOf(
            "spring-test-",
            "spring-boot-test",
            "spring-boot-starter-test",
            "testcontainers-",
            "wiremock-"
        )
        val moduleTestStarter = Regex("""spring-boot-starter-.*-test-\d.*\.jar""")

        val offenders = unitCompileClasspath.get().files
            .map { it.name }
            .filter { name -> forbiddenMarkers.any { name.contains(it) } || moduleTestStarter.containsMatchIn(name) }
            .distinct()
            .sorted()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "The unit tier must not compile against Spring test support, Testcontainers or " +
                    "WireMock, but its compile classpath contains: $offenders. That classpath IS the " +
                    "mechanism which stops a unit test booting a context - restore it rather than " +
                    "relaxing this check."
            )
        }
        logger.lifecycle("  unit tier: no Spring test support, Testcontainers or WireMock on the compile classpath")
    }
}

// ---------------------------------------------------------------------------------------------
// Coverage and report aggregation
// ---------------------------------------------------------------------------------------------

jacoco {
    toolVersion = "0.8.13"
}

val coverageSuites = listOf(testTask, integrationTestTask, systemTestTask)

// Every tier's execution data counts. There is no longer a tier that has to be kept out of the
// default build, because every tier now runs on the same database the application does.
val coverageData = fileTree(layout.buildDirectory).include("jacoco/*.exec")

tasks.jacocoTestReport {
    dependsOn(coverageSuites)
    executionData.setFrom(coverageData)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(coverageSuites)
    executionData.setFrom(coverageData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

// `check` now requires Docker: every database test runs against a real PostgreSQL container, so
// there is no longer a subset of the suite that can meaningfully run without one. The unit tier
// still needs nothing.
tasks.check {
    dependsOn(integrationTestTask, systemTestTask, verifyContextBudget, verifyTierClasspaths)
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
