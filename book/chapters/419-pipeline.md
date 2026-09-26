# 19 The pipeline

::: {.callout .covers}
This chapter covers

- Designing CI jobs around feedback time and failure isolation
- Deciding what blocks a merge, what runs nightly, and what runs after deploy
- The lab's two-job pipeline plus a nightly performance run
- A flaky-test policy: detection, quarantine, ownership, deadlines
- Reports and artefacts that make a red build quick to diagnose
:::

**After this chapter you will be able to** lay out a pipeline for a service's whole test portfolio,
justify which tests gate merges, and run a flaky-test policy that keeps the suite trustworthy.

::: {.callout .recall}
Warm-up

1. Why isn't the Gatling smoke test a merge gate? (section 18.5)
2. What makes a production smoke test safe? (17.5)
3. Name the four FIRST properties other than "Fast". (3.2)
:::

## 19.1 The pipeline is a feedback system

Chapter 1 said a suite's value depends on **speed** and **trust**. The CI pipeline is where both are
won or lost for the whole team. Three design goals:

1. **Fast signal on every change.** The checks that block a merge should finish in minutes.
2. **Clear failure location.** When something is red, the job name should already tell you roughly
   what kind of problem it is.
3. **Every red means something.** A pipeline that's sometimes red for no reason trains people to
   ignore red.

## 19.2 What blocks a merge

A useful sorting rule: **a check blocks merges if it is fast enough, deterministic enough, and about
this change.**

| Check | Fast? | Deterministic? | About this change? | Where |
| --- | --- | --- | --- | --- |
| Unit + architecture | Seconds | Yes | Yes | PR, blocking |
| Integration (slices, containers) | Minutes | Yes (designed to be) | Yes | PR, blocking |
| Coverage floor, classpath/context guards | Seconds | Yes | Yes | PR, blocking |
| Black-box Cucumber | Minutes | Yes (designed to be) | Yes | PR, blocking |
| Gatling smoke | Minutes | **No** (latency noise) | Mostly | Nightly, non-blocking |
| Fuzzing / property runs at system scope | Long | Seeded, but findings need triage | Partly | Nightly |
| Dependency vulnerability scan | Fast | Changes as advisories appear | **No** | Scheduled + PR (warn) |
| Production smoke | Seconds | Yes | About the deploy | After deploy, triggers rollback |

The vulnerability-scan row is instructive: it's fast and important, but its result can change
without any change to your code (a new advisory is published overnight). Blocking PRs on it means an
unrelated PR goes red on Tuesday morning. Many teams run it on a schedule, open tickets automatically,
and block only on new vulnerabilities *introduced by the PR*.

::: {.callout .mental}
Mental model: a merge gate is a promise

A blocking check promises: "if I'm red, *your change* broke something." Any check that can't keep
that promise — because it's noisy, slow or depends on the outside world — belongs somewhere else in
the pipeline, where its result is still seen but doesn't hold unrelated work hostage.
:::

## 19.3 The lab's pipeline

![Figure 19.1 The planned pipeline: two parallel jobs gate every PR; a nightly job runs performance and reports without blocking.](images/19-pipeline.png)

Before the migration the lab had one job, `./gradlew build`, running every tier. It now has two:

```yaml
# Listing 19.1 .github/workflows/ci.yml (abridged)
jobs:
  unit-and-integration:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew check -x :blackbox:test                     # 1
      - name: Check every Cucumber step has a definition
        run: ./gradlew :blackbox:test -PdryRun -Ptags="not @wip"   # 2
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: unit-and-integration-reports, path: build/reports/ }

  blackbox:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew :blackbox:test                              # 3
      - uses: actions/upload-artifact@v7
        if: always()
        with:
          name: blackbox-reports
          path: |
            blackbox/build/reports/cucumber/
            blackbox/build/test-results/
      - name: Known bugs still reproduce                           # 4
        if: success()
        run: |
          ./gradlew :blackbox:test -Ptags=@known-bug || true
          # ...counts failures in the Cucumber report; warns if any known bug now passes
```

::: {.annotations}
1. Everything except the black-box tier, including coverage and the build guards.
2. A Cucumber dry run: every step matched to a definition, no Docker needed. A feature/glue mismatch
   fails here in seconds instead of after the black-box job has built and started the stack.
3. Builds the image and runs the scenarios. Runs **in parallel** with job 1, so total wall-clock time
   is the slower of the two, not the sum.
4. Informational, never blocking: the known-bug scenarios should *fail*. The step reports how many
   still reproduce and warns when one starts passing — the signal to delete its tag.
:::

Both jobs are marked **required** in the repository's branch protection. A separate `perf-smoke.yml`
workflow runs `./gradlew :blackbox:gatlingRun` on a nightly `schedule` and on `workflow_dispatch`,
uploading the Gatling report.

Why two jobs rather than one? **Failure isolation** (a red `blackbox` job points at the assembled
product, not at a unit), **parallelism**, and **independent retries of infrastructure hiccups** —
if Docker on one runner has a bad day, you re-run one job, not the whole build.

