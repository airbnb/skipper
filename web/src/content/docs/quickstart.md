---
title: Quickstart
description: Add Skipper to a JVM service and run your first workflow — with zero database setup.
section: Getting Started
order: 2
---

This guide gets Skipper running in an existing JVM service in a few minutes. For a deeper,
worked example with tests, see **[Your First Workflow](/docs/first-workflow/)**.

## 1. Add the dependency

Skipper is published to Maven Central as `com.airbnb.skipper:skipper-core`. The current
version is on the
[artifact page](https://central.sonatype.com/artifact/com.airbnb.skipper/skipper-core).

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.airbnb.skipper:skipper-core:0.8.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.airbnb.skipper</groupId>
  <artifactId>skipper-core</artifactId>
  <version>0.8.0</version>
</dependency>
```

If you write workflows in Kotlin, also enable the AllOpen compiler plugin so Skipper can
subclass your workflow and action classes (see **[Kotlin Coroutines](/docs/kotlin-coroutines/)**).

## 2. Configure Skipper

Skipper is configured through a `SkipperConfig`. The only required setting is a unique service
name. By default Skipper persists to an **embedded, in-memory SQLite** store — so there is
nothing else to set up to start experimenting: no database to provision and no schema to
create.

```kotlin
import com.airbnb.skipper.SkipperConfig

val config = SkipperConfig.forService("my-service")
```

```java
import com.airbnb.skipper.SkipperConfig;

SkipperConfig config = SkipperConfig.forService("my-service");
```

> The in-memory store is ideal for getting started, local development, and tests — but it is
> **not durable**: its state is lost when the process exits. To keep state across restarts on a
> single node, give the store and the scheduler the same file path instead:
>
> ```kotlin
> config.workflowStore = SqliteWorkflowStore.Factory("skipper.db")
> config.scheduler = SqliteScheduler.Factory("skipper.db")
> ```
>
> ```java
> config.setWorkflowStore(new SqliteWorkflowStore.Factory("skipper.db"));
> config.setScheduler(new SqliteScheduler.Factory("skipper.db"));
> ```
>
> Both factories are in `com.airbnb.skipper.internal.storage.sqlite` and
> `com.airbnb.skipper.internal.scheduler.sqlite`; the schema is created on first start. For
> several replicas sharing one store, use MySQL. See **[Storage Backends](/docs/storage/)**.

## 3. Create the runtime and start the scheduler

`SkipperRuntime` wires up the engine from your config. Start its scheduler so workflows are
driven forward, and keep the runtime around to obtain the workflow factory.

```kotlin
import com.airbnb.skipper.admin.AdminResource
import com.airbnb.skipper.factory.SkipperRuntime

val runtime = SkipperRuntime(config)

// Start the scheduler (stop it on shutdown).
runtime.skipperSchedulerManager.get().start()

// Optional: expose the admin UI by registering this JAX-RS resource with your HTTP layer.
// See Observability & Admin UI for details.
val admin = runtime.adminResource.get()
```

```java
import com.airbnb.skipper.admin.AdminResource;
import com.airbnb.skipper.factory.SkipperRuntime;

SkipperRuntime runtime = new SkipperRuntime(config);

// Start the scheduler (stop it on shutdown).
runtime.getSkipperSchedulerManager().get().start();

// Optional: expose the admin UI by registering this JAX-RS resource with your HTTP layer.
// See Observability & Admin UI for details.
AdminResource admin = runtime.getAdminResource().get();
```

## 4. Write a workflow

A workflow is a class with at least one `@WorkflowMethod`. An action is a method on an
`Actions` class, annotated with `@Execute`, where you perform I/O and side effects.

```kotlin
import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod

class GreetingWorkflow : Workflow() {
  private val actions = actions<GreetingActions>()

  @WorkflowMethod
  suspend fun greet(name: String): String = actions.render(name)
}

class GreetingActions : Actions() {
  @Execute
  suspend fun render(name: String): String = "Hello, $name!"
}
```

```java
import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import java.util.concurrent.CompletableFuture;

public class GreetingWorkflow extends Workflow {
  private final GreetingActions actions = actions(GreetingActions.class);

  @WorkflowMethod(returnType = String.class)
  public CompletableFuture<String> greet(String name) {
    return CompletableFuture.completedFuture(actions.render(name));
  }
}

public class GreetingActions extends Actions {
  @Execute
  public String render(String name) {
    return "Hello, " + name + "!";
  }
}
```

## 5. Invoke it

Get the workflow factory from the runtime and start an instance with a unique id.

```kotlin
import com.airbnb.skipper.IWorkflowFactory

val factory: IWorkflowFactory = runtime.workflowFactory.get()

val workflow = factory<GreetingWorkflow>("greeting-42")
val result = workflow.greet("world") // suspends until the workflow completes
```

```java
import com.airbnb.skipper.IWorkflowFactory;

IWorkflowFactory factory = runtime.getWorkflowFactory().get();

GreetingWorkflow workflow = factory.invoke(GreetingWorkflow.class, "greeting-42");
String result = workflow.greet("world").get(); // blocks until the workflow completes
```

That's it — Skipper persists progress as the workflow runs and will drive it to completion
even if the process restarts mid-flight.

## Next steps

- Understand the model: **[Core Concepts](/docs/core-concepts/)**.
- Build and test a real one: **[Your First Workflow](/docs/first-workflow/)**.
