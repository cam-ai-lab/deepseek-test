# 17 Where tests run

::: {.callout .covers}
This chapter covers

- The environment ladder: in-process, containers, ephemeral environments, staging, production
- What each rung can see that the one below can't, and what it costs
- Ephemeral (preview) environments, precisely
- Staging: its uses and its traps
- Testing in production: smoke tests and synthetic monitoring
- Reusing one scenario suite across several environments
:::

**After this chapter you will be able to** choose the cheapest environment in which a given risk
becomes visible, explain what an ephemeral environment adds over containers on a build machine, and
design tests that can safely run against shared and production environments.

::: {.callout .recall}
Warm-up

1. What does a black-box scenario test that a lower-tier test with the same logic doesn't? (section
   15.2, "black-box scenarios test configuration too")
2. What's a property, and name one for the quote service that needs system scope. (16.4)
3. System test versus ephemeral test: which axis separates them? (2.4)
:::

## 17.1 The ladder

The fourth axis of chapter 2 — environment and phase — deserves a chapter of its own, because this is
where test strategies most often go wrong: either everything waits for a shared staging environment,
or nothing ever runs anywhere production-like.

![Figure 17.1 The environment ladder. Each rung adds realism and costs speed and control.](images/17-env-ladder.png)

| Rung | What runs | Lifetime | Who controls it | Sees |
| --- | --- | --- | --- | --- |
| In-process | Your code in the test JVM | One test | The test | Logic, framework wiring |
| Containers | Real DB, stubs, maybe the app image | One run | The test run | Real tech, packaging, shipped config |
| Ephemeral env | The app *deployed*, per PR/branch | Minutes–days | CI/infra tooling | Deployment manifests, networking, platform config |
| Staging | Long-lived, shared, production-like | Permanent | Platform team | Integration with real neighbours, data volumes |
| Production | The real thing | Permanent | Operations | Real users, real data, real traffic |

Two rules follow from the table:

1. **Push each test down to the lowest rung where its risk is visible.** A rounding rule belongs
   in-process. A migration belongs in containers. A Kubernetes ingress rule can't be seen below an
   ephemeral environment.
2. **Each rung up needs fewer tests.** By the time you reach production you want a small number of
   read-only checks, not a regression suite.

::: {.callout .mental}
Mental model: realism is bought, not free

Every rung buys realism with speed, control and money. Ask of each test: *which specific thing does
this environment have that the rung below doesn't, and does my test need it?* If you can't name the
thing, run the test lower.
:::

## 17.2 Containers on the build machine

This is where the lab's integration tier and the new black-box tier live. It's the sweet spot for
most service teams: real PostgreSQL, the real image, fully controlled stubs, disposable, and runnable
on a laptop *and* in CI with the same command.

What it can't show: anything about **how the service is deployed**. The compose file is not your
Kubernetes manifest or your Terraform. Resource limits, service discovery, TLS termination, secrets
injection, network policies and the real rate service are all absent.

## 17.3 Ephemeral environments

An **ephemeral environment** (also called a *preview* or *review* environment) is the application
deployed — with the same tooling and manifests as production — into a short-lived, isolated space for
one pull request or branch, then destroyed.

Typical shape:

1. A PR is opened. CI builds the image and deploys it into a fresh namespace (Kubernetes) or stack
   (a PaaS such as Render, Railway or Vercel for front ends), with its own database.
2. Dependencies are either real shared test instances (a staging rate service) or stubs deployed
   alongside.
3. Automated black-box tests run against its URL; humans can click around it too.
4. The PR is merged or closed; the environment is deleted.

What it adds over containers on the build machine:

- **Deployment is tested**: manifests, Helm charts, environment variables as the platform injects
  them, health probes as the orchestrator uses them.
- **Real platform behaviour**: ingress, TLS, DNS, service mesh, autoscaling configuration.
- **A shareable URL** for product review and exploratory testing (chapter 16) *before* merge.

What it costs: infrastructure to build and maintain, cloud spend, minutes of provisioning per PR, and
a new class of flakiness (provisioning failures). For a small service with a simple deployment, it
often isn't worth it; for a platform with many services and complex routing, it's frequently the
single best investment in confidence.

::: {.callout .myth}
Misconception: "Ephemeral environments replace Testcontainers"

