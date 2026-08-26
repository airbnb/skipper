---
title: Persistence & Replay
description: How a state machine is persisted and replayed, and the guarantees you can rely on as an author.
section: State Machine
order: 43
---

This page describes how the framework persists a state machine, what gets replayed on every
workflow execution, and what guarantees you can rely on as an author. If you are new to Skipper's
replay model, read the [Skipper core concepts](/docs/core-concepts/) first — this page assumes
familiarity with workflow replay, action checkpointing, and the distinction between `@StateField`
and Actions.

## What gets persisted

A state machine instance has two kinds of durable data:

1. **A compact runtime blob** stored in a single `@StateField` on the base class
   (`SkipperStateMachine.persistedState`). The blob holds the event log, the current state's
   entry timestamp, and metadata needed to reconstruct the runtime. It is encoded as Smile
   (binary JSON) and compressed with Zstd, plus dictionaries for repeated state/event names and
   nanosecond timestamps stored as primitives. Typical sizes are small even for long-running
   machines.

2. **Compact journal segments** stored as action checkpoint results via
   `StateMachineCheckpoint`. Each segment captures one of:
   - A transition start, progress marker, or completion.
   - A `timeout` firing.
   - An `after` hook firing.
   - A middleware lifecycle callback span.
   - A handler-start or hook-start timestamp marker.

   These segments are byte arrays wrapped in a serializable `StateMachineJournalSegment` envelope
   so Skipper's action serde can store them. On replay, Skipper returns the persisted bytes
   instead of re-running the journal action, so timestamps and spans always reflect the **first
   durable execution**, not the replay attempt.

This split is deliberate. The runtime blob is what the event loop reads to know "what events
have I seen and what is my current state". The journal segments are what the admin UI reads to
draw the transition timeline. Both stay compact because state machine histories can otherwise
balloon — large workflows can approach hundreds of KB with naive serialization.

## Why two checkpoint modes

Skipper Actions can use one of two checkpoint modes (see the
[Skipper Workflow API](/docs/workflow-api/)). The framework uses:

| Record | Mode | Reason |
|--------|------|--------|
| Transition start | `IMMEDIATE_CHECKPOINT` | Preserves the first-attempt start timestamp across retries |
| Transition progress markers | `IMMEDIATE_CHECKPOINT` | Same reason |
| Transition completion | `IMMEDIATE_CHECKPOINT` | Same reason |
| Timeout firing | `IMMEDIATE_CHECKPOINT` | Anchored before timeout-handler retries |
| `after` hook start marker | `IMMEDIATE_CHECKPOINT` | Anchored before hook retries |
| Middleware action record | `IMMEDIATE_CHECKPOINT` | Anchored before downstream retries |
| `after` hook completion record | `EVENTUAL_CHECKPOINT` | Commits with workflow state |
| Ignored-transition record | `EVENTUAL_CHECKPOINT` | Commits with workflow state |

These are **framework-owned** records. Your own `@Execute` Actions can choose either mode (the
default is whatever you set on `SkipperConfig`). Use `IMMEDIATE_CHECKPOINT` when the side
effect cannot be safely retried in the gap between earlier successful actions and the next
workflow update.

## Replay correctness

On every workflow execution — first run, signal-driven wakeup, scheduler resume, post-crash retry —
the event loop:

1. Starts from `initialState`.
2. Loads the runtime blob from `@StateField`.
3. Replays every persisted event in order from index 0, re-running each state's handlers.
4. Returns the materialized current state.

This is the core invariant: **the event log is append-only, and replay always starts from
`initialState`**. Whatever your handlers compute on event N must be deterministic with respect to
events 0..N-1 and the workflow input.

What this means for you in practice:

- **Anything Skipper checkpoints is replay-safe.** That is the same contract as a plain Skipper
  workflow: `@Execute` Action invocations, explicit `checkpoint { }` blocks, and `waitUntil`
  calls that were previously satisfied all return immediately from their persisted checkpoint
  result on replay rather than re-executing. As long as your handler bodies use those primitives
  for every side effect, replay will reach the same logical point without duplicating work.
- **Handler bodies, `onEntry`, `onExit`, `after`, and `timeout` callbacks re-execute on every
  replay.** Their bodies are **not** checkpointed — only the Action calls and `checkpoint { }`
  blocks inside them are. Treat the surrounding code as pure orchestration; route every side
  effect through an Action or a named `checkpoint(name, block)`.
- **Local `var` fields are reset on each replay and rebuilt deterministically from the event
  stream.** Use them for counters, sets, and flags that drive transition decisions. Drive
  transition decisions from a local `var`, not from a `@StateField`.
- **`@StateField` values are restored from persisted state.** Use them only for data you need to
  expose via `@QueryMethod` or persist alongside Actions.
