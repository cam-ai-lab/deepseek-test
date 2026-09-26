// The black-box suite: it starts the real image with docker compose and talks to it only over HTTP.
//
// Note what is NOT here: no `project(":")`. This module cannot see a single class of the
// application, which is the entire point - assertions compare raw JSON, so contract drift is
// caught rather than papered over. `verifyBlackboxIsolation` in the root build enforces that.
//
// The Spring Boot BOM is deliberately NOT imported either. It pins JUnit 6 and Spring 7, and
// Cucumber brings its own compatible set of JUnit Platform and Spring versions; importing both
// would put two version authorities in charge of the same jars. The few versions Boot would
// otherwise supply are declared explicitly below instead.

plugins {
    java
    // Adds the `gatling` source set (src/gatling/java) and the `gatlingRun` task for the nightly
    // performance smoke test. It shares BlackboxStack with the Cucumber suite.
    id("io.gatling.gradle") version "3.15.1.3"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val cucumberVersion = "8.0.2"
val testcontainersVersion = "2.0.5"
val restAssuredVersion = "5.5.6"
val springVersion = "6.2.11"
val wiremockVersion = "3.13.1"
val jsonUnitVersion = "4.1.0"
val assertjVersion = "3.27.7"
val postgresVersion = "42.7.13"
val jacksonVersion = "2.21.5"

dependencies {
    // BlackboxStack is shared by the Cucumber suite and the Gatling simulation, so it lives in main.
    implementation("org.testcontainers:testcontainers:$testcontainersVersion")

    testImplementation(platform("io.cucumber:cucumber-bom:$cucumberVersion"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-spring")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation("org.junit.platform:junit-platform-suite")

    // DI for the step classes, and JdbcTemplate for read-only assertions. A plain Spring context -
    // never the application's context.
    //
    // cucumber-spring declares these two as `provided` scope, so they do not arrive transitively and
    // have to be declared here or the Cucumber Spring integration fails at runtime.
    testImplementation("org.springframework:spring-context:$springVersion")
    testImplementation("org.springframework:spring-context-support:$springVersion")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")

    testImplementation("io.rest-assured:rest-assured:$restAssuredVersion")
    // REST-assured needs a JSON serialiser present to send a Map as a request body, and Cucumber's
    // HTML report formatter needs Jackson 2 *including* the jdk8 module - it fails the whole run with
    // "Cucumber needs a JSON library to write reports" if only databind is present.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
    // The admin client only; the WireMock server itself runs as a container.
    testImplementation("org.wiremock:wiremock:$wiremockVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    // Compares JSON text, so the number scale is asserted rather than just the value.
    testImplementation("net.javacrumbs.json-unit:json-unit-assertj:$jsonUnitVersion")

    testRuntimeOnly("org.postgresql:postgresql:$postgresVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `-PdryRun` matches every step to its definition without executing anything: no image, no Docker.
// Undefined or ambiguous steps still fail, so it is a fast check that the features and the glue agree.
val cucumberDryRun = providers.gradleProperty("dryRun").map { it != "false" }.getOrElse(false)

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
    if (cucumberDryRun) {
        systemProperty("cucumber.execution.dry-run", "true")
    } else {
        // The image must exist before the stack can be composed from it.
        dependsOn(":dockerImage")
    }

    // `@wip` is for work in progress; `@known-bug` documents behaviour that is deliberately not
    // fixed yet. Both are excluded by default and both should be visible in review.
    systemProperty(
        "cucumber.filter.tags",
        providers.gradleProperty("tags").getOrElse("not @wip and not @known-bug")
    )

    // The suite runs with this module as its working directory, so the compose files at the
    // repository root are passed in explicitly rather than guessed at with relative paths.
    systemProperty("blackbox.composeFile", rootProject.file("compose.blackbox.yaml").absolutePath)

    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// The performance smoke test runs against the same image and stack as the Cucumber suite. It is a
// nightly job, not a merge gate: latency on shared CI runners is too noisy to block on.
dependencies {
    gatlingImplementation("org.wiremock:wiremock:$wiremockVersion")
}

tasks.named("gatlingRun") {
    dependsOn(":dockerImage")
}

// The suite's entire value is that it cannot see the application's code. That independence is easy
// to give away by accident - one `implementation(project(":"))` "to reuse the response type" and the
// suite starts agreeing with the thing it exists to check, at which point it can no longer catch a
// contract change. This is the enforcement, rather than a note in a README.
val verifyBlackboxIsolation = tasks.register("verifyBlackboxIsolation") {
    group = "verification"
    description = "Fails if the suite has acquired a dependency on the application's code."

    val classpaths = listOf("compileClasspath", "testCompileClasspath", "runtimeClasspath", "testRuntimeClasspath")
        .map { configurations.named(it) }

    doLast {
        val projectDependencies = classpaths
            .flatMap { it.get().incoming.resolutionResult.allComponents }
            .map { it.id }
            .filterIsInstance<org.gradle.api.artifacts.component.ProjectComponentIdentifier>()
            .map { it.projectPath }
            .filter { it != ":blackbox" }   // the module is always the root of its own graph
            .distinct()

        if (projectDependencies.isNotEmpty()) {
            throw GradleException(
                "The black-box suite must not depend on any project, but it depends on " +
                    "$projectDependencies. Assertions deliberately compare raw JSON rather than the " +
                    "application's types - restore that independence rather than relaxing this check."
            )
        }

        val applicationBytecode = classpaths
            .flatMap { it.get().files }
            .filter { file ->
                file.path.contains("classes/java/main") ||
                    file.name == "application.jar" ||
                    file.name.startsWith("quotes-")
            }

        if (applicationBytecode.isNotEmpty()) {
            throw GradleException(
                "The application's compiled code is on the black-box classpath: $applicationBytecode. " +
                    "The suite must reach the service over HTTP only."
            )
        }
        logger.lifecycle("  blackbox: no project dependency and no application bytecode on any classpath")
    }
}

tasks.check {
    dependsOn(verifyBlackboxIsolation)
}

