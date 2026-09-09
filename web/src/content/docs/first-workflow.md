---
title: Your First Workflow
description: Build, invoke, and test a complete Skipper workflow from scratch.
section: Getting Started
order: 4
---

This walkthrough builds a small but complete workflow, calls an action from it, runs it,
and writes an integration test. Examples are in Kotlin; the same APIs are available in Java
(see the note at the end).

## 1. Create a workflow class

A workflow extends `Workflow` and has at least one method annotated with `@WorkflowMethod`.

```kotlin
class Demo : Workflow() {
  @WorkflowMethod
  suspend fun echo(input: String): String = input
}
```

```java
public class Demo extends Workflow {
  @WorkflowMethod(returnType = String.class)
  public CompletableFuture<String> echo(String input) {
    return CompletableFuture.completedFuture(input);
  }
}
```

A workflow method takes **at most one argument**, which must be serializable (a primitive
or a POJO/data class without top-level generics, with `equals()`/`hashCode()` defined — see
**[Serialization](/docs/storage/#serialization)**). The same rule applies to the return type.

## 2. Create an action

Actions are where side effects live. They extend `Actions` and annotate methods with
`@Execute`.

```kotlin
class DemoActions : Actions() {
  @Execute
  suspend fun reverse(input: String): String = input.reversed()
}
```

```java
public class DemoActions extends Actions {
  @Execute
  public String reverse(String input) {
    return new StringBuilder(input).reverse().toString();
  }
}
```

## 3. Call the action from the workflow

Declare a typed handle with `actions<T>()` and call it like a normal method.

```kotlin
class Demo : Workflow() {
  private val demoActions = actions<DemoActions>()

  @WorkflowMethod
  suspend fun echo(input: String): String = demoActions.reverse(input)
}
```

```java
public class Demo extends Workflow {
  private final DemoActions demoActions = actions(DemoActions.class);

  @WorkflowMethod(returnType = String.class)
  public CompletableFuture<String> echo(String input) {
    return CompletableFuture.completedFuture(demoActions.reverse(input));
  }
}
```

> Use `actions<T>()` to obtain action handles — do not inject action classes directly.
> Skipper proxies these calls to record their results.

## 4. Invoke the workflow

Get an `IWorkflowFactory` from your `SkipperRuntime` (see the
**[Quickstart](/docs/quickstart/)** for setup) and start an instance with a unique id. The
id is how you address this specific workflow instance later (to signal it, query it, or
cancel it).

```kotlin
val factory: IWorkflowFactory = runtime.workflowFactory.get()

val demo = factory<Demo>("demo-1")
val result = demo.echo("Hello, world!") // suspends until complete
```

```java
IWorkflowFactory factory = runtime.getWorkflowFactory().get();

Demo demo = factory.invoke(Demo.class, "demo-1");
String result = demo.echo("Hello, world!").get(); // blocks until complete
```

For long-running workflows you usually don't want to wait for the result inline. Register a
**callback handler** and let Skipper notify you on completion, error, or timeout:

```kotlin
val demo = workflowFactory.builder<Demo>("demo-1")
  .callbackHandler(MyCallbackHandler::class.java)
  .build()
```

```java
Demo demo = workflowFactory.builder(Demo.class, "demo-1")
  .callbackHandler(MyCallbackHandler.class)
  .build();
```

See **[Invoking Workflows](/docs/invoking-workflows/)** for the full builder API and how to
write a callback handler.

## 5. Test it

A test needs nothing beyond a `SkipperRuntime` on the default in-memory store: build one per
test, start its scheduler, run the workflow end to end, and assert on the result.

```kotlin
class DemoTest {
  private val runtime = SkipperRuntime(SkipperConfig.forService("demo-test")) // in-memory SQLite

  @BeforeEach fun start() = runtime.skipperSchedulerManager.get().start()
  @AfterEach fun stop() = runtime.skipperSchedulerManager.get().stop()

  @Test
  fun testEcho() = runBlocking {
    val demo = runtime.workflowFactory.get()<Demo>("demo-${UUID.randomUUID()}")
    assertEquals("Hello, world!".reversed(), demo.echo("Hello, world!"))
  }
}
```

```java
public class DemoTest {
  private final SkipperRuntime runtime =
      new SkipperRuntime(SkipperConfig.forService("demo-test")); // in-memory SQLite

  @BeforeEach void start() { runtime.getSkipperSchedulerManager().get().start(); }
  @AfterEach void stop() throws Exception { runtime.getSkipperSchedulerManager().get().stop(); }

  @Test
  public void testEcho() throws Exception {
    Demo demo = runtime.getWorkflowFactory().get().invoke(Demo.class, "demo-" + UUID.randomUUID());
    assertEquals(new StringBuilder("Hello, world!").reverse().toString(), demo.echo("Hello, world!").get());
  }
}
```

See **[Testing](/docs/testing/)** for workflows that wait on signals, compensation, and how to
hand your actions mocked collaborators.

## Java

Everything above works in Java. The main difference is that asynchronous workflows return
`CompletableFuture<T>` and declare the unwrapped type on the annotation,
`@WorkflowMethod(returnType = String.class)`. See **[Kotlin Coroutines](/docs/kotlin-coroutines/)**
for a side-by-side comparison of the execution styles.
