# Trace validation

This directory checks that runs of the real engine are behaviours of the TLA+ model in
[`../SkipperWorkflowLiveness.tla`](../SkipperWorkflowLiveness.tla).

The model check in `../` proves properties of the model. Trace validation checks the other half:
that the model describes the code. When the engine does something the model says it can't, either
the spec has drifted and needs updating, or the code has a bug.

## How it works

1. **Recording.** `testutils`' `TraceRecorder` wraps the store and scheduler of every test runtime
   when `-PskipperTraceDir=<dir>` is set. `TestRuntime` and `WorkflowTest` install it.
   - Each write is logged as one JSON line, under one lock, so the file order is the commit order.
   - A line holds the operation and the engine call site that made it (from the stack), plus a
     snapshot of everything the model tracks for that workflow: the workflow row, its timers and
     its task rows, read back after the write.
   - Only the stock SQLite and MySQL backends are wrapped. Tests that plug in their own store or
     scheduler steer interleavings by blocking inside calls, and the recorder's lock would change
     what they exercise.
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
     or is silent. A silent step is a model step that changes no durable state: a handler running
     the workflow body, a lease lapsing, time passing.
   - The workflow body is left free (`FreeBody`), except that it can only suspend on a timer, as
     `waitUntil` does. This checks the engine's protocol against any workflow code.
   - The trace is accepted if some behaviour consumes every event. Otherwise the report names the
     first write the model could not explain.

The model's constants come from the trace: gate values and the retry limit from the header, and
signal, start and renewal budgets from counting events.

## Running it

```bash
./gradlew :test --tests 'com.airbnb.skipper.integtest.*' -PskipperTraceDir=/tmp/traces
TLA2TOOLS=/path/to/tla2tools.jar formal/tla/trace/check_traces.sh --self-test /tmp/traces
```

The script prints one line per workflow (`accepted`, `REJECTED` or `skipped`) and exits 1 on any
rejection. `JOBS` sets how many TLC runs happen in parallel.

## The self-test

A checker that accepts everything would also report all green. `--self-test` guards against that.
After the real traces pass, `mutants.py` corrupts every accepted trace in five ways, and each
mutant must be rejected:
- bump a workflow version;
- change a workflow status;
- bump a task version;
- drop a write that a later write depends on;
- swap two writes.

A surviving mutant means the checker is too loose, or the corruption happens to be another run the
engine could have produced. Either way, it needs a look.

## What it found so far

Running it the first time turned up six places where the model didn't describe the code. They were
fixed in the model:

| The code | The model had |
|---|---|
| `createWorkflow` inserts `RUNNING` | `CREATED` |
| A persist moves a timer only as `Timer.Status.canTransitionTo` allows | any transition |
| A retry with `taskUnexpectedErrorRetryDelay = 0` is due at once | retries always backed off |
| A terminal workflow that runs again is persisted again, which bumps its version | the run was skipped |
| Signals take the in-memory path when `FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER` is off | signals always went to the scheduler |
| Callers invoke the workflow method again to read the result | one start call |

## Limits

- **Timing.** The recorder's lock serialises store calls, which can hide races that happen at full
  speed. It also slows tests down; the SQLite integration suite runs about as fast, but
  timing-sensitive tests may behave differently.
- **Rejected writes aren't checked.** A write that changed nothing the model tracks, such as a lost
  optimistic lock, is consumed without checking that the model agrees it would fail.
- **One workflow at a time.** Each workflow's run is checked on its own; workers shared across
  workflows are not modelled.