- **Idempotent mutation is required for `@StateField` collections.** Handlers re-run; mutations
  must produce the same result twice.

## Retry behavior

If an `onEntry` hook runs several Actions and a later Action fails retryably, Skipper persists
earlier successful `EVENTUAL_CHECKPOINT` results when it transitions the workflow to
`TRANSIENT_ERROR`. On retry, those successful Actions replay from checkpoint and do not
re-execute; the failed Action retries from where it was.

If the process crashes before dirty checkpoints flush, earlier `EVENTUAL_CHECKPOINT` Actions can
execute again, so they still need to be idempotent or use `IMMEDIATE_CHECKPOINT`.

Mixing modes is supported. Immediate Action checkpoints can become durable before the state
entry completes. On retry, the admin timeline uses immediate transition and phase-start journals
as the durable envelope, then merges completion back into those same records — successful
checkpoints from earlier attempts stay scoped under the same transition span instead of being
rendered later in the timeline.

## Internal collaborators

The public `SkipperStateMachine` facade delegates the replay loop, persistence, transition
execution, and journal checkpointing to small package-private classes. You will not interact
with them directly, but knowing they exist helps when reading stack traces or admin output.

| Class | Responsibility |
|-------|----------------|
| `StateMachineEventLoop` | Deterministic replay from the initial state through the durable event log. |
| `StateMachineTransitionExecutor` | One transition attempt: handler span, middleware, hooks, and transition progress records. |
| `StateMachineJournal` | Compact journal segment encoding, checkpoint-mode choice, and replay-safe upserts. |
| `StateMachineRuntimeStore` | Decode/cache/persist for the compact `@StateField` blob. |
| `StateMachineStateCodec` | Pure Smile + Zstd snapshot/journal format and materialization helpers. |
| `StateMachineCheckpoint` | The `Actions` subclass that holds the journal-write `@Execute` methods. |

## Format versioning & decode tolerance

The compact blob and each journal segment carry a format version. Decode is **floor-guarded, not
exact**: it accepts any version `>= MIN_SUPPORTED_*` with no upper bound (`StateMachineStateCodec`).
This has two consequences you can rely on:

- **Rolling deploys are safe in both directions.** A pod running older code keeps decoding blobs that
  a newer pod rewrote, and vice versa, as long as new fields are additive (nullable/defaulted) — the
  Smile mapper drops unknown properties (`FAIL_ON_UNKNOWN_PROPERTIES=false`).
- **The admin view never 500s on an in-flight instance during a format change.** Because
  `getAdminSnapshot()` decodes through the same path, a tolerant decode keeps the admin UI rendering
  older and newer instances alike.

Framework maintainers: add new snapshot/journal fields as nullable/defaulted with
`@JsonInclude(NON_NULL)` and a pinned `@JsonProperty` wire key — no version bump needed. Bump a
`*_WRITER_VERSION` only for a reader-visible semantic change, and raise a `MIN_SUPPORTED_*` only to
retire an old format after verifying no in-flight instances remain below it. Note that state and
event **names** are resolved separately (`resolveStateName`/`resolveEventTypeAlias`) and still fail
loudly on an unknown value.

> **Adding a new persisted enum constant is not free.** The records embed
> `TriggerKind`/`TransitionOutcome` as **non-null** fields, so an old reader that meets an unknown
> enum value still fails to deserialize (the mapper produces `null`, which the non-null field
> rejects). The mapper sets `READ_UNKNOWN_ENUM_VALUES_AS_NULL` so this becomes safe *once a field is
> made nullable*, but until then, introducing a new constant requires first shipping a reader that
> tolerates it (make the field nullable, or stage the constant behind a version) before any writer
> emits it.

## Storage limits

State-machine payloads are deliberately compact. The runtime blob and each journal segment use
Smile + Zstd; repeated state and event names share dictionaries; timestamps are stored as
primitives. Even so, very high-throughput state machines can produce a lot of data over time.

If you need to project history to an external system (data warehouse, search index), do it from
your middleware's `afterTransition` hook — do not lean on the runtime blob as long-term storage.

For evolution rules — which changes to a live state machine are safe and which are breaking — see
**[Evolution](/docs/state-machine/evolution/)**. Skipper's own checkpoint-identity rules
([Skipper versioning guide](/docs/versioning/)) apply because the state machine is a Skipper workflow
underneath — but the DSL adds its **own** identity surface on top (states keyed by enum name,
after-hooks/timeouts keyed per state, events keyed by class) with its own breaking/non-breaking
rules. Read the [Evolution](/docs/state-machine/evolution/) page for the state-machine-specific
matrix; do not assume the plain-workflow rules are the whole story here.
