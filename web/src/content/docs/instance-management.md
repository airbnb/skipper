---
title: Instance Management
description: Inspect, cancel, re-execute, and recover workflow instances from outside the workflow.
section: Guides
order: 15
---

Outside of a workflow definition, you manage running and finished instances through the
workflow proxy and the `WorkflowsService`.

## A single instance

Get a handle by id, then inspect or control it.

```kotlin
val workflow = workflowFactory<MyWorkflow>(workflowId)

val view = workflow.getWorkflowInstanceView()
println(view.status)      // RUNNING, WAITING, COMPLETED, ERROR, TIMEOUT, RETRIES_EXHAUSTED
println(view.createdAt)

workflow.cancelWorkflowInstance("User cancelled")
```

```java
MyWorkflow workflow = workflowFactory.invoke(MyWorkflow.class, workflowId);

WorkflowInstanceView view = workflow.getWorkflowInstanceView();
System.out.println(view.getStatus());     // RUNNING, WAITING, COMPLETED, ERROR, TIMEOUT, RETRIES_EXHAUSTED
System.out.println(view.getCreatedAt());

workflow.cancelWorkflowInstance("User cancelled");
```

Cancellation won't kill a workflow that is actively executing, but it prevents further
retries and cancels a workflow that is in a waiting state. With the `INFLIGHT_CANCELLATION_CHECKPOINTS`
gate on, a running workflow stops at its next action boundary. A workflow method declared with
`compensateOnCancel = true` also [undoes its completed actions](/docs/compensation/#compensating-a-cancelled-workflow).

## Bulk operations

`WorkflowsService` operates across many instances — useful for dashboards and incident
response.

```kotlin
// Count by status
val running = workflowsService.countWorkflowsByStatus(listOf(RUNNING))

// Count a parent's children, all or only those in some statuses
val children = workflowsService.countChildWorkflows("parent-1")
val pending = workflowsService.countChildWorkflows("parent-1", setOf(RUNNING, WAITING))

// Find workflows parked after exhausting retries
val stuck = workflowsService.findWorkflowsWithExhaustedRetries(100)

// Re-run them (retries the last failed action)
workflowsService.reExecuteWorkflows(listOf("wf-1", "wf-2"))

// Cancel several non-terminal workflows
workflowsService.cancelWorkflows(listOf("wf-3", "wf-4"), "Cleaning up")
```

```java
// Count by status
long running = workflowsService.countWorkflowsByStatus(List.of(RUNNING));

// Count a parent's children, all or only those in some statuses
long children = workflowsService.countChildWorkflows("parent-1");
long pending = workflowsService.countChildWorkflows("parent-1", Set.of(RUNNING, WAITING));

// Find workflows parked after exhausting retries
List<String> stuck = workflowsService.findWorkflowsWithExhaustedRetries(100);

// Re-run them (retries the last failed action)
workflowsService.reExecuteWorkflows(List.of("wf-1", "wf-2"));

// Cancel several non-terminal workflows
workflowsService.cancelWorkflows(List.of("wf-3", "wf-4"), "Cleaning up");
```

## Re-running a workflow for the same key

A workflow id is never reused, so running the same job twice, such as reindexing the same customer,
needs a new id. `WorkflowsService` names the runs after a key you choose: `key`, `key_r2`, `key_r3`,
and so on. Every run keeps its own history.

`nextRunId` returns the id for the next run, or empty while the latest run is not finished. A run is
finished once it has completed, timed out, or finished compensating. A failed run is finished too,
unless Skipper is about to compensate it. A cancelled run is finished too, unless its workflow
method sets `compensateOnCancel`. For that run `nextRunId` throws, because `WorkflowsService` cannot
tell whether it will still compensate.

```kotlin
val key = "reindex-${customer.id}"
val nextId = workflowsService.nextRunId(key)
if (nextId.isPresent) {
    workflowFactory.builder(ReindexWorkflow::class.java, nextId.get()).runAsync().build().reindex(customer)
} else {
    val live = workflowsService.findLatestRun(key).get()
    println("${live.id} is still ${live.status}")
}
```

```java
String key = "reindex-" + customer.getId();
Optional<String> nextId = workflowsService.nextRunId(key);
if (nextId.isPresent()) {
    workflowFactory.builder(ReindexWorkflow.class, nextId.get()).runAsync().build().reindex(customer);
} else {
    WorkflowInstanceView live = workflowsService.findLatestRun(key).get();
    System.out.println(live.getId() + " is still " + live.getStatus());
}
```

Start the run as soon as you get the id. If two callers start at once, they get the same id and
start one run between them. The second caller joins the first run, and its own input is ignored.

A cancel does not stop an execution that is already running, so a cancelled run may still run
actions after `nextRunId` returns. A run parked in `RETRIES_EXHAUSTED` or `COMPENSATION_ERROR` blocks
the next run until an operator resolves it, for example with `reExecuteWorkflows`.

Use one key for one workflow class, and do not end a key in `_r` followed by a number. Otherwise two
keys can end up with the same run ids. Finding the latest run reads every run of the key, so it suits
keys with a handful of runs. Deleting a run hides every later run from the lookup, so delete only the
latest run. Reviving an older run with `resetWorkflowsFromError` or `rewindWorkflow` gives the key two
live runs, so revive only the latest run.

## Rewinding a workflow

For incident recovery, `rewindWorkflow` resets a workflow to a chosen checkpoint (the
"pivot") and replays from there — even if the workflow is already in a terminal state. It
atomically sets the status back to `RUNNING`, deletes the pivot checkpoint and everything
after it, and schedules immediate execution. Checkpoints **before** the pivot are kept, so
those actions don't run again.

```kotlin
// By named checkpoint
workflowsService.rewindWorkflow("wf-1", "charge-payment")
```

```java
// By named checkpoint
workflowsService.rewindWorkflow("wf-1", "charge-payment");
```

> Rewinding permanently deletes the discarded checkpoints, and any non-idempotent side
> effects in the re-executed actions will happen again. Use it only for incident recovery.

## Dead-letter queue

Workflows that cannot make progress due to an unexpected error in workflow code end up in a
dead-letter queue (DLQ) for manual inspection. After fixing the underlying issue, re-drive
or remove them.

```kotlin
val failed = workflowsService.inspectDeadLetterQueue(100)
workflowsService.redriveDeadLetterQueue(failed)   // retry
workflowsService.removeFromDeadLetterQueue(failed) // give up, remove
```

```java
List<Task<Object>> failed = workflowsService.inspectDeadLetterQueue(100);
workflowsService.redriveDeadLetterQueue(failed);    // retry
workflowsService.removeFromDeadLetterQueue(failed); // give up, remove
```
