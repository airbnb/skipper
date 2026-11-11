# Trace validation

This directory checks that runs of the real engine are behaviours of the TLA+ models in `../`:
each workflow's run against [`SkipperWorkflowLiveness.tla`](../SkipperWorkflowLiveness.tla), and
each runtime's scheduler against [`SkipperScheduler.tla`](../SkipperScheduler.tla). The first
part of this page is about workflows; [Scheduler traces](#scheduler-traces) covers the second.

The model check in `../` proves properties of the model. Trace validation checks the other half:
that the model describes the code. When the engine does something the model says it can't, either
the spec has drifted and needs updating, or the code has a bug.

## How it works

1. **Recording.** `testutils`' `TraceRecorder` wraps the store and scheduler of every test runtime
   when `-PskipperTraceDir=<dir>` is set. `TestRuntime` and `WorkflowTest` install it.
   - Every write is logged as one JSON line, under one lock, so the file order is the commit order.
   - A line holds the operation and the engine call site that made it (from the stack), plus a
     snapshot of everything the model tracks for that workflow: the workflow row, its timers and
     its task rows, read back after the write.
   - Three reads are logged as well, because the model treats them as steps of their own:
     - the task handler loading the instance it will run (`WfRead`);
     - the task handler then loading its timers (`WfRun`);
     - a signal loading the instance it applies to (`SignalStart`).

     A read counts only when those methods call the store directly. The test helpers' constant
     polling stays out of the trace.
   - Only the stock SQLite and MySQL backends are wrapped. Tests that plug in their own store or
     scheduler steer interleavings by blocking inside calls, and the recorder's lock would change
     what they exercise.
   - A test that only wraps the stock backends, to make a call fail, can install tracing beneath its
     wrapper (`ChildWorkflowStartFailureTest` does). A call the wrapper fails never reaches the trace,
     and the recorder skips the wrapper's frames when it works out which engine method made a call.
2. **Conversion.** `trace_to_tla.py` splits each file by workflow and turns each write into the
   model action it corresponds to (the `ACTIONS` table).
   - State is reduced to model values. Task ids become 0 for the workflow task and 1..n for timers.
     `run_after` becomes `DUE`, `SOON` or `LATER`, or `LEASE` when a fetch, renewal or in-memory
     schedule set it.
   - Checkpoint and persisted-signal writes are dropped, because the model doesn't track those
     rows.
   - A workflow that uses a feature the model doesn't cover yet (compensation, execution timeouts,
     cancellation, rewind, clone) is reported as skipped.
3. **Checking.** [`SkipperTrace.tla`](SkipperTrace.tla) replays one workflow's events against the
   model.
   - A step either consumes the next event, taking its action and landing exactly on its snapshot,
     or is silent. A start that found the workflow already there is a step too, although it
     writes nothing: what that start does next depends on what it found. A silent step is a model step that changes no durable state: a lease lapsing,
     time passing, a worker's local bookkeeping. The logged reads can't happen silently.
   - The workflow body is left free (`FreeBody`), except in how a real body can run. It replays its
     waits in order and may stop anywhere, and it suspends only inside `waitUntil`, on a timer it
     just created or one still active. This checks the engine's protocol against any workflow code.
   - The trace is accepted if some behaviour consumes every event. Otherwise the report names the
     first write the model couldn't explain; the rejected model is re-run with progress printing to
     find it.

The model's constants come from the trace: gate values and the retry limit from the header, and
signal, start and renewal budgets from counting events.

## Keeping the search small

TLC has to guess whatever the trace doesn't pin down, and every guess multiplies the states it
explores. For the longest trace (104 events, from `SerialWorkflowExecutions`), these measures took
the search from not finishing within 10 minutes (620,000 states and climbing) to 241 states and
23 seconds. Each one can only make the checker
reject more, never accept a run it shouldn't:

- **Pinned reads.** The reads above are logged instead of guessed.
- **Ordered bodies.** A run's possible outcomes grow linearly with its waits, not exponentially.
- **Lookahead.** A body's outcome must match one of the next two recorded persists (all outcomes
  stay possible if either persist changed nothing).
- **Worker symmetry.** Workers are interchangeable, so states that differ only in which worker did
  what are merged.
- **No history counters.** Lease values are "one past any in use" rather than a running counter,
  and early lease expiries aren't counted in trace mode, so equivalent states merge.
- **Time only where needed.** Between events, time may move a row's `run_after` only if the next
  event writes that row, or records a class the model hasn't reached yet. Otherwise every pending
  timer drifts through `LATER`, `SOON` and `DUE` in every combination.
