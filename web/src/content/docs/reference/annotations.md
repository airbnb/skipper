---
title: Annotations
description: The annotations that make up Skipper's programming model, with their parameters.
section: Reference
order: 30
---

These pages are a dense lookup of Skipper's public surface. For usage in context, follow the
linked guides.

Annotations are discovered across the full class hierarchy, so they may be declared on
abstract base classes and are honored on concrete subclasses.

| Annotation | Target | Description |
|---|---|---|
| `@WorkflowMethod` | Workflow method | Declares a workflow entry point. Pass `returnType` to give the unwrapped type for a Java `CompletableFuture` return. |
| `@StateField` | Workflow field | Marks a field as persisted workflow state, saved and restored when the workflow resumes. No parameters. |
| `@SignalMethod` | Workflow method | Injects data into a running workflow (max one serializable parameter). `persist = true` durably records the signal. |
| `@QueryMethod` | Workflow method | Reads workflow state without mutating it. No parameters. |
| `@Execute` | Actions method | Declares an action. Optional `returnType`, `retryStrategy`, `exceptionClassifier`, and `checkpointMode`. |
| `@Compensate` | Actions method | Rollback logic for the action named by `forExecute`. Optional `checkpointMode`. |

A workflow using several of them:

```kotlin
class TransferWorkflow : Workflow() {
  private val ledger = actions<LedgerActions>()

  @StateField var isApproved: Boolean? = null

  @WorkflowMethod
  suspend fun transfer(request: TransferRequest): Boolean {
    if (request.amount > 1000) {
      val ok = waitUntil({ isApproved != null }, Duration.ofDays(1))
      if (!ok || isApproved != true) return false
    }
    ledger.debit(request.from)
    ledger.credit(request.to)
    return true
  }

  @SignalMethod fun approve(approved: Boolean) { isApproved = approved }

  @QueryMethod fun approvalState(): Boolean? = isApproved
}
```

```java
public class TransferWorkflow extends Workflow {
  private final LedgerActions ledger = actions(LedgerActions.class);

  @StateField Boolean isApproved;

  @WorkflowMethod(returnType = Boolean.class)
  public CompletableFuture<Boolean> transfer(TransferRequest request) {
    if (request.getAmount() > 1000) {
      boolean ok = waitUntil(() -> isApproved != null, Duration.ofDays(1));
      if (!ok || !Boolean.TRUE.equals(isApproved)) return CompletableFuture.completedFuture(false);
    }
    ledger.debit(request.getFrom());
    ledger.credit(request.getTo());
    return CompletableFuture.completedFuture(true);
  }

  @SignalMethod
  public void approve(boolean approved) { isApproved = approved; }

  @QueryMethod
  public Boolean approvalState() { return isApproved; }
}
```
