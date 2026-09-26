# 18 Performance testing basics

::: {.callout .covers}
This chapter covers

- Load, stress, spike and soak tests: four different questions
- Latency, throughput and why averages lie
- Open versus closed workload models
- A Gatling smoke test for the quote service
- Why a performance *smoke* test isn't a benchmark, and where it belongs in the pipeline
:::

**After this chapter you will be able to** pick the right kind of performance test for a question,
read a latency report properly, and write a small Gatling simulation that catches performance
pathologies early.

::: {.callout .recall}
Warm-up

1. Name the five rungs of the environment ladder. (section 17.1)
2. Why is `BlackboxStack` in the `blackbox` module's main code rather than its tests? (14.5)
3. What's an invariant? Give one for the quote service. (16.4)
:::

## 18.1 Performance is a question, not a test

"Do a performance test" is as vague as "do an integration test". Performance testing covers several
distinct questions, each with its own load *shape*.

![Figure 18.1a Load test: steady, expected traffic. Question: do we meet our targets at normal load?](images/18-load-constant.png)

![Figure 18.1b Stress test: keep increasing load. Question: where and how do we break?](images/18-load-stress.png)

![Figure 18.1c Spike test: sudden bursts. Question: do we survive and recover from sudden peaks?](images/18-load-spike.png)

![Figure 18.1d Soak test: normal load for hours. Question: do we degrade over time (leaks, growing queues)?](images/18-load-soak.png)

| Type | Question | Finds |
| --- | --- | --- |
| **Load** | At expected traffic, are latency and errors within targets? | Slow endpoints, N+1 queries, missing indexes |
| **Stress** | What's the breaking point, and how do we fail? | Capacity limits, ungraceful failure modes |
| **Spike** | Can we absorb a sudden burst and recover? | Cold caches, autoscaling lag, connection storms |
| **Soak** | Is behaviour stable over hours? | Memory leaks, connection leaks, log/disk growth |

On the four axes: scope **system**, visibility **black-box**, purpose **performance**, and the
environment is where it gets difficult — because performance results only mean something in an
environment that resembles production.

## 18.2 Measure the right thing

Two basic metrics:

- **Latency** (response time): how long one request takes.
- **Throughput**: how many requests per second the system completes.

They're linked. As load rises toward capacity, throughput flattens and latency climbs sharply as
requests start queueing.

And the most important lesson of this chapter: **don't summarise latency with an average.**

![Figure 18.2 The same endpoint's latency, summarised six ways. The mean hides the users who wait almost a second.](images/18-percentiles.png)

In figure 18.2, the mean is 120 ms — sounds fine. But the 99th percentile (p99) is 950 ms: one
request in a hundred takes nearly a second. If a page makes 20 such calls, most page loads include at
least one slow call. Averages blend the many fast requests with the few slow ones and hide exactly
the experience you care about.

Read latency as **percentiles**: p50 (the median, the typical request), p95 and p99 (the tail), and
max (the worst case, often noise). Performance targets should be written the same way: *"p95 below
300 ms, p99 below 800 ms, no errors, at 20 requests per second."*

::: {.callout .mental}
Mental model: users live in the tail

Your median user is fine. Your *complaints* come from the tail. And in a system of many calls, almost
every user eventually lands in someone's tail. Watch p95 and p99.
:::

## 18.3 Open and closed workloads

A subtle point that makes many load tests lie. There are two ways to generate load:

- **Closed model**: a fixed number of virtual users, each sending a request, waiting for the
  response, then sending the next. If the system slows down, users send *fewer* requests — so load
  drops exactly when the system struggles, and latency looks better than it would for real users.
- **Open model**: requests *arrive* at a set rate regardless of how the system is doing, like real
  users on the internet. If the system slows down, requests pile up — which is what really happens.

For a public HTTP API, the open model is usually the honest one. (The closed-model distortion is
related to what Gil Tene called *coordinated omission*: the load generator unintentionally stops
measuring the slowest periods.) Gatling supports both; `injectOpen(constantUsersPerSec(…))` is open.

## 18.4 A Gatling smoke test

The lab has a small Gatling simulation in the `blackbox` module, written in Gatling's Java DSL
so the team stays in one language.

```java
// Listing 18.1 QuoteSmokeSimulation (blackbox/src/gatling/java/.../perf/, imports omitted)
public class QuoteSmokeSimulation extends Simulation {

    static {
        BlackboxStack.ensureStarted();                                          // #1
        URI admin = URI.create(BlackboxStack.wiremockAdminUrl());
        new WireMock(admin.getHost(), admin.getPort()).register(get(urlPathEqualTo("/rates/WIDGET"))
                .willReturn(okJson("{\"productCode\":\"WIDGET\",\"annualPercentage\":4.25}")));
    }

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BlackboxStack.baseUrl())
            .contentTypeHeader("application/json")
            .acceptHeader("application/json");

    private final ScenarioBuilder createThenFetch = scenario("create then fetch")
            .exec(http("create quote")
                    .post("/api/v1/quotes")
                    .body(StringBody(session -> """
                            {"customerId":"perf-%s","productCode":"WIDGET","amount":1000.00,
                             "currency":"USD","termMonths":12}
                            """.formatted(UUID.randomUUID())))                   // #2
                    .check(status().is(201), jsonPath("$.quoteId").saveAs("quoteId")))
            .exec(http("fetch quote")
                    .get("/api/v1/quotes/#{quoteId}")                           // #3
                    .check(status().is(200)));

    {
        setUp(createThenFetch.injectOpen(
                rampUsersPerSec(1).to(20).during(Duration.ofSeconds(15)),      // #4
                constantUsersPerSec(20).during(Duration.ofSeconds(60))))
                .protocols(protocol)
                .assertions(                                                     // #5
                        global().failedRequests().percent().is(0.0),
                        global().responseTime().percentile(95.0).lt(300),
                        global().responseTime().percentile(99.0).lt(800));
    }
}
```

::: {.annotations}
1. Start the same compose stack as the Cucumber suite. It must happen before the fields are
   initialised, because `baseUrl` is read at construction time — a static initialiser guarantees
   that. The WireMock mapping is registered through the admin API, exactly as the Cucumber steps do.
2. A unique customer per virtual user, following chapter 15's isolation rule.
3. Gatling expression language: use the id saved from the previous response.
4. An **open** workload: ramp up (the JVM warms up; the JIT compiles hot code), then hold 20 new
   users per second for a minute.
5. Assertions turn the run into pass/fail: zero errors, p95 < 300 ms, p99 < 800 ms.
:::

Run it with `./gradlew :blackbox:gatlingRun` (the Gatling Gradle plugin adds a `gatling` source set
and that task). The HTML report shows percentiles over time, throughput, and errors per request.

::: {.callout .hood}
Under the hood: the classpath the compiler didn't check

The simulation's first nightly run crashed before sending a request:
`NoClassDefFoundError: org/testcontainers/…/WaitStrategy`. The `gatling` source set sees the module's
main *classes* (`BlackboxStack`) but not main's *dependencies* (Testcontainers). Compilation passed,
because the simulation never names a Testcontainers type itself — only `BlackboxStack` does, and it
was already compiled. The fix is one declaration in `blackbox/build.gradle.kts`:

```kotlin
configurations.named("gatlingImplementation") {
    extendsFrom(configurations.implementation.get())
}
```

A compile-only check can't see a runtime classpath gap. Only running the thing can — the same lesson
as the Dockerfile's missing launcher in chapter 14, one level down.
:::

Once fixed, a run on a standard GitHub runner looked like this:

| Metric | Value |
| --- | --- |
| Requests (create + fetch) | 2,714, none failed |
| Mean / p50 | 6 ms / 5 ms |
| p95 / p99 | 16 ms / 25 ms |
| Max | 347 ms |
| Assertions | 0% failed; p95 < 300 ms; p99 < 800 ms — all passed |

Notice the max: fourteen times the p99. A single outlier like that is typical of JIT warm-up, a
garbage-collection pause or a noisy neighbour on the runner — the report's latency-over-time chart
shows which. That's why the assertions sit on percentiles, never on the max, and why the injection
profile ramps up first.

## 18.5 A smoke test, not a benchmark

The simulation runs against containers on whatever machine executes it — a laptop or a shared CI
runner. Those numbers are **not** production numbers. Different CPU, no production-sized data,
noisy neighbours on the CI host. So what's it for?

It catches **pathologies**: the order-of-magnitude problems that show up at any scale.

- A query without an index that's fine with 10 rows and awful with 10,000 (the simulation creates
  thousands of quotes in a minute).