::: {.callout .hood}
Under the hood: why the compose logs matter

When a black-box scenario fails, the test report tells you *what the client saw* — say, a 500. It
can't tell you *why*: the stack trace is in the application's log, inside a container that's gone by
the time you look. A `docker compose logs` step after the tests won't help either: Testcontainers
gives the stack its own random project name and tears it down when the test JVM exits.

The lab's suite does two things instead. While the stack runs, `ComposeContainer` log consumers
stream the app's and WireMock's output into the test log, which Gradle prints and CI keeps. And if the
stack fails to start, `BlackboxStack.dumpLogs()` finds every compose container by the label Compose
puts on it (`com.docker.compose.service`), including ones that already exited, and prints their logs:

```java
String ids = run("docker", "ps", "-a", "-q", "--filter", "label=com.docker.compose.service=" + service);
// then `docker logs --tail 200 <id>` for each
```

That dump is what finally explained the suite's first CI failures (chapter 14.7). For black-box
suites, this output is often the difference between a five-minute and a two-hour diagnosis.
:::

## 19.4 Flaky tests: a policy, not a feeling

A **flaky test** passes and fails on the same code. Chapter 3 listed the causes; this section is
about what a *team* does about them. Without a policy, flaky tests follow a predictable path: people
re-run, then re-run by reflex, then real failures get re-run too.

![Figure 19.2 The lifecycle of a flaky test under a quarantine policy. Every path out of quarantine goes through a fix or a deletion.](images/19-flaky.png)

A workable policy:

1. **Detect.** Track tests that fail and then pass on re-run of the same commit. Many CI systems and
   test-reporting tools do this automatically; otherwise a small script over JUnit XML history does.
2. **Quarantine quickly.** A confirmed flaky test is moved out of the blocking path — tagged
   `@Tag("quarantine")` in JUnit or `@quarantine` in Cucumber and excluded from the required jobs —
   *within a day*. Blocking everyone on a coin toss costs more than temporarily losing one check.
3. **Own it.** Quarantine opens a ticket with an owner. A quarantined test still runs in a
   non-blocking job, so its history keeps accumulating evidence.
4. **Deadline.** If it isn't fixed within an agreed period (two weeks is common), it's deleted —
   deliberately, with a note of what risk is now uncovered. A test nobody will fix is giving false
   comfort.
5. **Measure.** Track the size of quarantine. A growing quarantine is a signal about the suite's
   design (shared state, timing assumptions), not just about individual tests.

::: {.callout .myth}
Misconception: "Automatic retries make flakiness go away"

Retrying failed tests automatically (Gradle's test-retry plugin, Cucumber's rerun) can be a
reasonable *stopgap* for infrastructure noise. But if retries are silent, flakiness becomes
invisible — and some of it is real concurrency bugs in production code. If you retry, **report every
retry**, and feed retried tests into the same quarantine process.
:::

## 19.5 Keeping the pipeline fast as it grows

Techniques in rough order of payoff:

- **Fix the expensive things first**: Spring contexts (chapter 8), container starts (chapter 9), image
  builds (layer caching, chapter 14).
- **Parallelise jobs** (as above) before parallelising tests within a job.
- **Build caching**: the lab enables Gradle's build cache (`org.gradle.caching=true`), so unchanged
  test tasks can be restored rather than re-run.
- **Test selection**: run only tests affected by the change. Powerful in large monorepos, but it
  needs accurate dependency information; wrong selection means missed failures. Always run
  everything on the main branch.
- **Split slow suites into shards** across runners.

## 19.6 Reports people actually read

A red pipeline should answer three questions without anyone opening an IDE:

1. **Which check failed?** — job names that name the tier (`unit-and-integration`, `blackbox`).
2. **What failed?** — test names that describe behaviour (chapter 3), Cucumber scenarios in plain
   words, full exception output (`exceptionFormat = FULL` in the lab's build).
3. **Why?** — the artefacts: HTML test reports, the Cucumber report, the captured container logs, the Gatling
   report, coverage.

::: {.callout .tryit}
Try it: classify your own pipeline

For your team's pipeline (or the lab's), list every check and fill in the table from section 19.2:
fast? deterministic? about this change? Then mark any check that's in the wrong place. *No tools
needed.*
:::

::: {.callout .quiz}
Check your understanding

1. What three properties should a check have to block merges?
2. Why split the lab's build into two parallel jobs?
3. Why capture the containers' logs, and why can't a `docker compose logs` step after the tests do it?
4. Describe the five steps of a flaky-test policy.
5. When are automatic retries acceptable, and what condition must come with them?
:::

::: {.callout .summary}
Summary

- The pipeline's job is **fast, clearly located, trustworthy** feedback.
- A check blocks merges only if it's **fast, deterministic, and about the change**. Others run
  nightly, on a schedule, or after deploy.
- The lab's plan: **two parallel required jobs** (unit+integration, black-box) and a **nightly**,
  non-blocking Gatling run.
- For black-box failures, capture the **application's logs** as an artefact.
- Flaky tests need a **policy**: detect, quarantine, own, deadline, measure. Retries must be visible.
:::
