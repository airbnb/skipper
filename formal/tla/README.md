# TLA+ models

Two models of the engine, checked with TLC by the same script:

- **Workflow liveness** (`SkipperWorkflowLiveness.tla`, below): one workflow instance through the
  store.
- **Scheduler** (`SkipperScheduler.tla`, [further down](#scheduler-model)): many opaque tasks over
  several scheduler instances, each with a concurrency limit, with or without task partitioning.

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
- **One run at a time**: no two workers run the workflow task at once, from loading the instance
  to the final write (`NoOverlappingRuns`). Two runs at once would execute the same actions
  concurrently.
- **Liveness**: `EventuallyDone == <>[]Done`: the workflow is eventually created and stays terminal,
  `RETRIES_EXHAUSTED`, or parked in the DLQ.

`RunningIsDriven` and `WaitingIsDriven` are the safety half of the argument, and they are much cheaper to check than the
liveness property. Each says that something durable, or an obligation someone will honour, will run
the workflow again.

## What is modelled

- One workflow instance.
- Scheduler workers. Each worker can fetch a task, lease it, renew the lease, run the task
  handler, and make the final versioned remove or reschedule
  (`SkipperSchedulerManager.finishTask`).
- One or more `startWorkflow` callers, each in its own JVM with its own in-memory queue.
- The in-process `startWorkflow` path, which writes a backstop row and keeps an in-memory copy of
  the task in the caller's JVM.
- The start that finds a workflow stranded (`RUNNING` with no task row) and schedules it only in
  the persistent scheduler, due after a lease (`StartRecover`).
- The workflow body, abstracted to `NWaits` calls to `waitUntil(cond, timeout)` followed by a
  return. Wait *k*'s condition holds once *k* signals have been applied. Optionally the body can
  also fail as transient, non-retryable, or retries-exhausted.
- The environment: the `startWorkflow` caller, a signal sender, and an operator who requeues DLQ
  tasks.

Every action is one store transaction or one local step, so a fault can land between any two of
them. The fault kinds are switched on per model: worker/JVM crash, handler timeout, early lease
expiry, a store error after the workflow row committed, `startWorkflow` failing after creating the
row, and a signal call failing before it schedules.

`NoLongPauses` is an assumption some models make: no start stalls between its two writes, and no
in-memory copy waits in its queue, for as long as a lease. Without it, an in-memory copy can outlive
its lease and run next to the backstop row's delivery, which `inmem_start` already records.

Not modelled yet: compensation, execution timeouts, cancellation, child workflows, persisted-signal
replay. Task partitioning and several tasks sharing instances are in the
[scheduler model](#scheduler-model).

| Action | Code |
|---|---|
| `StartCreate`, `StartSched`, `StartRecover` | `SkipperEngine.startWorkflow` (its `isStranded` check), `scheduleExecution`, `SchedulerExecutionQueue.schedule` / `scheduleAfterLease` |
| `Fetch`, `MarkFailed`, `Finish` | `SqliteScheduler.fetch`, `SkipperSchedulerManager.handleTask` / `finishTask` / `scheduleRerunNow` |
| `Take` | `SkipperSchedulerManager.startTakingTasks` on the in-memory queue |
| `WfRead`, `WfRun`, `WfPersist`, `WfTimers` | `WorkflowExecutionTaskHandler.handle` (its instance read, then its timer read and the run) / `persistExecutionResult`, `Workflow.waitUntil` |
| `WfRearmCheck`, `WfRearm` | `WorkflowExecutionTaskHandler.rearmOrphanedTimers` (its `getTask`, then its `schedule`) |
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
pins v1.7.4. The script needs Java 11 or later, and the full run takes a little over a minute.

A config names the module it checks on a `\* spec:` line (the `sched_*` configs check
`SkipperScheduler`); without one it checks `SkipperWorkflowLiveness`. For each model, the script
prints a verdict and compares it with the model's `\* expect:` line.
The verdict is one of:
- `ok`
- the name of the violated invariant or property
- `temporal`, for a violated liveness property

The script exits 1 on any mismatch. Models for bugs that are still open record the violation as
their expectation. So when a fix lands and its model starts passing, update that `expect:` line to
`ok` in the same change. TLC writes the full output, counterexample traces included, to `$OUT_DIR`,
or to a temp dir if that isn't set.

CI runs the same script in `.github/workflows/formal.yml` (job `tla-model-check`) whenever
the model, the engine or the test harness changes. It keeps TLC's output as the `tlc-output` run
artifact. Its result feeds `formal`, the fan-in check the main branch ruleset requires.

## Results (main at 619a628, plus airbnb/skipper#40's timer re-arm and #46's stranded-start recovery)

| Model | Scenario | Result |
|---|---|---|
| `baseline` | default gates, 2 workers, one signal, no faults | ok |
| `baseline_user_failures` | as above, the workflow body may fail | ok |
| `pr12_regression` | `BUMP_TASK_VERSION_ON_HONORED_LEASE` off | `RunningIsDriven` violated: the #12 race, found again (calibration) |
| `start_retry` | `startWorkflow` fails after creating the row; caller retries | ok (was stuck forever; fixed in #46) |
| `start_retry_noop_off` | as above, `CREATE_EXISTING_WORKFLOW_IS_NOOP` off | ok |
| `timer_arming_crash` | a worker crashes while running the workflow | ok (was stuck forever; fixed in #40) |
| `timer_arming_store_error` | one store error after the workflow row committed | ok (was stuck forever; fixed in #40) |
| `inmem_start` | in-process start, no faults | **stuck forever** |
| `lease_faults` | a lease lapses under a live holder, 2 workers | **stuck forever** |
| `signal_schedule` | a signal call fails before scheduling | `RunningIsDriven` violated; liveness holds only through the wait timer |
| `start_race` | two in-process starts from different JVMs, either may fail after creating the row; `NoLongPauses` | ok |
| `start_race_unguarded` | as above, with the stranded start scheduled like any other start | `NoOverlappingRuns` violated (calibration) |
| `rearm_overlap` | a timer task goes missing and a lease lapses, so two executions schedule the same timer's task; checks `TimerSchedulesKeepLeases` only | ok |

`NoOverlappingRuns` is checked in every model except `inmem_start`, `lease_faults` and
`rearm_overlap`. Their leases can lapse under a queued copy or a live holder, the stale holders of
finding 3, so two runs can overlap there.

The findings behind these results:

1. **A retried `startWorkflow` can leave a new workflow `RUNNING` with no task.** Fixed in
   airbnb/skipper#46. (`createWorkflow` inserts the row as `RUNNING`, not `CREATED`; trace
   validation caught the model's earlier `CREATED`.)
   - `createWorkflow` and `scheduleExecution` are separate transactions.
   - If the call fails between them, the caller's retry hits `EntityAlreadyExists`.
   - With `CREATE_EXISTING_WORKFLOW_IS_NOOP`, which is on by default, that retry returned without
     scheduling.
   - The repair: a start that finds the workflow `RUNNING` with no task row schedules it anyway
     (`SkipperEngine.isStranded`).
   - That opens a race. The "stranded" workflow may belong to a start that is only slow and is about
     to schedule it in memory. If the second start also schedules it like a normal start, the workflow
     runs twice at once:
     1. One start writes the row.
     2. Another start finds the workflow `RUNNING` with no task and schedules it.
     3. A worker leases that task.
     4. The first start's in-memory schedule honours the lease but still queues its own copy.
     5. A second worker takes the copy.

     `start_race_unguarded` finds this.
   - So the recovering start writes only the backstop row, due after a lease, with no in-memory
     copy (`SchedulerExecutionQueue.scheduleAfterLease`). Whichever row write comes first, the
     other honours its lease, and only one copy runs. `start_race` checks it, and trace
     validation checks the engine against it on a recorded stranded child
     (`ChildWorkflowStartFailureTest`).

2. **A `WAITING` workflow can end up with a timer that never fires.** Fixed in airbnb/skipper#40.
   - The timer row is written in the same transaction as `WAITING`
     (`WorkflowExecutionTaskHandler.kt:540`), but its `TIMER` task is scheduled afterwards
     (`:553`).
   - A crash, or one failed insert, between the two reruns the workflow task.
   - On the rerun, `waitUntil` finds the timer row and does not create it again
     (`Workflow.kt:377`). Before the fix, no new `TIMER` task was scheduled, and the workflow task
     was removed.
   - The repair (`WfRearmCheck`, `WfRearm`, `:557`): after persisting a non-terminal execution,
     every timer the run loaded as `ACTIVE` and left untouched gets its `TIMER` task scheduled again
     if that task is missing (`:578`). With it, both `timer_arming_*` models pass, and removing it
     brings back the `WaitingIsDriven` violation.
   - The check and the schedule are separate calls. An overlapping execution can create the task in
     between, and a worker can lease it. The overlapping execution can also be the late one: its new
     timer's task can arrive after the other execution has re-armed that timer. So every timer
     schedule, new or re-armed, honours an active lease without a version bump; the scheduler checks
     the lease in the same write. `rearm_overlap` checks `TimerSchedulesKeepLeases`: no timer
     schedule replaces a task under an active lease. Without the honour on either schedule, TLC finds
     the overwrite. That model leaves out `WaitingIsDriven`, because with early expiry on it already
     fails through finding 3.
   - A workflow stranded before the fix has no task of either kind, so nothing runs it again on its
     own. Re-executing it (`WorkflowsService.reExecuteWorkflows`, the admin bulk-retry) runs it once,
     and that run re-arms the timer.

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

# Scheduler model

`SkipperScheduler.tla` models the scheduler as a component: N opaque tasks, M instances that each
keep a count of the tasks they have admitted and (optionally) a concurrency limit, one shared table
of task rows, and (optionally) task partitioning over the live cluster members. It is separate from
the workflow model because the questions are different: here a task has no workflow state, and what
matters is which instance holds which task, what each instance's count says, and who may fetch what.

## The properties

1. **The count is the work** (`AdmittedMatchesWork`, `IdleMeansZero`). An instance's admitted count
   equals its tasks queued, plus its runs not yet done, plus its handlers still pending. It is never
   negative (`TypeOK`), and an instance with nothing to do reports zero.
2. **Fetched work stays within the limit** (`FetchedWithinLimit`). The tasks an instance fetched
   never exceed its limit, however stale the free-slot count its fetch acted on. The count as a
   whole may pass the limit, because in-memory executions are never refused;
   `sched_inmem_over_limit` asks for `AdmittedWithinLimit` and records the violation.
3. **No fetched task waits behind a full instance** (`NoFetchedWaitsWhileFull`). A fetched task is
   never leased and waiting to start while the instance is already executing as many tasks as its
   limit, unless in-memory work is taking up room.
4. **Every due task eventually runs** (`DueEventuallyRuns`), on some instance, across crashes and
   membership changes. Since every fault is bounded, every task is also eventually finished
   (`EveryTaskCompletes`), and with partitioning the member list settles with every bucket owned by
   a live instance in that instance's own view (`BucketsEventuallyOwned`).

Liveness assumes weak fairness for the steps that happen on their own: live instances keep
fetching, taking, running and reporting; handlers that are not hung complete; the wait on a hung
handler times out; unheld leases run out; dead members age out, and live ones heartbeat and re-read
the member list. Faults, in-memory schedules, cancels and restarts may or may not happen.

## What is modelled

| Action | Code |
|---|---|
| `FetchRead` | `SkipperSchedulerManager.startFetchingTasks`: `freeSlots()` and `min(getMaxTasks(), free)` |
| `FetchLease` | `fetchTasksWithOptionalPartitioning`, `SqliteScheduler.fetch` / `MySqlScheduler.fetch` (plain and partitioned) |
| `Deliver` | `SchedulerExecutionQueue.add` for each fetched task, which refuses an id already queued |
| `Owns`, `RangeStart`, `RangeSize` | `BucketPartitioner.getBucketRangeForMember`, and the fall back to the whole table when the gate is off or the instance is missing from the member list it read |
| `InMemAdd` | `SchedulerExecutionQueue.schedule`: the leased backstop row and the in-memory copy |
| `Take` | `SkipperSchedulerManager.startTakingTasks`: `take`, `executor.execute(run)`, `addTaskInFlight` |
| `RunStart`, `Report` | `AdmittedRun` running `handleTask`: `handler.handle`, then `holdSlotUntil(handled)` |
| `HandlerDone` | the handler's future completing, and `finishTask` when the run still waits on it |
| `SlotBack` | `AdmittedRun.done` / `SchedulerExecutionQueue.markFinished` once the handler is done |
| `RunAborts` | a run that ends before it has a handler: the executor rejects it, the task is past `maxRetries` and is marked failed, or `handler.handle` throws |
| `Timeout` | `handled.get(taskExecutionTimeout)` giving up in `handleTask` |
| `Hang` | a handler whose future never completes |
| `Cancel` | `LeaseRenewalManager.attemptToRenewOneTask` failing and calling `taskFuture.cancel(true)`, then `AdmittedRun.done` |
| `Lapse` | a row's `run_after` passing: the lease is gone and the row can be fetched again |
| `Crash`, `Join` | the process dying and starting; `JdbcClusterMembershipManager.start` (first heartbeat, then the member list) |
| `MemberExpire`, `MemberLapse`, `Heartbeat`, `RefreshView` | `JdbcClusterMembershipManager`: `LIVENESS_THRESHOLD` on `last_heartbeat_at`, the heartbeat loop, and the cached `getActiveMemberIds` |

The fetch is three steps on purpose: the loop reads its free slots, the store leases the tasks,
and the loop queues them one at a time. In-memory schedules and finishing tasks can land between
any two; property 2 has to hold anyway. A handler finishing and its slot coming back are separate
steps for the same reason. That granularity is also what lets a recorded run be replayed against
the model one event at a time ([trace validation](trace/README.md#scheduler-traces)).

Left out, on purpose:

- Everything the workflow model covers: versions and the final versioned write (here a task is
  finished if its row still carries the lease the run holds), signals, timers, and retry counts
  (a task marked failed is just a run that ends without a handler).
- `FutureTask` internals. That a slot is given back exactly once is covered by `AdmittedRunTest`.
- Timing: the 100 ms poll while full and the 1 s pause after a short fetch only decide when the
  next fetch happens.
- A graceful stop. It reaches the same states as a crash followed by the member row ageing out.
- A renewal that wins back a lapsed lease nobody else took. It returns to a state already covered.
- The executor's size, a limit changed at runtime through the knob, and the SQLite candidate page
  (a partitioned fetch can return fewer tasks than are due).
- An unbounded stream of in-memory work. In-memory schedules are finite here; a process that
  keeps itself over its limit with in-memory work never fetches, which is by design.

## Results (airbnb/skipper#58's concurrency limit)

Every model has two instances, two buckets and a limit of 1 unless it says otherwise.

| Model | Scenario | Result |
|---|---|---|
| `sched_baseline` | limit 2, fetch size 3, three due tasks, no faults | ok |
| `sched_no_limit` | as above with no limit | ok |
| `sched_inmem` | one task enters in memory on either instance, two are due | ok |
| `sched_inmem_over_limit` | as above, asking that the whole count stay within the limit | `AdmittedWithinLimit` violated (calibration: in-memory work is never refused) |
| `sched_faults` | limit 2; two faults among crash, handler timeout, lease lapse and a run that ends before it has a handler; the renewer cancels runs whose lease moved | ok |
| `sched_partitioned` | partitioning on; two faults among crash and a live member's heartbeat ageing out | ok |
| `sched_cancel_unreported` | the renewer cancels a run between its start and the moment it reports its handler | **`AdmittedMatchesWork` violated** (finding 1) |
| `sched_hang` | one instance, one handler that never completes | **a due task never runs** (the documented trade-off) |
| `sched_hang_spare` | two instances, no partitioning, one hung handler | ok: the other instance runs what the full one cannot |
| `sched_hang_every_attempt` | as above, one task whose handler hangs on every attempt | **a due task never runs** (finding 3) |
| `sched_hang_partitioned` | as `sched_hang_spare` with partitioning on | **a due task never runs**, next to an idle instance (finding 2) |

The models are not vacuous: each fault path is reached in the model that enables it, and breaking
the spec on purpose is caught. Fetching the fetch size regardless of free slots breaks
`FetchedWithinLimit`. Giving the slot back on a timeout or on a cancel while the handler runs,
keeping the count across a crash, and counting a task the queue refused, each break
`AdmittedMatchesWork`. Dead members that never age out break liveness in `sched_partitioned`.

The model check says nothing about whether the engine does what the model says. That half is
[trace validation](trace/README.md#scheduler-traces): recorded runs of the real scheduler are
replayed against this model, and a run it cannot explain fails the `trace-validation` job.

The findings:

1. **A run cancelled before it reports its handler gives its slot back while the handler runs on.**
   - `AdmittedRun.done` holds the slot for the handler only if the run has reported the handler's
     future (`holdSlotUntil`). The run reports it after `handler.handle` returns, and
     `WorkflowExecutionTaskHandler.handle` reads the workflow from the store before it returns.
   - If the lease renewer cancels the run in that window (`LeaseRenewalManager`, when another
     worker has re-leased the row), `done` sees no future and gives the slot back at once. The
     interrupt does not stop `handle`, which goes on to start the workflow on the handler pool.
   - From then until that handler finishes, the instance runs one task more than its count says,
     so a fetch can take it one over its limit.
   - Confirmed in the engine: with a handler that blocks inside `handle` and a run the renewer
     cancels, `admittedCount` is 0 while the handler's future is still pending. Trace validation
     rejects the recorded run.
     `AdmittedRunTest.testRunCancelledBeforeItReachesTheHandlerGivesTheSlotBackOnce` pins the same
     behaviour down as the release happening once.
   - Narrow in practice: the cancel has to land within one store read of the run starting, on a
     task whose lease was already lost.

2. **With partitioning, a full instance's buckets wait even when another instance is idle.**
   - An instance at its limit stops fetching, but keeps heartbeating, so it stays in the member
     list and keeps its bucket range. A partitioned fetch never reads outside its own range.
   - So tasks in that range wait for a slot on that instance. If its slots are held by handlers
     that never complete, they wait for good, while the other members have room.
   - Without partitioning, any instance with room runs them (`sched_hang_spare`).
   - Confirmed in the engine: two scheduler managers over one store, each with a limit of 1 and
     half the buckets. The first one's handler never completes. Its other due tasks did not run in
     8 s, while the second manager finished its own tasks and sat idle.
   - The same mechanism without a hang is only a delay: a busy member's tasks queue behind it
     instead of spilling to an idle one.

3. **One task whose handler always hangs takes one more slot each time it is rerun.**
   - After the execution timeout the run ends, the handler keeps its slot, and nothing renews the
     lease. When the lease lapses the task is fetched again, by any instance with room, and its
     new handler takes another slot.
   - This repeats until the task has used `schedulerTaskMaxRetries` deliveries (100 by default),
     which is more than most limits. Read from the code and the model; not reproduced in a test.
