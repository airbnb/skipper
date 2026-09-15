<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="web/public/brand/skipper-horiz-white.svg">
  <img src="web/public/brand/skipper-horiz-color.svg" alt="Skipper" width="320">
</picture>

**Durable execution, embedded in your service.**

Skipper is a lightweight workflow engine for the JVM. Write long-running business logic as plain
Kotlin or Java, and let Skipper guarantee it runs to completion in spite of failures.
No cluster to operate, no new database: just a dependency.

<a href="https://central.sonatype.com/artifact/com.airbnb.skipper/skipper-core"><img src="https://img.shields.io/maven-central/v/com.airbnb.skipper/skipper-core?color=2a3990&label=Maven%20Central" alt="Maven Central"></a>
<a href="https://skipper.airbnb.tech/docs/reference/annotations/"><img src="https://img.shields.io/badge/javadoc-API_reference-2a3990" alt="Javadoc / API reference"></a>
<a href="https://dl.circleci.com/status-badge/redirect/gh/airbnb/skipper/tree/main"><img src="https://dl.circleci.com/status-badge/img/gh/airbnb/skipper/tree/main.svg?style=shield" alt="CI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-2a3990" alt="License: Apache 2.0"></a>
<img src="https://img.shields.io/badge/JVM-8%2B-2a3990" alt="JVM 8+">
<img src="https://img.shields.io/badge/API-Kotlin_%26_Java-f15b29" alt="Kotlin and Java">
<img src="https://img.shields.io/badge/status-pre--release-f15b29" alt="Pre-release">

