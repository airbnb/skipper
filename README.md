<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="web/public/brand/skipper-horiz-white.svg">
  <img src="web/public/brand/skipper-horiz-color.svg" alt="Skipper" width="320">
</picture>

**Durable workflow execution for the JVM — a library, not a cluster.**

Skipper is a lightweight workflow engine. Write business processes as ordinary
Kotlin or Java code, and let Skipper guarantee they run to completion.

[**Documentation**](https://skipper.airbnb.tech/) · [Quickstart](https://skipper.airbnb.tech/docs/quickstart/) · [Examples](https://skipper.airbnb.tech/examples/) · [Releases](https://github.com/airbnb/skipper/releases)

<a href="https://skipper.airbnb.tech/"><img src="https://img.shields.io/badge/docs-skipper.airbnb.tech-2a3990" alt="Documentation"></a>
<a href="https://central.sonatype.com/artifact/com.airbnb.skipper/skipper-core"><img src="https://img.shields.io/maven-central/v/com.airbnb.skipper/skipper-core?color=2a3990&label=Maven%20Central" alt="Maven Central"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-2a3990" alt="License: Apache 2.0"></a>
<img src="https://img.shields.io/badge/JVM-8%2B-2a3990" alt="JVM 8+">
<img src="https://img.shields.io/badge/Jackson-2.9_to_2.22-2a3990" alt="Jackson 2.9 to 2.22">
<img src="https://img.shields.io/badge/API-Kotlin_%26_Java-f15b29" alt="Kotlin and Java">
<img src="https://img.shields.io/badge/status-pre--release-f15b29" alt="Pre-release">

</div>

---

Some workflows require **durable execution**: once started, they must be guaranteed to reach a
terminal state. A payment. An order. A multi-step approval. Skipper makes that guarantee hold in
spite of crashes, deploys, and flaky downstreams.

Skipper is a library you add to a service you already run. There is no control plane to stand up,
no cluster to keep alive, and no new datastore to own — workflow state is checkpointed to the
database you already have.

## A workflow is just a class

```kotlin
class CheckoutWorkflow : Workflow() {
  private val payments = actions<PaymentActions>()
  private val email = actions<EmailActions>()

  @StateField var reviewCleared: Boolean? = null

  @WorkflowMethod
  suspend fun checkout(order: Order): Receipt {
    if (order.total > 1_000) {
      // Hibernate until a human decides — up to a day — holding no thread and
      // polling nothing. Survives deploys and restarts while it waits.
      val reviewed = waitUntil({ reviewCleared != null }, Duration.ofDays(1))
      if (!reviewed || reviewCleared != true) return Receipt.declined(order)
    }

    // Checkpointed once it completes, so a resume skips it. Delivery is at-least-once —
    // keep actions idempotent.
    val charge = payments.charge(order)
    // Retried on failures you classify as transient, per the retry policy you set.
    email.sendReceipt(order, charge)
    return Receipt(charge.id)
  }

  // Wakes the waiting workflow from the outside: an API handler, a queue consumer, a human.
  @SignalMethod
  fun clearReview(cleared: Boolean) {
    reviewCleared = cleared
  }
}
```

No retry loop, no state machine, no queue plumbing, and no scheduled job of your own polling for
approvals. Completed steps are checkpointed, so a resumed workflow picks up where it stopped.

Starting one is a call from your API handler, a queue consumer, anywhere:

```kotlin
val factory = runtime.workflowFactory.get()

// The workflow id is the idempotency key: the same id never starts a second instance,
// it joins the one already running.
val checkout = factory(CheckoutWorkflow::class.java, "order-${order.id}")
val receipt = checkout.checkout(order)

// Later, from another request — the same id hands you the live instance to signal.
factory(CheckoutWorkflow::class.java, "order-${order.id}").clearReview(true)
```

## How it works

- **Your replicas are the workers.** Every replica of your service runs Skipper's scheduler.
  There is no worker fleet to deploy and no task queue to size: work is claimed from the shared
  store by lease, so replicas need no coordination with each other.
- **State is a snapshot, not an event log.** A workflow's state and each action's result live in
  a row, which is why they should stay small.
- **Workflow methods must be deterministic.** Skipper re-runs the method and skips the steps it
  has already checkpointed, so the code has to take the same path twice — clocks, randomness,
  and I/O belong in actions, not in the workflow.

→ [Core Concepts](https://skipper.airbnb.tech/docs/core-concepts/)

## Why Skipper

- **Your storage, your rules.** Workflow state persists behind pluggable interfaces: an embedded
  SQLite backend in the box for zero-setup starts, MySQL for a shared production store, and room
  for your own.
- **Long waits cost nothing.** A workflow can hibernate for a day or a week awaiting a signal or
  an approval while holding no thread, then resume exactly where it paused.
- **You can see what is running.** A built-in admin UI — one JAX-RS resource you register with
  your existing HTTP layer — lists live instances, their state, and their history.

## Getting started

Skipper is on Maven Central as `com.airbnb.skipper:skipper-core`. The badge above shows the
latest release; the snippets below pin the current one.

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.airbnb.skipper:skipper-core:0.6.3")
}
```

<details>
<summary>Gradle (Groovy) and Maven</summary>

```groovy
// build.gradle
implementation 'com.airbnb.skipper:skipper-core:0.6.3'
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.airbnb.skipper</groupId>
  <artifactId>skipper-core</artifactId>
  <version>0.6.3</version>
</dependency>
```

</details>

Skipper is built against Jackson 2.9 and tested on every push against the newest Jackson 2.x as well, so
it runs on whichever Jackson your service already has.

Then wire it up once at startup:

```kotlin
// In main(), or wherever your service wires up its singletons on startup.
val config = SkipperConfig.forService("my-service")   // embedded SQLite, in-memory: nothing else to set up
val runtime = SkipperRuntime(config)

// Start the scheduler once — it is what drives workflows forward. Stop it on shutdown.
runtime.skipperSchedulerManager.get().start()
```

That default is for getting started, local runs, and tests. Build the runtime once and hold it
for the life of the process — it is what hands you the workflow factory. For production, point the
store at a database you already run:

```kotlin
val config = SkipperConfig.forService("my-service").apply {
  workflowStore = MySqlWorkflowStore.Factory()
  scheduler = MySqlScheduler.Factory()
  mySqlDataSource = dataSource
}
```

MySQL needs Skipper's schema to exist: apply the bundled Flyway migrations to that database once
before the first run, since Skipper does not run them for you there. For a single node that only
needs to survive restarts, a SQLite file is enough, and it bootstraps its own schema:

```kotlin
val config = SkipperConfig.forService("my-service").apply {
  workflowStore = SqliteWorkflowStore.Factory("skipper.db")
  scheduler = SqliteScheduler.Factory("skipper.db")
}
```

Your own store implementation is likewise a config change: see
[Storage Backends](https://skipper.airbnb.tech/docs/storage/).

**Read next:** [Quickstart](https://skipper.airbnb.tech/docs/quickstart/) ·
[Core Concepts](https://skipper.airbnb.tech/docs/core-concepts/) ·
[Your First Workflow](https://skipper.airbnb.tech/docs/first-workflow/)

## What comes with it

| Capability | What it gives you |
| ---------- | ----------------- |
| [Retries and failure classification](https://skipper.airbnb.tech/docs/error-handling/) | You classify what is transient; Skipper retries it on your policy |
| [Signals and queries](https://skipper.airbnb.tech/docs/signals-and-queries/)   | Feed a running workflow from the outside, or ask it where it is      |
| [Compensation](https://skipper.airbnb.tech/docs/compensation/)                 | Undo completed steps in reverse on failure — the saga pattern        |
| [Versioning](https://skipper.airbnb.tech/docs/versioning/)                     | Evolve workflow code while old instances are still in flight         |
| [Admin UI and tracing](https://skipper.airbnb.tech/docs/observability/)        | Inspect, replay, and manage live instances                          |
| [Testing](https://skipper.airbnb.tech/docs/testing/)                           | `skipper-testutils`: extend `WorkflowTest` and drive workflows deterministically in unit tests |

## Is Skipper for you?

**A good fit if** the process must reach a terminal state despite failures, coordinates several
steps or long waits, and you would rather not operate a separate workflow cluster to get that.

**Probably not** if the work does not need durable execution, or your service is not on the JVM.

## Documentation

The full documentation lives at **[skipper.airbnb.tech](https://skipper.airbnb.tech/)**: guides,
the [API reference](https://skipper.airbnb.tech/docs/reference/annotations/), runnable
[examples](https://skipper.airbnb.tech/examples/), and a
[troubleshooting guide](https://skipper.airbnb.tech/docs/troubleshooting/). The source for the
site is in [`web/`](web/).

## Contributing

Issues and pull requests are welcome. CI runs `./gradlew build` and `./gradlew spotlessCheck`, which fails on any Kotlin or Java file that ktlint or google-java-format would change; `./gradlew spotlessApply` reformats them.

## License

[Apache 2.0](LICENSE). Copyright Airbnb, Inc.
