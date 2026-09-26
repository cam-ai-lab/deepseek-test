# Appendix A — Lab setup

## A.1 What you need

| Tool | Why | Check |
| --- | --- | --- |
| A JDK, version 17 or newer | Runs the Gradle wrapper; Gradle then downloads the JDK 21 the build uses | `java -version` |
| Docker with Compose v2 | Integration tier (PostgreSQL container) and the black-box tier | `docker compose version` |
| A `docker-compose` executable | Testcontainers' compose support calls it by that name | `docker-compose version` |
| Git | Clone the lab | `git --version` |
| Optional: Python 3 + `uv` | Schemathesis (chapter 16) | `uv --version` |

On macOS, one straightforward way:

```bash
brew install --cask temurin@21     # a JDK
brew install --cask orbstack       # a lightweight Docker runtime (Docker Desktop also works)
```

If you already manage JDKs with SDKMAN!, make sure a JDK is active in the shell that runs Gradle
(`sdk use java <version>`); the wrapper can't see a JDK that is only loaded in interactive shells.

Docker Desktop and OrbStack install a `docker-compose` command alongside `docker compose`. On Linux
(and on GitHub's runners) you may only have the Compose v2 plugin; the lab's CI creates a two-line
shim, and you can do the same:

```bash
printf '#!/bin/sh\nexec docker compose "$@"\n' | sudo tee /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

## A.2 Running each tier

| Command | Runs | Needs Docker |
| --- | --- | --- |
| `./gradlew :test` | Unit tier (the leading `:` means "root project only") | No |
| `./gradlew integrationTest` | Integration tier | Yes |
| `./gradlew :blackbox:test` | Black-box tier: builds the image, starts the stack | Yes |
| `./gradlew :blackbox:test -PdryRun` | Checks every Cucumber step has a definition; runs nothing | No |
| `./gradlew :blackbox:test -Ptags=@known-bug` | Only the known-bug scenarios (they fail today) | Yes |
| `./gradlew :blackbox:gatlingRun` | Performance smoke | Yes |
| `./gradlew check` | Everything, plus guards and the coverage floor | Yes |
| `./gradlew :test --tests '*QuoteCalculatorTest'` | One class | Depends on tier |

Plain `./gradlew test` runs the `test` task in *every* project, including `:blackbox:test` — which
builds a Docker image. Use `:test` when you mean the unit tier.

To study Parts 1 and 2 against the code they describe, check out the tag: `git checkout
before-blackbox`. There, `./gradlew systemTest` runs the old in-JVM system tier.

Reports:

- Tests: `build/reports/tests/<suite>/index.html`
- Coverage: `build/reports/jacoco/test/html/index.html`
- Cucumber: `blackbox/build/reports/cucumber/index.html`
- Gatling: `blackbox/build/reports/gatling/`

The first run is slow: it downloads Gradle, a JDK 21, dependencies and the `postgres:17-alpine`
image. Later runs reuse all of them.

## A.3 Running the application by hand

```bash
docker compose up -d                 # PostgreSQL on localhost:5432
docker run -d -p 8081:8080 wiremock/wiremock:3.13.1    # a stand-in rate service
curl -X POST localhost:8081/__admin/mappings \
  -d '{"request":{"urlPath":"/rates/WIDGET"},
       "response":{"status":200,"jsonBody":{"annualPercentage":4.25}}}'
./gradlew bootRun                    # the service on localhost:8080

curl -s -X POST localhost:8080/api/v1/quotes -H 'Content-Type: application/json' \
  -d '{"customerId":"me","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}'
```

## A.4 Map of the lab

| Path | Chapter(s) |
| --- | --- |
| `src/main/java/.../quote/QuoteCalculator.java` | 5, 16 |
| `src/main/java/.../quote/QuoteService.java` | 1, 3, 4 |
| `src/main/java/.../rate/HttpRateGateway.java` | 10 |
| `src/main/resources/db/migration/V1__create_quotes.sql` | 9, 12, 16 |
| `src/test/.../QuoteCalculatorTest.java` | 5 |
| `src/test/.../QuotePricingRegressionTest.java` | 6 |
| `src/test/.../ArchitectureTest.java` | 7 |
| `src/integrationTest/.../QuoteControllerTest.java` | 8 |
| `src/integrationTest/.../QuoteRepositoryTest.java` | 9 |
| `src/integrationTest/.../HttpRateGatewayTest.java` | 10 |
| `src/systemTest/**` (tag `before-blackbox`) | 2, 6, 12 (replaced in 15) |
| `Dockerfile`, `compose.blackbox.yaml`, `docker/` | 14 |
| `blackbox/src/main/.../BlackboxStack.java` | 14, 18 |
| `blackbox/src/test/**` (steps, support, features) | 13, 15, 16 |
| `blackbox/src/gatling/**` | 18 |
| `.github/workflows/ci.yml`, `perf-smoke.yml` | 19 |
| `src/testFixtures/**` | 4, 9, 11 |
| `build.gradle.kts` | 11, 14 |
| `BLACKBOX-TEST-PLAN.md` | 12–15, 18, 19 |

## A.5 Building this book

The book's sources live in `book/` in the lab: Markdown chapters, mermaid diagrams, a stylesheet and
`build.sh`, which renders the diagrams with mermaid-cli and assembles the EPUB with pandoc.

```bash
cd book && ./build.sh
```