- A connection pool of size 1, or a leak that exhausts it.
- An accidental synchronous call to a slow dependency on every request.
- Timeouts misconfigured so that one slow dependency call blocks a thread for a minute.
- Errors that only appear under concurrency.

Those are worth catching every night. Precise capacity numbers ("we can handle 1,200 requests per
second per pod") need a production-like environment, production-like data, and dedicated runs — a
different exercise with different tooling budgets.

::: {.callout .myth}
Misconception: "Put the performance test in the PR pipeline so nobody makes it slower"

Latency on shared CI runners varies by tens of percent from run to run. A merge gate on p95 < 300 ms
will fail randomly — a flaky test by design — and teams will learn to ignore it. Run it nightly,
look at the trend, and gate only on things that are stable: error rate, and gross thresholds far
above normal.
:::

That's why the lab runs Gatling in a **nightly** workflow (`perf-smoke.yml`, also on manual dispatch), non-blocking,
with its report uploaded.

## 18.6 Making performance results trustworthy

A short checklist for when you do run serious performance tests:

- **Warm up** before measuring (JIT compilation, connection pools, caches).
- **Realistic data volume** — performance with an empty table means little.
- **Realistic mix** — the ratio of reads to writes, the distribution of request sizes.
- **Open workload** for internet-facing APIs.
- **Isolate the environment** — no one else deploying or load-testing at the same time.
- **Record the environment** — versions, instance sizes, configuration — alongside the results.
- **Compare against a baseline**, not against a feeling.

::: {.callout .tryit}
Try it: make it fail on purpose

Temporarily set the Hikari pool size to 1 in the image's environment
(`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 1` in `compose.blackbox.yaml`), and add an artificial
`Thread.sleep(50)` in `findQuote`. Run the simulation and read the report: how do p50, p95 and p99
change? Which assertion fails first? Revert both changes afterwards. *Needs a JDK and Docker.*
:::

::: {.callout .quiz}
Check your understanding

1. Match each question to a test type: "Do we leak memory?", "Where do we break?", "Can we survive
   Black Friday's opening minute?", "Are we fast enough on a normal day?"
2. Why is the mean a poor summary of latency?
3. Explain the difference between open and closed workload models, and why it matters.
4. Why does the smoke simulation start the stack in a static initialiser?
5. Why isn't the Gatling smoke test a merge gate?
:::

::: {.callout .summary}
Summary

- Performance testing is several questions: **load**, **stress**, **spike**, **soak**.
- Read latency as **percentiles** (p50, p95, p99); users live in the tail.
- Prefer an **open** workload model for internet-facing APIs; closed models hide slowdowns.
- The lab's **Gatling smoke** test reuses the black-box stack and catches **pathologies**, not
  capacity numbers.
- Performance numbers from shared CI are noisy: run **nightly**, watch trends, and gate only on stable
  signals.
:::
