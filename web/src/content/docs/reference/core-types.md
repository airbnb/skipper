---
title: Core types
description: The classes and interfaces that make up Skipper's API — Workflow, Actions, the factory, callbacks, and instance management.
section: Reference
order: 31
---

## Where things live

The snippets in the guides omit imports. Everything is under `com.airbnb.skipper`; these are the
sub-packages you will need. `internal` is part of the public API where a guide names it.

| Package | Types |
|---|---|
| `com.airbnb.skipper` | `Workflow`, `Actions`, the annotations, `IWorkflowFactory`, `WorkflowCallbackHandler`, `SkipperConfig`, `SimpleInjector`, `ComponentFactory`, `RetryStrategy`, `FixedRetryStrategy`, `ExponentialRetryStrategy`, `PersistentRetryStrategy`, `SkipperError`, `RetryableError`, `NonRetryableError`, `FeatureGate` |
| `com.airbnb.skipper.api` | `WorkflowInstanceView`, `WorkflowInstanceStatusView` |
| `com.airbnb.skipper.factory` | `SkipperRuntime` |
| `com.airbnb.skipper.admin` | `AdminResource` |
| `com.airbnb.skipper.internal` | `ExceptionClassifier`, `DefaultExceptionClassifier` |
| `com.airbnb.skipper.internal.storage.sqlite` / `...internal.scheduler.sqlite` | `SqliteWorkflowStore.Factory`, `SqliteScheduler.Factory` |
| `com.airbnb.skipper.internal.storage.mysql` / `...internal.scheduler.mysql` | `MySqlWorkflowStore.Factory`, `MySqlScheduler.Factory` |
| `com.airbnb.skipper.testutils` | `WorkflowTest`, `Bind`, `WorkflowTestHelper` |

## In-workflow API — `Workflow`

Methods available inside a `@WorkflowMethod`. See [Workflow API](/docs/workflow-api/) and
[Versioning](/docs/versioning/).

| Signature | Description |
|---|---|
| `waitUntil(condition): Boolean` | Wait indefinitely until the condition becomes true. |
| `waitUntil(condition, timeout): Boolean` | Wait until the condition holds or the timeout elapses; returns whether it was met. |
| `waitUntil(condition, timeout, timerId): Boolean` | Same, with a stable timer id — the evolution-safe form. |
| `sleep(duration)` | Pause for a fixed duration. |
| `checkpoint(block)` / `checkpoint(name, block)` | Run a block once and persist its effect; the named variant is evolution-safe. |
| `checkpointSuspend(block)` | Suspend-friendly `checkpoint` for Kotlin suspend workflows. |
| `version(changeId, minVersion, maxVersion): Int` | Version gate for branching changed logic without breaking in-flight instances. |
| `actions<T>()` / `actions(Class)` | Obtain a typed handle to an `Actions` class. |
| `id: String` | This workflow instance's id. |
| `getWorkflowInstanceView(): WorkflowInstanceView` | A snapshot of this instance's status and history. |
| `cancelWorkflowInstance(reason): WorkflowInstanceView` | Cancel this instance. |

## Defining actions — `Actions`

See [Core Concepts](/docs/core-concepts/), [Compensation](/docs/compensation/), and
[Error Handling](/docs/error-handling/).

| Signature | Description |
|---|---|
| `actions.named(name)` — Kotlin | Give the next action call a stable checkpoint name. |
| `actions.named(Class, name)` — Java | Same, with an explicit class token for a typed return. |
| `retryStrategyProvider(): RetryStrategy` | Override (Java) to set the default retry strategy for an action class. |

## Invocation — `IWorkflowFactory`

Starting and addressing workflows. See [Invoking Workflows](/docs/invoking-workflows/).

| Signature | Description |
|---|---|
| `factory<T>(id)` — Kotlin | Create/address a workflow instance by id. The id is an idempotency key. |
| `factory.invoke(Class, id)` — Java | The same, for Java callers. |
| `factory.builder<T>(id)` — Kotlin | Builder for advanced invocation; reified, no class token. |
| `factory.builder(Class, id)` — Java | The same, for Java callers. |
| `.callbackHandler(Class)` | Register a `WorkflowCallbackHandler` for async lifecycle events. |
| `.runAsync()` | Hand execution to the persistent scheduler instead of running in-process. |
| `.detached()` | Return without waiting for the result — [fire-and-forget](/docs/invoking-workflows/#fire-and-forget). Requires a result-less workflow method. |
| `.workflowOptions(WorkflowOptions)` | Per-invocation options (timeout, query-on-nonexistent). |
| `.requestContext(payload)` | Opaque, persisted per-request payload surfaced to middleware. |
| `.parentWorkflowId(id)` | Link this instance to a parent workflow. |
| `.build(): T` | Build the workflow handle. |

## Lifecycle callbacks — `WorkflowCallbackHandler`

Implement to receive async results. The first four are required; the rest have default no-op
implementations. See [Invoking Workflows](/docs/invoking-workflows/).

| Method | Fires when… | Required |
|---|---|---|
| `onSuccess(view)` | the workflow reaches a successful terminal state | yes |
| `onNonRetryableError(view, error)` | a non-retryable error (or exhausted retries) failed it | yes |
| `onWorkflowInWaitingStatus(view)` | it hit an unfulfilled `waitUntil` and hibernated | yes |
| `onWorkflowTimeout(view)` | it exceeded its execution timeout | yes |
| `onRetryableError(view, error)` | a retryable error occurred (before a retry) | default |
| `onRetriesExhausted(view, error)` | a `PersistentRetryStrategy` ran out of retries | default |
| `onCompensationCompleted(view)` / `onCompensationError(view, error)` | compensation finished or failed | default |
| `onCancelled(view, reason)` | the instance was cancelled | default |

## Instance management — `WorkflowsService`

Bulk operations over many instances. See [Instance Management](/docs/instance-management/).

| Signature | Description |
|---|---|
| `countWorkflowsByStatus(statuses): long` | Count instances in the given statuses. |
| `findWorkflowsWithExhaustedRetries(limit)` | List workflows parked in `RETRIES_EXHAUSTED`. |
| `reExecuteWorkflows(ids)` | Re-run workflows, retrying their last failed action. |
| `cancelWorkflows(ids, reason)` | Cancel multiple non-terminal workflows. |
| `rewindWorkflow(id, pivot)` | Reset an instance to a checkpoint and replay from there (incident recovery). |
| `resetWorkflowsFromError(ids)` | Move errored workflows back to a runnable state. |
| `deleteWorkflow(id)` | Permanently delete a workflow instance. |
| `inspectDeadLetterQueue(limit)` | List tasks parked in the dead-letter queue. |
| `redriveDeadLetterQueue(tasks)` / `removeFromDeadLetterQueue(tasks)` | Retry or permanently remove DLQ tasks. |

## Workflow status — `WorkflowInstanceView`

Returned by `getWorkflowInstanceView()`. Fields: `id`, `workflowClass`, `workflowMethod`,
`status`, `createdAt`, `workflowInput`, `state`, `parentWorkflowId`. The `status` is one of:

`CREATED` · `RUNNING` · `WAITING` · `COMPLETED` · `ERROR` · `TRANSIENT_ERROR` ·
`RETRIES_EXHAUSTED` · `TIMEOUT` · `COMPENSATION_IN_PROGRESS` · `COMPENSATION_ERROR` ·
`COMPENSATION_COMPLETED` · `CANCELLED`
