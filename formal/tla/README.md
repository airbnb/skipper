# Workflow liveness model

`SkipperWorkflowLiveness.tla` is a TLA+ model of how the engine drives a workflow instance through
the store, checked with TLC. The property it pins down:

> Once a workflow is created, it runs to a terminal state or to a state that needs manual
> intervention.

## The property, precisely

- **Terminal**: `COMPLETED`, `ERROR`. Terminal states are absorbing (`TerminalIsStable`).
- **Manual intervention**: `RETRIES_EXHAUSTED`, or a task marked `FAILED` by the scheduler. `FAILED`
  tasks are the DLQ: the workflow status does not change, and requeueing the task is what drives the
  workflow on.
- **Waiting**: every `WAITING` workflow has an active timer that will fire, meaning a timer row in
  `ACTIVE` with its `TIMER` task present (`WaitingIsDriven`).
- **Running**: a `CREATED`, `RUNNING` or `TRANSIENT_ERROR` workflow always has its `WORKFLOW` task
  behind it (`RunningIsDriven`). It does not count if the workflow is only rescued by an unrelated
  wait timer, which can be up to 365 days away. This is the invariant the lost-signal race
  (airbnb/skipper#12) broke.
- **Starting**: `startWorkflow` may fail before the workflow runs, and the caller is expected to
  retry. A retry may be a no-op only once the workflow task exists.
- **Liveness**: `EventuallyDone == <>[]Done`: the workflow is eventually created and stays terminal,
  `RETRIES_EXHAUSTED`, or parked in the DLQ.

The two invariants are the safety half of the argument, and they are much cheaper to check than the
liveness property. Each says that something durable, or an obligation someone will honour, will run
the workflow again.

## What is modelled

- One workflow instance.
- Scheduler workers. Each worker can fetch a task, lease it, renew the lease, run the task
  handler, and make the final versioned remove or reschedule
  (`SkipperSchedulerManager.finishTask`).
- The in-process `startWorkflow` path, which writes a backstop row and keeps an in-memory copy of
  the task in the caller's JVM.
- The workflow body, abstracted to `NWaits` calls to `waitUntil(cond, timeout)` followed by a
  return. Wait *k*'s condition holds once *k* signals have been applied. Optionally the body can
  also fail as transient, non-retryable, or retries-exhausted.
- The environment: the `startWorkflow` caller, a signal sender, and an operator who requeues DLQ
  tasks.

Every action is one store transaction or one local step, so a fault can land between any two of
them. The fault kinds are switched on per model: worker/JVM crash, handler timeout, early lease
expiry, a store error after the workflow row committed, `startWorkflow` failing after creating the
row, and a signal call failing before it schedules.

Not modelled yet: compensation, execution timeouts, cancellation, child workflows, persisted-signal
replay, task partitioning, several workflows sharing workers.

| Action | Code |
|---|---|
| `StartCreate`, `StartSched` | `SkipperEngine.startWorkflow`, `scheduleExecution`, `SchedulerExecutionQueue.schedule` |
| `Fetch`, `MarkFailed`, `Finish` | `SqliteScheduler.fetch`, `SkipperSchedulerManager.handleTask` / `finishTask` / `scheduleRerunNow` |
| `Take` | `SkipperSchedulerManager.startTakingTasks` on the in-memory queue |
| `WfRead`, `WfRun`, `WfPersist`, `WfTimers` | `WorkflowExecutionTaskHandler.handle` (its instance read, then its timer read and the run) / `persistExecutionResult`, `Workflow.waitUntil` |
| `TmExec`, `TmSched` | `TimerTaskHandler.handle` |
| `Renew` | `LeaseRenewalManager.attemptToRenewOneTask` |
| `SignalStart`, `SignalPersist`, `SignalSched` | `SkipperEngine.runSignal` |
| `Requeue` | `Scheduler.requeueFailedTask` |
| `Sched` | `Scheduler.schedule`: insert at version 1, overwrite, or honour an active lease (and bump the version if `BUMP_TASK_VERSION_ON_HONORED_LEASE` is on) |

## Running it

```bash
TLA2TOOLS=/path/to/tla2tools.jar formal/tla/check.sh            # every model
TLA2TOOLS=/path/to/tla2tools.jar formal/tla/check.sh baseline   # one model
```

Get `tla2tools.jar` from the [TLA+ releases](https://github.com/tlaplus/tlaplus/releases). CI
pins v1.7.4. The script needs Java 11 or later, and the full run takes about a minute.

For each model, the script prints a verdict and compares it with the model's `\* expect:` line.
The verdict is one of:
- `ok`
- the name of the violated invariant or property
- `temporal`, for a violated liveness property

The script exits 1 on any mismatch. Models for bugs that are still open record the violation as
their expectation. So when a fix lands and its model starts passing, update that `expect:` line to
`ok` in the same change. TLC writes the full output, counterexample traces included, to `$OUT_DIR`,
or to a temp dir if that isn't set.

CI runs the same script in `.github/workflows/formal.yml` (job `tla-model-check`) whenever
`formal/` changes. It keeps TLC's output as the `tlc-output` run artifact. The job isn't a required
check.

## Results (main at 619a628)

| Model | Scenario | Result |
|---|---|---|
| `baseline` | default gates, 2 workers, one signal, no faults | ok |
| `baseline_user_failures` | as above, the workflow body may fail | ok |
| `pr12_regression` | `BUMP_TASK_VERSION_ON_HONORED_LEASE` off | `RunningIsDriven` violated: the #12 race, found again (calibration) |
| `start_retry` | `startWorkflow` fails after creating the row; caller retries | **stuck forever** |
| `start_retry_noop_off` | as above, `CREATE_EXISTING_WORKFLOW_IS_NOOP` off | ok |
| `timer_arming_crash` | a worker crashes while running the workflow | **stuck forever** |
| `timer_arming_store_error` | one store error after the workflow row committed | **stuck forever** |
| `inmem_start` | in-process start, no faults | **stuck forever** |
| `lease_faults` | a lease lapses under a live holder, 2 workers | **stuck forever** |
| `signal_schedule` | a signal call fails before scheduling | `RunningIsDriven` violated; liveness holds only through the wait timer |

The findings behind these results:

1. **A retried `startWorkflow` can leave a new workflow `RUNNING` with no task.** (`createWorkflow` inserts
   the row as `RUNNING`, not `CREATED`; trace validation caught the model's earlier `CREATED`.)
   - `createWorkflow` (`SkipperEngine.kt:102`) and `scheduleExecution` (`:143`) are separate
     transactions.
   - If the call fails between them, the caller's retry hits `EntityAlreadyExists`.
   - With `CREATE_EXISTING_WORKFLOW_IS_NOOP`, which is on by default (`:128`), that retry returns
     without scheduling.

2. **A `WAITING` workflow can end up with a timer that never fires.**
   - The timer row is written in the same transaction as `WAITING`
     (`WorkflowExecutionTaskHandler.kt:540`), but its `TIMER` task is scheduled afterwards
     (`:555`).
   - A crash, or one failed insert, between the two reruns the workflow task.
   - On the rerun, `waitUntil` finds the timer row and does not create it again
     (`Workflow.kt:377`). No new `TIMER` task is scheduled, and the workflow task is removed.

3. **The task row's version can be reused (ABA), so a stale holder deletes someone else's row.**
   - `remove` is `DELETE ... WHERE version = ?` (`SqliteScheduler.kt:420`, `MySqlScheduler.kt:424`),
     and a re-inserted row starts again at version 1.
   - A holder whose lease was lost can therefore delete a row that was later re-inserted and has
     reached the version it holds. The row may belong to a signal, a fired timer, or another worker.
   - Two ways to get a stale holder:
     - a pause longer than the lease (8 minutes by default);
     - an in-process start whose in-memory copy waited past its lease, then finished before the
       renewer (which polls every 500 ms) cancelled it.
   - In both traces the deleted row is the workflow task that a fired timer had just scheduled.

4. **A signal can leave a `RUNNING` workflow with no workflow task.**
   - The signal persists `RUNNING`, then schedules the workflow task in a separate call
     (`SkipperEngine.kt:485` / `:492`, then `:507`).
   - If that call fails, the workflow is `RUNNING` with no task. Only the wait timer rescues it.
