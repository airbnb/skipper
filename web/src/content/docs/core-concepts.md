---
title: Core Concepts
description: The handful of ideas that make up Skipper's programming model — workflows, actions, determinism, and durable state.
section: Getting Started
order: 3
---

Skipper's model is small. Once these few concepts click, the rest of the API is just
detail.

## Workflows

A **workflow** is the composition of a series of sequential or parallel steps that make up
a business process. In code it is a class that extends `Workflow` and exposes at least one
method annotated with `@WorkflowMethod`.

```kotlin
class TransferWorkflow : Workflow() {
  @WorkflowMethod
  suspend fun transfer(request: TransferRequest): Boolean { /* ... */ }
}
```

```java
public class TransferWorkflow extends Workflow {
  @WorkflowMethod(returnType = Boolean.class)
  public CompletableFuture<Boolean> transfer(TransferRequest request) { /* ... */ }
}
```

The workflow method holds your business logic — the decisions, branches, and waits. It
should read like a description of the process, not like infrastructure code.

## Actions

An **action** is a function that does one unit of real work: sending an email, writing to a
database, calling another service — anything with I/O or side effects. Actions live on
classes that extend `Actions`, with methods annotated with `@Execute`.

```kotlin
class LedgerActions : Actions() {
  @Execute
  suspend fun debit(account: String) { /* ... */ }
}
```

```java
public class LedgerActions extends Actions {
  @Execute
  public void debit(String account) { /* ... */ }
}
```

Actions are decoupled from workflow logic and are therefore reusable. A workflow calls
actions through a typed handle:

```kotlin
private val ledger = actions<LedgerActions>()
```

```java
private final LedgerActions ledger = actions(LedgerActions.class);
```

Action inputs and return types must be **serializable** so Skipper can persist them — a
primitive, or a POJO/data class that Jackson can serialize (no top-level generics, so wrap a
`List`/`Map` in a top-level class).

## Determinism

Skipper provides durability by **reconstructing a workflow's progress when it resumes** —
for example after a wait, a retry, or a process restart. To do this reliably, the workflow
method must be **deterministic**: given the same inputs, the code outside of action calls
must always take the same path.

In practice this means a few operations must not happen directly inside workflow code:

- Reading the current time (`Instant.now()` and similar).
- Generating random values or UUIDs.
- Any other I/O or non-deterministic call.

Anything non-deterministic belongs in an **action** (or a `checkpoint`, below). This is the
single most important rule when writing workflows.

> Don't catch `Throwable`, `Exception`, or `Error` in workflow code — Skipper uses
> exceptions internally to suspend workflows and to surface action failures, and swallowing
> them leads to undefined behavior. See
> [Error Handling](/docs/error-handling/#dont-catch-blanket-exceptions-in-workflow-code).

## Durable state &amp; checkpoints

Fields you want to persist across the life of a workflow are marked with `@StateField`.
Skipper saves and restores them so the workflow can pause and resume without losing data.

```kotlin
@StateField var isApproved: Boolean? = null
```

```java
@StateField Boolean isApproved;
```

When a workflow resumes, completed actions are **not** run again — their results were
persisted and are returned from the saved record. For non-deterministic work that isn't a
natural action, wrap it in a `checkpoint`, which runs once and persists its effect:

```kotlin
checkpoint { total = computeExpensiveTotal() }
```

```java
checkpoint(() -> { total = computeExpensiveTotal(); });
```

This is also how you safely mutate a `@StateField` from workflow code when a signal can
mutate the same field — see **[Workflow API](/docs/workflow-api/)**.

## Putting it together

A workflow coordinates **actions**, persists **state**, and can **wait** for the outside
world — and Skipper makes the whole thing durable. The next page builds a complete example
end to end.