[**Documentation**](https://skipper.airbnb.tech/) · [Quickstart](https://skipper.airbnb.tech/docs/quickstart/) · [Examples](examples/) · [API reference](https://skipper.airbnb.tech/docs/reference/annotations/) · [Releases](https://github.com/airbnb/skipper/releases)

</div>

---

```kotlin
class CheckoutWorkflow : Workflow() {
  private val payments = actions<PaymentActions>()
  private val email = actions<EmailActions>()

  @WorkflowMethod
  suspend fun checkout(order: Order): Receipt {
    // Charged once — even if the workflow resumes later.
    val charge = payments.charge(order)
    // Retried automatically until it succeeds.
    email.sendReceipt(order, charge)
    return Receipt(charge.id)
  }
}
```

Each action is checkpointed to your database when it completes. If the process crashes or is
redeployed halfway through, the workflow resumes on any replica and skips the steps already done.
Same code in Java: see [`examples/java-gradle-sqlite`](examples/java-gradle-sqlite/).

## 60-second quickstart

You need a JDK (17 or newer) and Gradle 9 on your PATH. Paste this into a terminal:

```bash
mkdir hello-skipper && cd hello-skipper && mkdir -p src/main/kotlin

cat > settings.gradle.kts <<'EOF'
rootProject.name = "hello-skipper"
EOF

cat > build.gradle.kts <<'EOF'
plugins {
  kotlin("jvm") version "2.4.20"
  kotlin("plugin.allopen") version "2.4.20"
  application
}

repositories { mavenCentral() }

dependencies {
  implementation("com.airbnb.skipper:skipper-core:0.8.0")
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// Skipper subclasses your Workflow and Actions classes at runtime; AllOpen makes them open for it.
allOpen { annotation("com.airbnb.skipper.SkipperOpen") }

kotlin { jvmToolchain(17) }
application {
  mainClass.set("MainKt")
  applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}
EOF

cat > src/main/kotlin/Main.kt <<'EOF'
import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.factory.SkipperRuntime
import com.airbnb.skipper.invoke
import kotlinx.coroutines.runBlocking

// Actions hold the side effects: I/O, RPCs, database writes. Each result is checkpointed.
class GreetingActions : Actions() {
  @Execute
  suspend fun render(name: String): String = "Hello, $name!"
}

// The workflow is the deterministic orchestration around them.
class GreetingWorkflow : Workflow() {
  private val actions = actions<GreetingActions>()

  @WorkflowMethod
  suspend fun greet(name: String): String = actions.render(name)
}

fun main() {
  // Embedded in-memory SQLite by default: nothing to install, nothing to configure.
  val runtime = SkipperRuntime(SkipperConfig.forService("hello-skipper"))
  runtime.skipperSchedulerManager.get().start()
  try {
    val factory = runtime.workflowFactory.get()
    // The id is the idempotency key: starting "greeting-1" twice joins the same instance.
    val result = runBlocking { factory<GreetingWorkflow>("greeting-1").greet("world") }
    println(result)
  } finally {
    runtime.skipperSchedulerManager.get().stop()
  }
}
EOF

gradle -q run
```

It prints `Hello, world!` after running the workflow through Skipper's scheduler and store. The
in-memory store is for experiments and tests. Point it at a SQLite file, or at the MySQL your
service already runs, in [Getting started](#getting-started) below.

## Waiting, signals, and retries

Real workflows wait on the outside world. Skipper hibernates them holding no thread and polling
nothing, and wakes them when a signal arrives or a timer fires. This survives deploys and restarts.

```kotlin
class CheckoutWorkflow : Workflow() {
  private val payments = actions<PaymentActions>()
  private val email = actions<EmailActions>()

  @StateField var reviewCleared: Boolean? = null

  @WorkflowMethod
  suspend fun checkout(order: Order): Receipt {
    if (order.total > 1_000) {
      // Hibernate until a human decides, up to a day.
      val reviewed = waitUntil({ reviewCleared != null }, Duration.ofDays(1))
      if (!reviewed || reviewCleared != true) return Receipt.declined(order)
    }

    // Checkpointed once it completes, so a resume skips it. Delivery is at-least-once:
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

Starting and signalling one is a call from your API handler, a queue consumer, anywhere:

```kotlin
val factory = runtime.workflowFactory.get()

// The workflow id is the idempotency key: the same id never starts a second instance,
// it joins the one already running.
val receipt = factory<CheckoutWorkflow>("order-${order.id}").checkout(order)

// Later, from another request, the same id hands you the live instance to signal.
factory<CheckoutWorkflow>("order-${order.id}").clearReview(true)
```

No retry loop, no state machine, no queue plumbing, and no scheduled job of your own polling for
approvals.

## How it works

- **Your replicas are the workers.** Every replica of your service runs Skipper's scheduler.
  There is no worker fleet to deploy and no task queue to size: work is claimed from the shared
  store by lease, so replicas need no coordination with each other.
- **State is a snapshot, not an event log.** A workflow's state and each action's result live in
  a row, which is why they should stay small.
- **Workflow methods must be deterministic.** Skipper re-runs the method and skips the steps it
  has already checkpointed, so the code has to take the same path twice. Clocks, randomness,
  and I/O belong in actions, not in the workflow.

→ [Core Concepts](https://skipper.airbnb.tech/docs/core-concepts/)

## Why Skipper

- **A library, not a cluster.** Embed Skipper in the service you already run. There is no
  separate control plane to deploy, scale, or get paged about.
- **Your storage, your rules.** Workflow state persists behind pluggable interfaces: an embedded
  SQLite backend in the box for zero-setup starts, MySQL for a shared production store, and room
  for your own.
- **Long waits cost nothing.** A workflow can hibernate for a day or a week awaiting a signal or
  an approval while holding no thread, then resume exactly where it paused.
- **You can see what is running.** A built-in admin UI, one JAX-RS resource you register with
  your existing HTTP layer, lists live instances, their state, and their history.

## Getting started

Skipper is on Maven Central as `com.airbnb.skipper:skipper-core`. The badge above shows the
latest release; the snippets below pin the current one.

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.airbnb.skipper:skipper-core:0.8.0")
}
```

<details>
<summary>Gradle (Groovy) and Maven</summary>

```groovy
// build.gradle
implementation 'com.airbnb.skipper:skipper-core:0.8.0'
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.airbnb.skipper</groupId>
  <artifactId>skipper-core</artifactId>
  <version>0.8.0</version>
</dependency>
```

</details>

Kotlin projects also need the AllOpen compiler plugin configured for `com.airbnb.skipper.SkipperOpen`,
as in the quickstart above, so Skipper can subclass your workflow and action classes.

Skipper is built against Jackson 2.9 and tested on every push against the newest Jackson 2.x as well, so
it runs on whichever Jackson your service already has.

Then wire it up once at startup:

```kotlin
// In main(), or wherever your service wires up its singletons on startup.
val config = SkipperConfig.forService("my-service")   // embedded SQLite, in-memory: nothing else to set up
val runtime = SkipperRuntime(config)

// Start the scheduler once. It is what drives workflows forward. Stop it on shutdown.
runtime.skipperSchedulerManager.get().start()
```

That default is for getting started, local runs, and tests. Build the runtime once and hold it
for the life of the process: it is what hands you the workflow factory. For production, point the
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

Prefer a working project to a guide? [`examples/`](examples/) holds complete, CI-tested builds on
the common JVM stacks: plain Java with Gradle or Maven, Kotlin, Spring Boot on MySQL, and Dropwizard
with Guice and the admin UI.

## What comes with it

| Capability | What it gives you |
| ---------- | ----------------- |
| [Retries and failure classification](https://skipper.airbnb.tech/docs/error-handling/) | You classify what is transient; Skipper retries it on your policy |
| [Signals and queries](https://skipper.airbnb.tech/docs/signals-and-queries/)   | Feed a running workflow from the outside, or ask it where it is      |
| [Compensation](https://skipper.airbnb.tech/docs/compensation/)                 | Undo completed steps in reverse on failure: the saga pattern         |
| [Versioning](https://skipper.airbnb.tech/docs/versioning/)                     | Evolve workflow code while old instances are still in flight         |
| [Admin UI and tracing](https://skipper.airbnb.tech/docs/observability/)        | Inspect, replay, and manage live instances                          |
| [Metrics](https://skipper.airbnb.tech/docs/observability/#metrics)             | Engine counters, latencies and gauges; `skipper-metrics-prometheus` exposes them to a Prometheus scrape |
| [Testing](https://skipper.airbnb.tech/docs/testing/)                           | `skipper-testutils`: extend `WorkflowTest` and drive workflows deterministically in unit tests |

## Is Skipper for you?

**A good fit if** the process must reach a terminal state despite failures, coordinates several
steps or long waits, and you would rather not operate a separate workflow cluster to get that.

**Probably not** if the work does not need durable execution, or your service is not on the JVM.

## Documentation

The full documentation lives at **[skipper.airbnb.tech](https://skipper.airbnb.tech/)**: guides,
the [API reference](https://skipper.airbnb.tech/docs/reference/annotations/), runnable
[examples](https://skipper.airbnb.tech/examples/), and a
[troubleshooting guide](https://skipper.airbnb.tech/docs/troubleshooting/). Every release also ships
a Dokka javadoc jar next to the artifact on Maven Central. The source for the site is in
[`web/`](web/).

## Contributing

Issues and pull requests are welcome. CI runs `./gradlew build` and `./gradlew spotlessCheck`, which fails on any Kotlin or Java file that ktlint or google-java-format would change; `./gradlew spotlessApply` reformats them.

## License

[Apache 2.0](LICENSE). Copyright Airbnb, Inc.