- **Quiet by default.** Progress printing runs only when diagnosing a rejection.

## Running it

```bash
formal/tla/trace/record_traces.sh /tmp/traces
TLA2TOOLS=/path/to/tla2tools.jar formal/tla/trace/check_traces.sh --self-test /tmp/traces
```

`record_traces.sh` runs the end-to-end suites with tracing on. `check_traces.sh` prints one line per
workflow:

| Verdict | Meaning | Fails the run? |
|---|---|---|
| `accepted` | The model can take exactly the engine's steps. | No |
| `REJECTED` | It can't; the line names the first write it couldn't explain. | Yes |
| `skipped` | The run uses a feature the model doesn't cover yet. | No |
| `INCONCLUSIVE` | TLC didn't decide within `TRACE_BUDGET` seconds (default 300). | No, but it's listed, and flagged as a warning on GitHub Actions |

An inconclusive trace is worth a look. Rejecting a trace means exhausting every interleaving, so a
slow trace could be hiding a rejection. `JOBS` sets how many TLC runs happen in parallel.

## The self-test

A checker that accepts everything would also report all green. `--self-test` guards against that.
After the real traces pass, `mutants.py` corrupts every accepted trace in five ways, and each mutant
must be rejected:
- bump a workflow version;
- change a workflow status;
- bump a task version;
- drop a write that a later write depends on;
- swap two writes.

A surviving mutant means the checker is too loose, or the corruption happens to be another run the
engine could have produced. Either way, it needs a look.

## Does it catch engine changes?

To check, I changed `TimerTaskHandler` to schedule the workflow task *before* expiring its timer,
instead of after. Both writes still happen, and every end-to-end test stayed green; one unit test
noticed. Trace validation rejected 8 workflow runs. Each stops at the reordered step: the workflow
is scheduled while its timer is still `ACTIVE`, which the model says can't happen. A change like
that is exactly what should prompt either a spec update, if the new order is intended, or a
rethink.

Current results on the traced suites: 110 of 110 in-scope workflow runs accepted, 121 skipped, and
540 of 540 mutants rejected. Of the skips, 96 are tests that write to the store directly; the rest
use compensation (17), cancellation (5), execution timeouts (2) or rewind (1).

The stranded-start recovery of airbnb/skipper#46 is covered the same way. With the engine changed
back to scheduling a stranded child like any other start, the recorded run of
`ChildWorkflowStartFailureTest`'s stranded child is rejected at that schedule.

## What it has found so far

Running it turned up places where the model didn't describe the code. They were fixed in the model:

| The code | The model had |
|---|---|
| `createWorkflow` inserts `RUNNING` | `CREATED` |
| A persist moves a timer only as `Timer.Status.canTransitionTo` allows | any transition |
| A retry with `taskUnexpectedErrorRetryDelay = 0` is due at once | retries always backed off |
| A terminal workflow that runs again is persisted again, which bumps its version | the run was skipped |
| Signals take the in-memory path when `FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER` is off | signals always went to the scheduler |
| Callers invoke the workflow method again to read the result | one start call |
| An in-memory copy runs on the instance it was queued with, so its persist can lose the lock | a fresh read |
| The handler reads its instance and its timers at different moments | one read |

## Limits

- **Timing.** The recorder's lock serialises store calls, which can hide races that happen at full
  speed. Timing-sensitive tests may behave differently with tracing on.
- **Rejected writes aren't checked.** A write that changed nothing the model tracks, such as a lost
  optimistic lock, is consumed without checking that the model agrees it would fail.
- **Stopping looks like being slow.** A worker that silently stops, taking no further step, can't be
  told apart from one that is just slow, because the model has no fairness in trace mode. An engine
  change that makes a handler give up without writing can pass.
- **One workflow at a time.** Each workflow's run is checked on its own; workers shared across
  workflows are not modelled.

# Scheduler traces

The same test runs also record what each runtime's scheduler does in memory, and each recording is
replayed against the scheduler model. The question it answers: does the engine's admitted count
move the way the model says, and does the fetch loop ask for what its free slots allow?

## How it works