They answer different questions. Containers on the build machine tell you the *code and its
configuration* work, in seconds, anywhere. Ephemeral environments tell you the *deployment* works, in
minutes, in the cloud. A healthy pipeline uses containers for the bulk of black-box tests and an
ephemeral environment (if at all) for a thin layer of deployment checks.
:::

Because the lab's black-box suite takes its URLs from `BlackboxStack`, a small change would let the
same scenarios run against an ephemeral environment: if `BASE_URL` is set, skip starting compose and
use it. The plan deliberately starts without that switch — you add it when you need it.

## 17.4 Staging

**Staging** is a long-lived, shared, production-like environment. Its legitimate uses:

- Integrating with **real neighbours** that can't be stubbed well (payment sandboxes, identity
  providers).
- **Data-volume** and **migration rehearsal**: running the new migration against a production-sized
  copy.
- **Release rehearsal** for risky changes.

Its traps are well known:

- **Contention.** Many teams deploying to one place; your test fails because someone else's change
  is half-deployed.
- **Drift.** Staging configuration slowly diverges from production.
- **Unowned data.** Years of test data, in states no one understands.
- **The queue.** "Waiting for staging" becomes the bottleneck of the delivery process.

If you run black-box scenarios against staging, chapter 15's isolation design pays off: scenarios
that create their own uniquely identified data and assert only on it work on a busy shared database.
Scenarios that assume an empty database don't.

## 17.5 Production

Yes, you test in production — carefully.

- **Smoke tests after deploy.** A handful of fast, *safe* checks: health endpoint green, a
  read-only request succeeds, a known reference quote can be fetched. They answer "did the deploy
  work?" within a minute of it finishing, and can trigger an automatic rollback.
- **Synthetic monitoring.** The same kind of checks, run on a schedule (every minute) from outside,
  alerting when they fail. It's testing as monitoring: it catches failures caused by things that
  aren't deploys — an expired certificate, a dependency outage.
- **Observability-based verification.** Canary releases compared on error rates and latency; not
  tests in the classic sense, but they use the same idea — an automated judgement about correctness.

Production tests must be **safe**: read-only, or writing clearly marked synthetic data that is
excluded from reporting and cleaned up. A production smoke test that creates a real quote for a real
customer ID is an incident waiting to happen.

In Cucumber terms, the plan would tag a subset of scenarios `@smoke` — only those that are read-only
or use synthetic customers — and run `-Ptags=@smoke` against production after each deploy.

## 17.6 Putting the lab on the ladder

| Test | Rung | Why that rung |
| --- | --- | --- |
| Unit tests | In-process | Logic only |
| Web slice | In-process | Spring MVC, no I/O needed |
| JPA slice | Containers | Needs real PostgreSQL |
| Adapter test | In-process + local stub server | Needs real HTTP, not a real provider |
| Black-box Cucumber | Containers (app image) | Needs the shipped artefact and config |
| Gatling smoke | Containers | Needs the real image; numbers are indicative only |
| *(future)* Deployment checks | Ephemeral env | Only if deployment grows complex |
| *(future)* `@smoke` | Production, post-deploy | "Did the deploy work?" |

::: {.callout .tryit}
Try it: design a production smoke test

Pick two scenarios from chapter 15 that would be safe to run in production, and one that would be
dangerous. For the dangerous one, describe what you'd change (data, assertions, cleanup) to make a
production-safe version — or argue it shouldn't run there at all. *Discussion in appendix B.*
:::

::: {.callout .quiz}
Check your understanding

1. State the two rules that follow from the environment ladder.
2. What can an ephemeral environment test that containers on the build machine can't?
3. Name three traps of a shared staging environment.
4. What makes a production smoke test safe?
5. Why does per-scenario unique data make the same scenarios usable against staging?
:::

::: {.callout .summary}
Summary

- Environments form a **ladder**: in-process, containers, ephemeral, staging, production. Each rung
  adds realism and costs speed, control and money.
- Push each test to the **lowest rung where its risk is visible**; fewer tests on each rung up.
- **Ephemeral environments** test the *deployment*; containers test the *code and its config*. They
  complement each other.
- **Staging** is for real neighbours, data-volume rehearsal and release rehearsal — beware
  contention, drift and queues.
- **Production** gets safe smoke tests and synthetic monitoring, never a regression suite.
:::
