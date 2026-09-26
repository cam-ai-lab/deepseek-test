# 14 Building the black-box lab

::: {.callout .covers}
This chapter covers

- Packaging the application as a Docker image: the thing you actually ship
- A compose overlay: app, PostgreSQL and a WireMock container
- A SELECT-only database role for assertions
- Starting and stopping the stack from Java with Testcontainers
- The `blackbox` Gradle subproject, and wiring the build together
:::

**After this chapter you will be able to** stand up a production-shaped environment for tests with
one Gradle command, and explain why each piece of it exists.

::: {.callout .recall}
Warm-up

1. Which class in a cucumber-spring suite carries `@CucumberContextConfiguration`, and what does it
   configure? (section 13.5)
2. What are the three levels of test double? Which one does this chapter build? (4.4)
3. Why is a singleton container better than one per class? (9.4)
:::

## 14.1 The shape of the lab

![Figure 14.1 The black-box lab. The test JVM talks to a compose stack only over the network: HTTP to the app, HTTP to WireMock's admin API, and read-only SQL to PostgreSQL.](images/14-blackbox-arch.png)

Three containers and one test JVM. Every arrow crossing between them is a *network* connection.
That's the physical expression of black-box: there is no path from the tests into the application
except the ones a client or an operator has.

We'll build it bottom-up: image, compose files, database role, stack lifecycle, Gradle module.

## 14.2 The image

A black-box suite should test **the artefact you deploy**. For this service, that's a Docker image.
The lab builds it from Spring Boot's layered jar:

```dockerfile
# Listing 14.1 Dockerfile (repository root, comments abridged)
FROM eclipse-temurin:21-jre AS extract
WORKDIR /builder
COPY build/libs/application.jar application.jar                                          # 1
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted   # 2

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*                                                       # 3
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./                                  # 4
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER app                                                                                 # 5
EXPOSE 8080
HEALTHCHECK --interval=2s --timeout=2s --start-period=30s --retries=30 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]                                           # 6
```

::: {.annotations}
1. A fixed jar name. The build renames the boot jar to `application.jar` (listing 14.2), because
   `build/libs` also holds a `-plain.jar` and a wildcard `COPY` of two files fails.
2. Split the fat jar into layers: third-party dependencies change rarely; your classes change on
   every commit.
3. The health checks call `curl`, which the JRE base image doesn't include.
4. Copy the rarely changing layers first, so Docker's cache reuses them and rebuilds only the last.
5. Run as an unprivileged user. A test image should be as close to the production image as possible,
   and production images shouldn't run as root.
6. `java -jar`, *not* Spring Boot's `JarLauncher`. See the box below — this line is the story of the
   suite's first failed CI run.
:::

::: {.callout .hood}
Under the hood: the launcher that wasn't there

Since Spring Boot 3.3, `extract --layers` produces the **launcher-free** layout by default:
`application.jar` plus a `lib/` directory, started with `java -jar`. Only `extract --layers
--launcher` produces the older layout that includes Spring Boot's loader classes and is started with
`org.springframework.boot.loader.launch.JarLauncher`. The lab's first Dockerfile mixed the two — the
new extraction with the old entrypoint — so the `spring-boot-loader` layer was empty and the container
died instantly with `ClassNotFoundException: JarLauncher`. Every unit and integration test was green.
Only a test of the *packaged artefact* could see it: the black-box tier's first finding was about
packaging, before it had checked a single business rule.
:::

The build names the jar and builds the image:

```kotlin
// Listing 14.2 The jar name and the image task (root build.gradle.kts)
tasks.bootJar {
    archiveFileName.set("application.jar")                                    // #1
}

val dockerImage = tasks.register<Exec>("dockerImage") {
    group = "build"
    description = "Builds the quotes:blackbox image from the current boot jar."
    dependsOn(tasks.bootJar)
    workingDir = layout.projectDirectory.asFile
    commandLine("docker", "build", "-t", "quotes:blackbox", ".")
    inputs.file("Dockerfile")
    inputs.files(tasks.bootJar.map { it.outputs.files })
    outputs.upToDateWhen { false }                                             // #2
}
```

::: {.annotations}
1. Renaming the boot jar is simpler than disabling the plain `jar` task — which, it turns out, breaks
   `java-test-fixtures`, because the fixtures resolve the main classes through that task.
2. The output is an image tag, not a file, so Gradle can't tell whether it's current. The task always
   runs; Docker's layer cache makes an unchanged rebuild cheap.
:::

Notice what the image does *not* contain: test code, test configuration, special profiles. It is
configured purely through environment variables, just as a deployment would configure it. If the
black-box suite needs the app to behave differently, the only lever is its environment — which is
exactly the lever operators have in production.

## 14.3 The compose file

The repository already has `compose.yaml` for local development: a PostgreSQL container that
publishes port 5432 so `./gradlew bootRun` can reach it. The plan called for an **overlay** — a
second file merged on top of it. The implementation uses a **standalone** file instead, for a reason
worth knowing about Compose:

- Compose merges `ports` lists by *appending*. An overlay can add ports but can't remove the dev file's
  `5432:5432`, so a black-box run would collide with any developer's database already running.
- Compose's `!reset` tag can clear a list, but not every tool that parses compose files understands
  custom YAML tags.

So `compose.blackbox.yaml` stands alone and publishes **no host ports at all**. The price is that the
PostgreSQL service is described in two files, which must be kept in step.

```yaml
# Listing 14.3 compose.blackbox.yaml (comments abridged)
services:
  postgres:
    image: postgres:17-alpine                                         # 1
    environment:
      POSTGRES_DB: quotes
      POSTGRES_USER: quotes
      POSTGRES_PASSWORD: quotes
    volumes:
      - ./docker/postgres-init:/docker-entrypoint-initdb.d:ro         # 2
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U quotes -d quotes"]
      interval: 2s
      timeout: 2s
      retries: 30

  wiremock:
    image: wiremock/wiremock:3.13.2                                   # 3
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/__admin/health"]
      interval: 2s
      timeout: 2s
      retries: 30

  app:
    image: quotes:blackbox                                            # 4
    depends_on:
      postgres: { condition: service_healthy }
      wiremock: { condition: service_healthy }
    environment:                                                      # 5
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/quotes
      SPRING_DATASOURCE_USERNAME: quotes
      SPRING_DATASOURCE_PASSWORD: quotes
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: org.postgresql.Driver
      APP_RATE_BASE_URL: http://wiremock:8080
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health/readiness"]
      interval: 2s
      timeout: 2s
      retries: 60
      start_period: 30s
```

::: {.annotations}
1. The same image, database name and credentials as `compose.yaml` — keep them in step.
2. Scripts in this directory run once, when the database is first created — before the app (and
   Flyway) ever connects.
3. The same WireMock version the integration tier uses as a library, now as a container.
4. The image from listing 14.1.
5. Spring Boot's relaxed binding maps `APP_RATE_BASE_URL` to `app.rate.base-url`. Inside the compose
   network, services find each other by name: `postgres`, `wiremock`.
:::

::: {.callout .hood}
Under the hood: why no host ports?

Fixed host ports would clash with a developer's running stack — or with a second build on the same CI
machine (chapter 3's "environment" source of flakiness). Testcontainers reaches each service through
its own randomly mapped port, so the compose file doesn't need to publish anything.
:::

## 14.4 A read-only role for assertions

The plan allows steps to *read* the database, for the rare facts the API can't show ("nothing was
stored when the rate service failed"), but never to write. Rather than trusting everyone to
remember, we make writing impossible:

```sql
-- Listing 14.4 docker/postgres-init/01-blackbox-reader.sql
CREATE ROLE blackbox_reader LOGIN PASSWORD 'blackbox_reader';
GRANT CONNECT ON DATABASE quotes TO blackbox_reader;
GRANT USAGE ON SCHEMA public TO blackbox_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE quotes IN SCHEMA public
    GRANT SELECT ON TABLES TO blackbox_reader;                        -- 1
```

::: {.annotations}
1. The `quotes` table doesn't exist yet when this runs — Flyway creates it later, as the `quotes`
   user. *Default privileges* say "any table `quotes` creates in future is readable by
   `blackbox_reader`". Without this line, the reader would have no access to anything.
:::

This is the "make the wrong thing impossible" principle from chapter 11 applied at the database
level. A step that tries `INSERT` or `DELETE` fails with a permission error — enforced by
PostgreSQL, not by code review.

::: {.callout .mental}
Mental model: grey-box on a leash

Reading the database makes these tests technically grey-box for those assertions. That's a
deliberate, bounded exception: **read-only**, only in **Then** steps, only for facts the API can't
expose. The API remains the only way to *change* the system. Keep the leash short, or the suite
drifts back into testing internals.
:::

## 14.5 Starting the stack from Java

Developers shouldn't have to remember `docker compose up` before running tests, and CI shouldn't need
a separate script. Testcontainers' `ComposeContainer` starts a compose stack from Java and waits for
it to be ready.

```java
// Listing 14.5 BlackboxStack (blackbox/src/main/java/.../stack/BlackboxStack.java, abridged)
public final class BlackboxStack {

    private static ComposeContainer stack;

    public static synchronized void ensureStarted() {                                 // #1
        if (stack == null) {
            start(composeFile());                                                     // #2
        }
    }

    private static void start(File composeFile) {
        ComposeContainer container = new ComposeContainer(composeFile)                 // #3
                .withPull(false)                                                      // #4
                .withExposedService(APP, APP_PORT,
                        Wait.forHttp("/actuator/health/readiness")                    // #5
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withExposedService(WIREMOCK, WIREMOCK_PORT,
                        Wait.forHttp("/__admin/health").forStatusCode(200))
                .withExposedService(POSTGRES, POSTGRES_PORT)
                .withRemoveVolumes(true)
                .withLogConsumer(APP, BlackboxStack::log)                             // #6
                .withLogConsumer(WIREMOCK, BlackboxStack::log);
        try {
            container.start();
        }
        catch (RuntimeException ex) {
            dumpLogs();                                                               // #7
            throw ex;
        }
        stack = container;
        Runtime.getRuntime().addShutdownHook(new Thread(BlackboxStack::stopQuietly));
    }

    public static String baseUrl() {
        ensureStarted();
        return "http://" + stack.getServiceHost(APP, APP_PORT) + ":"
                + stack.getServicePort(APP, APP_PORT);                                // #8
    }
    // ... wiremockAdminUrl(), jdbcUrl(), composeFile(), dumpLogs()
}
```

::: {.annotations}
1. The singleton pattern from chapter 9: started once, lazily, shared by everything in the JVM.
2. Found from a system property the Gradle task sets, or by walking up from the working directory — so
   the Gatling simulation and an IDE run find it too.
3. `ComposeContainer` is Testcontainers' **Compose v2** support. It runs `docker compose` inside a small
   helper container, so the host needs Docker but no separate `docker-compose` binary.
4. By default it pulls every image first. `quotes:blackbox` exists only in the local Docker daemon,
   so a pull would look for it on Docker Hub and fail.
5. Wait for Spring Boot's *readiness* probe (the lab enables probes in `application.yml`), not merely
   an open port. An open port means Tomcat is listening; readiness means the app finished starting,
   including Flyway.
6. Container output streams into the build log while the stack runs.
7. If start-up fails, print every compose container's logs — found by the label Compose puts on each
   container, because the project name is random (see section 14.7).
8. Mapped host and port, discovered at run time. Nothing is hard-coded.
:::

A Cucumber hook starts it before the first scenario:

```java
// Listing 14.6 Starting the stack (blackbox/src/test/java/.../steps/Hooks.java, abridged)
public class Hooks {

    @Autowired
    private RateStub rateStub;

    @BeforeAll
    public static void startStack() {
        if (!Boolean.getBoolean("cucumber.execution.dry-run")) {                     // #1
            BlackboxStack.ensureStarted();
        }
    }

    @Before
    public void resetStubs() {
        this.rateStub.reset();                                                        // #2
    }
}
```

::: {.annotations}
1. Cucumber runs `@BeforeAll` hooks even in a dry run, which needs no stack (chapter 15).
2. Stubs are global to the WireMock container, so every scenario starts from a clean slate.
:::

Why is `BlackboxStack` in `src/main` of the `blackbox` module rather than in its tests? Because the
Gatling performance simulation (chapter 18) needs the same stack. Putting it in the module's main
code lets both the Cucumber tests and the Gatling source set depend on it.

![Figure 14.2 The lifecycle of one black-box run, from image build to teardown.](images/14-lifecycle.png)

## 14.6 The `blackbox` Gradle module

A separate subproject makes black-box-ness a property of the build: its classpath simply doesn't
contain the application.

```kotlin
// Listing 14.7 settings.gradle.kts
rootProject.name = "quotes"
include("blackbox")
```

```kotlin
// Listing 14.8 blackbox/build.gradle.kts (abridged)
plugins {
    java
    id("io.gatling.gradle") version "3.15.1.3"                               // #1
}

val cucumberVersion = "8.0.2"
val testcontainersVersion = "2.0.5"
val springVersion = "6.2.11"                                                 // #2

dependencies {
    implementation("org.testcontainers:testcontainers:$testcontainersVersion")   // BlackboxStack

    testImplementation(platform("io.cucumber:cucumber-bom:$cucumberVersion"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-spring")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("org.springframework:spring-context:$springVersion")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.rest-assured:rest-assured:5.5.6")
    testImplementation("org.wiremock:wiremock:3.13.2")                        // admin client only
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.postgresql:postgresql:42.7.13")
    // Deliberately NO project(":") dependency.                               // #3
}

val cucumberDryRun = providers.gradleProperty("dryRun").map { it != "false" }.getOrElse(false)

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
    if (cucumberDryRun) {
        systemProperty("cucumber.execution.dry-run", "true")                  // #4
    } else {
        dependsOn(":dockerImage")
    }
    systemProperty("cucumber.filter.tags",
        providers.gradleProperty("tags").getOrElse("not @wip and not @known-bug"))   // #5
    systemProperty("blackbox.composeFile", rootProject.file("compose.blackbox.yaml").absolutePath)
}
```

::: {.annotations}
1. The Gatling plugin adds the `gatling` source set and the `gatlingRun` task (chapter 18).
2. Explicit versions rather than importing Spring Boot's BOM: Boot 4's BOM pins a newer Spring and
   JUnit than the Cucumber integration is built for, and two version authorities over the same jars is
   a recipe for confusing classpath errors. The suite is a separate program; it gets its own versions.
3. The crucial absence. A step can't import `QuoteResponse` because it isn't on the classpath.
   Chapter 15 adds a guard so nobody "fixes" that.
4. `-PdryRun` matches steps to definitions without building the image or starting anything.
5. `./gradlew :blackbox:test -Ptags=@known-bug` runs just the known bugs; the default excludes them.
:::

And in the root build, `check` includes the new tier so `./gradlew check` still means "everything":

```kotlin
tasks.check { dependsOn(":blackbox:check") }
```

## 14.7 The first CI runs

The suite was written on a machine without Docker, so continuous integration was its first real
execution. It took seven failed CI runs before a single scenario executed. The four failures below
are the instructive ones (the others were dependency wiring), and each is a lesson worth more than
a paragraph of theory, so here they are in order.

| Symptom in CI | Actual cause | What would have caught it earlier |
| --- | --- | --- |
| `Local Docker Compose not found` | Testcontainers' old compose class shells out to a `docker-compose` binary; runners ship only the `docker compose` plugin | Running once locally with the CI's Docker setup |
| `Aborting attempt to link to container …_app_1 as it is not running` | The image's entrypoint named `JarLauncher`, which the new layered extraction doesn't include — the app died instantly (section 14.2) | Running the built image by hand: `docker run quotes:blackbox` |
| The log dump printed **nothing** | It ran `docker compose logs` without Testcontainers' random project name, so it inspected a project that didn't exist | Testing the diagnostic path deliberately, not only the happy path |
| Same "not running" error, app log shows it **started** | The old `DockerComposeContainer` looks for Compose v1 names (`project_app_1`); Compose v2 names them `project-app-1` | Knowing which class supports which Compose version |

Notice the second and fourth rows: *the same error message, with two completely different causes*.
Only the container logs told them apart — which is why fixing the log dump (third row) came before
fixing anything else. Then, once the stack started, the undefined steps of chapter 15 surfaced.

::: {.callout .mental}
Mental model: fix your eyes before you fix the bug

When a failure is opaque, the first fix is to the *diagnostics*, not to the suspected cause. A black-box
suite runs code you can't step through in a debugger; its logs are your debugger. Guessing at causes
without them produces commits like "try the compose plugin instead", "use per-service wait strategies",
"split the context configuration" — each plausible, none addressing the actual fault.
:::

::: {.callout .tryit}
Try it: bring the stack up by hand

Before changing any of the Java, prove the environment works on your machine: `./gradlew dockerImage`,
then `docker compose -f compose.blackbox.yaml up -d --wait`, then `docker compose -f
compose.blackbox.yaml ps` to see every service healthy. The file publishes no ports, so to call the app
use `docker compose -f compose.blackbox.yaml exec app curl -s localhost:8080/actuator/health`. Finally
`docker compose -f compose.blackbox.yaml down -v`. *Needs a JDK and Docker.*
:::

::: {.callout .quiz}
Check your understanding

1. Why should a black-box suite test the Docker image rather than `./gradlew bootRun`?
2. Why is the Gradle `dockerImage` task careful to pass one specific jar?
3. What would happen without the `ALTER DEFAULT PRIVILEGES` line in listing 14.4?
4. Why wait for `/actuator/health/readiness` rather than for the port to open?
5. Where does "black-box" get enforced in this design? Name two places.
6. Two CI failures had the same error message and different causes. What told them apart?
:::

::: {.callout .summary}
Summary

- Test **the artefact you ship**: a layered Docker image, configured only by environment variables.
- A **compose overlay** reuses the dev stack and adds the app and a WireMock container; services find
  each other by name inside the compose network.
- A **SELECT-only role** with default privileges makes read-only assertions possible and writes
  impossible.
- **Testcontainers `ComposeContainer`** starts the stack once per JVM, waits on real readiness, and
  exposes mapped ports.
- A separate **`blackbox` Gradle module** with no dependency on the application makes sharing code
  with the system under test impossible by construction.
:::
