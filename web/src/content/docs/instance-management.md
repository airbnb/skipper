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
retries and cancels a workflow that is in a waiting state.

## Bulk operations

`WorkflowsService` operates across many instances — useful for dashboards and incident
response.

```kotlin
// Count by status
val running = workflowsService.countWorkflowsByStatus(listOf(RUNNING))

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

// Find workflows parked after exhausting retries
List<String> stuck = workflowsService.findWorkflowsWithExhaustedRetries(100);

// Re-run them (retries the last failed action)
workflowsService.reExecuteWorkflows(List.of("wf-1", "wf-2"));

// Cancel several non-terminal workflows
workflowsService.cancelWorkflows(List.of("wf-3", "wf-4"), "Cleaning up");
```

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