1. **Recording.** With `-PskipperTraceDir=<dir>`, the test harness also gives each runtime a
   `SchedulerTraceRecorder`, which writes `sched-<uuid>.ndjson`.
   - The runtime builds its in-memory queue and its task handlers through a
     `SchedulerInstrumentation`, an optional argument of `SkipperRuntime`'s constructor. That is the
     one seam in the engine. It only decides what gets built, it is not on `SkipperConfig`, and it
     is not a supported extension point. The recorder builds a `TracingExecutionQueue`, a subclass that
     records each `add`, `take` and `markFinished`, and wraps each handler in a
     `TracingTaskHandler`, which records the call, the future coming back, and the future
     completing. A wrapper around the store records each fetch the fetch loop makes.
   - Each line carries the queue's admitted count after the step. Every step that changes the
     count runs under one lock together with its line, so the file order is the order the steps
     happened and each count is exact.
   - A fetch line also carries the limit and fetch size in force, read from the config and the
     knobs as the scheduler manager reads them.
   - A handler's completion is recorded before the scheduler sees it: the scheduler is handed a
     future derived from the handler's, which completes right after the line is written.
   - Nothing is written until the runtime builds its own scheduler manager. A test that drives a
     manager of its own, with handlers the recorder never sees, leaves no file.
   - A fetch is recognised by its caller, `SkipperSchedulerManager`. If the fetch loop's call moved
     elsewhere, fetches would go unrecorded, fetched tasks would appear from nowhere, and those runs
     would be rejected.
2. **Conversion.** `sched_trace_to_tla.py` turns each file into one model.

   | Line | Model action |
   |---|---|
   | `fetch` | `FetchLease`, with the tasks it came back with |
   | `add` | `Deliver` for a fetched task, `InMemAdd` for one scheduled in the process |
   | `take` | `Take` |
   | `start` | `RunStart` |
   | `report` | `Report` |
   | `done` | `HandlerDone` |
   | `release` | `SlotBack`, `Cancel` or `RunAborts`: whichever task could give a slot back |

   The model's limit and fetch size come from the fetch lines. A run in which they changed, or
   whose fetches are sized from the backlog, is skipped, because they are constants of the model.
   So is a run in which the test adds a task to the queue itself, doing the fetch loop's job by
   hand. Nothing else is skipped, and `check_traces.sh` fails when there are no scheduler files or
   when no scheduler run that fetched a task is accepted.
3. **Checking.** [`SkipperSchedulerTrace.tla`](SkipperSchedulerTrace.tla) replays the events
   against `SkipperScheduler` with one instance.
   - A step consumes the next event, taking its action and landing on its admitted count. The
     only step with no event is the fetch loop reading its free slots (`FetchRead`); a fetch is
     accepted only if what it asked for is what some such read allows.
   - The recorder does not see the task rows or other instances. So the conditions on rows are
     opened up: any task may be due, and any lease may have moved. Partitioning and what happens
     across instances are left to the model check.
   - A slot given back while its handler is still running (finding 1 of the scheduler model) is
     not allowed, so a run that shows it is rejected.

Replaying is nearly deterministic, so these checks are quick: the longest run so far has a few
hundred events and takes under a second.

## The self-test

`mutants.py` corrupts the 40 longest scheduler runs in five ways, each of which must be rejected:
- the queue's count is one too high;
- a fetch asked for one task more;
- a task ran without being taken from the queue;
- a handler handed back its future before it was called;
- the last slot in use came back before its handler finished.

## Does it catch engine changes?

Two checks, neither of which any end-to-end test notices:

- **Giving the slot back when the scheduler stops waiting.** I changed `AdmittedRun.done` to
  release the slot as soon as the run ends, instead of holding it until the handler finishes. All
  112 integration tests stayed green. Trace validation rejected the scheduler run of
  `SchedulerTaskExecutionTimeoutTest`: it stops at the slot coming back while the handler that
  outlived the timeout is still running.
- **Finding 1 of the scheduler model, in the engine as it is.** A handler that blocks inside
  `handle`, and a run the lease renewer cancels meanwhile. The recorded run reads: handler called,
  slot given back, future handed back, and then a fetch asking for a task the instance has no room
  for. It is rejected at the future being handed back, since the model has no handler left to
  hand one back. The suites do not produce this run on their own (see Limits).

Current results on the traced suites: 119 of 119 scheduler runs accepted (2,066 events, the longest
112), and 200 of 200 mutants rejected.

## Limits

- **One instance, no rows.** The check covers an instance's own accounting. It cannot tell
  whether a cancel was justified, or whether a task should have been fetchable.
- **Timing.** The recorder's lock serialises the queue's steps, which can hide races that happen
  at full speed.
- **Only what the suites do.** A race that the suites never hit is never recorded. Finding 1
  needs the lease renewer to cancel a run within one store read of its start; no suite does that.
- **Runtimes only.** Tests that build their own scheduler manager are not recorded.
- **Stopping looks like being slow**, as for workflows: a handler that never finishes just leaves
  a shorter trace.
