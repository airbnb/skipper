---
title: Signals & Queries
description: Communicate with a running workflow — push data in with signals, read state out with queries.
section: Guides
order: 11
---

Workflows often need to talk to the outside world while they run: receive an approval, get
a result that arrives asynchronously, or expose their current status. Signals and queries
are how.

## Signals

A **signal** pushes data into a running workflow, usually to wake it from a `waitUntil`.
Annotate a method with `@SignalMethod`. Signals typically just update `@StateField` values.

```kotlin
class ApprovalWorkflow : Workflow() {
  @StateField var isApproved: Boolean? = null

  @WorkflowMethod
  suspend fun process(request: ApprovalRequest): String {
    waitUntil({ isApproved != null }, Duration.ofDays(7))
    return if (isApproved == true) "Approved" else "Rejected"
  }

  @SignalMethod
  fun setApproval(approved: Boolean) { this.isApproved = approved }
}
```

```java
public class ApprovalWorkflow extends Workflow {
  @StateField Boolean isApproved;

  @WorkflowMethod(returnType = String.class)
  public CompletableFuture<String> process(ApprovalRequest request) {
    waitUntil(() -> isApproved != null, Duration.ofDays(7));
    return CompletableFuture.completedFuture(
        Boolean.TRUE.equals(isApproved) ? "Approved" : "Rejected");
  }

  @SignalMethod
  public void setApproval(boolean approved) { this.isApproved = approved; }
}
```

Send a signal by getting a handle to the instance and calling the method:

```kotlin
val workflow = workflowFactory<ApprovalWorkflow>(workflowId)
workflow.setApproval(true)
```

```java
ApprovalWorkflow workflow = workflowFactory.invoke(ApprovalWorkflow.class, workflowId);
workflow.setApproval(true);
```

**Rules for signals:**

- At most one parameter, which must be serializable.
- They run **synchronously on the caller's thread** — so the caller learns immediately if
  the signal fails and can retry. The workflow then continues asynchronously.
- Keep them lightweight: validate fast and mutate `@StateField`s. Don't invoke actions or
  perform I/O from a signal.

### Durable signals

By default a signal is not persisted — if it throws or the process crashes mid-execution
and the caller doesn't retry, it is lost. For business-critical signals, opt into
persistence:

```kotlin
@SignalMethod(persist = true)
fun setApproval(decision: ApprovalDecision) { this.decision = decision }
```

```java
@SignalMethod(persist = true)
public void setApproval(ApprovalDecision decision) { this.decision = decision; }
```

Skipper writes a durable record before executing the signal. Records that never reach the
`EXECUTED` state can be inspected and **replayed** through the admin API:

```
GET  /skipper/admin/workflows/{id}/signals
POST /skipper/admin/workflows/{id}/signals/{signalId}/replay
```

Replaying re-applies the signal's effects, so signal idempotency remains your
responsibility.

## Queries

A **query** reads a workflow's state without modifying it. Annotate a method with
`@QueryMethod` — typically a simple accessor or a value derived from state.

```kotlin
class OrderWorkflow : Workflow() {
  @StateField var status: String = "PENDING"

  @QueryMethod
  fun getStatus(): String = status
}
```

```java
public class OrderWorkflow extends Workflow {
  @StateField String status = "PENDING";

  @QueryMethod
  public String getStatus() { return status; }
}
```

```kotlin
val status = workflowFactory<OrderWorkflow>(workflowId).getStatus()
```

```java
String status = workflowFactory.invoke(OrderWorkflow.class, workflowId).getStatus();
```

**Rules for queries:**

- No parameters.
- Must not modify state (any mutation is not persisted) and must not perform I/O.
- Run synchronously on the caller's thread.
