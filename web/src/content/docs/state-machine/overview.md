---
title: Overview
description: A declarative Kotlin DSL for building durable state machines on top of Skipper — states, events, transitions, timers, and a built-in admin UI.
section: State Machine
order: 40
---

**Skipper State Machine** is a companion library: a declarative Kotlin DSL for building durable
state machines on top of Skipper. You declare states, events, and transitions; the framework
handles deterministic event processing, compact persistence, checkpointed side effects, and an
admin UI that visualizes every transition an instance ever made.

If your workflow is naturally described as *"in state X, when event Y arrives, do Z and move to
state W"* — and you'd otherwise express that as a chain of `waitUntil` calls and `@SignalMethod`s
branching on `@StateField` flags — this library is for you.

## Show me the code

A moderated support ticket: it opens in `OPEN`, waits for an agent, and reaches one of three
terminal states. A reminder fires after 24 hours; the whole machine times out after 5 days.

<svg viewBox="0 0 720 320" role="img" aria-label="Ticket state machine: OPEN transitions to AWAITING_AGENT on AgentAssigned, to EXPIRED on a 5-day timeout, and sends a reminder after 24 hours; AWAITING_AGENT transitions to RESOLVED on Resolution and to ESCALATED on Escalate or a 2-day timeout." style="width:100%;height:auto;max-width:700px;display:block;margin:1.4rem auto;">
  <defs>
    <marker id="smArrowGrey" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#5b6473"/></marker>
    <marker id="smArrowOrange" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#F05A28"/></marker>
  </defs>
  <g font-family="ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif">
    <circle cx="18" cy="146" r="5" fill="#1b2342"/>
    <line x1="23" y1="146" x2="38" y2="146" stroke="#5b6473" stroke-width="1.6" marker-end="url(#smArrowGrey)"/>
    <path d="M82,124 C80,96 120,96 118,124" fill="none" stroke="#F05A28" stroke-width="1.4" stroke-dasharray="4 3" marker-end="url(#smArrowOrange)"/>
    <text x="100" y="88" text-anchor="middle" font-size="11" fill="#F05A28" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">after 24h → reminder</text>
    <line x1="160" y1="146" x2="286" y2="146" stroke="#5b6473" stroke-width="1.6" marker-end="url(#smArrowGrey)"/>
    <text x="223" y="140" text-anchor="middle" font-size="12" fill="#1b2342" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">AgentAssigned</text>
    <line x1="100" y1="168" x2="100" y2="237" stroke="#F05A28" stroke-width="1.6" marker-end="url(#smArrowOrange)"/>
    <text x="112" y="206" text-anchor="start" font-size="12" fill="#F05A28" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">timeout 5d</text>
    <line x1="454" y1="136" x2="563" y2="93" stroke="#5b6473" stroke-width="1.6" marker-end="url(#smArrowGrey)"/>
    <text x="505" y="104" text-anchor="middle" font-size="12" fill="#1b2342" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">Resolution</text>
    <line x1="454" y1="156" x2="563" y2="206" stroke="#5b6473" stroke-width="1.6" marker-end="url(#smArrowGrey)"/>
    <text x="498" y="172" text-anchor="middle" font-size="12" fill="#1b2342" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">Escalate</text>
    <text x="498" y="186" text-anchor="middle" font-size="11" fill="#F05A28" style="paint-order:stroke;stroke:#fff;stroke-width:3px;">or timeout 2d</text>
    <rect x="40" y="124" width="120" height="44" rx="9" fill="#ffffff" stroke="#2B388F" stroke-width="1.6"/>
    <text x="100" y="146" text-anchor="middle" dominant-baseline="central" font-size="14" font-weight="600" fill="#1b2342">OPEN</text>
    <rect x="290" y="124" width="164" height="44" rx="9" fill="#ffffff" stroke="#2B388F" stroke-width="1.6"/>
    <text x="372" y="146" text-anchor="middle" dominant-baseline="central" font-size="13" font-weight="600" fill="#1b2342">AWAITING_AGENT</text>
    <rect x="566" y="64" width="128" height="44" rx="9" fill="#eef0fb" stroke="#2B388F" stroke-width="2"/>
    <rect x="570" y="68" width="120" height="36" rx="6" fill="none" stroke="#2B388F" stroke-width="1"/>
    <text x="630" y="86" text-anchor="middle" dominant-baseline="central" font-size="13" font-weight="600" fill="#1b2342">RESOLVED</text>
    <rect x="566" y="192" width="128" height="44" rx="9" fill="#eef0fb" stroke="#2B388F" stroke-width="2"/>
    <rect x="570" y="196" width="120" height="36" rx="6" fill="none" stroke="#2B388F" stroke-width="1"/>
    <text x="630" y="214" text-anchor="middle" dominant-baseline="central" font-size="13" font-weight="600" fill="#1b2342">ESCALATED</text>
    <rect x="40" y="240" width="120" height="44" rx="9" fill="#eef0fb" stroke="#2B388F" stroke-width="2"/>
    <rect x="44" y="244" width="112" height="36" rx="6" fill="none" stroke="#2B388F" stroke-width="1"/>
    <text x="100" y="262" text-anchor="middle" dominant-baseline="central" font-size="13" font-weight="600" fill="#1b2342">EXPIRED</text>
  </g>
</svg>

*Dark arrows are events (external signals); orange arrows are time-driven (`timeout` / `after`).
`RESOLVED`, `ESCALATED`, and `EXPIRED` are terminal (double border).*

```kotlin
// 1. States — a plain enum
enum class TicketState { OPEN, AWAITING_AGENT, RESOLVED, ESCALATED, EXPIRED }

// 2. Events — a sealed hierarchy; `object` for no payload, `data class` for payload
sealed class TicketEvent : StateMachineEvent() {
    data class AgentAssigned(val agentId: Long) : TicketEvent()
    data class Resolution(val notes: String) : TicketEvent()
    object Escalate : TicketEvent()
}

// 3. Input — passed to every handler and hook
data class TicketInput(val ticketId: Long, val reporterId: Long)

// 4. State machine
class TicketStateMachine :
    SkipperStateMachine<TicketState, TicketEvent, TicketInput>(TicketState.OPEN) {

    private val actions = actions<TicketActions>()

    override fun StateMachineBuilder<TicketState, TicketEvent, TicketInput>.define() {
        state(TicketState.OPEN) { handleOpen() }
        state(TicketState.AWAITING_AGENT) { handleAwaitingAgent() }
        state(TicketState.RESOLVED) { terminal() }
        state(TicketState.ESCALATED) { terminal() }
        state(TicketState.EXPIRED) { terminal() }
    }

    private fun StateBuilder<TicketState, TicketEvent, TicketInput>.handleOpen() {
        on<TicketEvent.AgentAssigned> { _, input ->
            actions.markAwaitingAgent(input.ticketId)
            transitionTo(TicketState.AWAITING_AGENT)
        }
        after(Duration.ofHours(24)) { input -> actions.sendReminder(input.reporterId) }
        timeout(Duration.ofDays(5)) { input ->
            actions.markExpired(input.ticketId)
            transitionTo(TicketState.EXPIRED)
        }
    }

    private fun StateBuilder<TicketState, TicketEvent, TicketInput>.handleAwaitingAgent() {
        on<TicketEvent.Resolution> { event, input ->
            actions.markResolved(input.ticketId, event.notes)
            transitionTo(TicketState.RESOLVED)
        }
        on<TicketEvent.Escalate> { _, input ->
            actions.markEscalated(input.ticketId)
            transitionTo(TicketState.ESCALATED)
        }
        timeout(Duration.ofDays(2)) { input ->
            actions.markEscalated(input.ticketId)
            transitionTo(TicketState.ESCALATED)
        }
    }
}
```

That's the whole machine — no `waitUntil`, no manual state-field branching, no hand-rolled
history. Skipper persists the event log and replays it deterministically on every restart, and the
built-in [Admin UI](/docs/state-machine/admin-ui/) shows each instance's full state and event
history, every transition, pending timer deadlines, an auto-generated Mermaid sequence diagram, and
a form to send events manually.

## Why use this instead of a raw workflow?

A raw Skipper workflow describes behavior imperatively with `waitUntil` and `@SignalMethod`. That's
perfect for linear flows, but gets noisy when the workflow branches on the order of incoming
signals, when many states share timeout/reminder semantics, or when operators need to see "what
state is this in right now" without reading code.

| Concern | Raw workflow | State Machine |
|---|---|---|
| Express "in state X, on event Y, do Z" | `if (state == X) { waitUntil(...); ... }` chains | `state(X) { on<Y> { ... transitionTo(Z) } }` |
| State timeout / reminders | Manual `waitUntil(condition, timeout)` plumbing | `timeout(d) { ... }` / `after(d) { ... }` |
| History (states, events, transitions) | DIY via `@StateField` lists | Built-in, durable, admin-viewable |
| Operator UI | Workflow action history only | Dedicated state view + Mermaid diagram |
| Cross-cutting metrics / audit | Hand-rolled in handler bodies | [Middleware](/docs/state-machine/middleware/) hooks |

## Key concepts

- **States** — a finite set of situations, modeled as a Kotlin `enum`.
- **Events** — the inputs that drive the machine, modeled as a sealed-class hierarchy
  (`object` for no payload, `data class` for payload). All extend `StateMachineEvent`.
- **Transitions** — the rules: "in state X, on event Y, do Z and move to W."
- **Hooks** — `onEntry` / `onExit` callbacks that fire when entering or leaving a state.
- **Timers** — `timeout(d)` drives a transition if no event arrives; `after(d)` runs a side effect
  at a point inside a state without changing state.
- **Middleware** — cross-cutting lifecycle hooks for metrics, audit, and alerting.
- **Terminal states** — entering a state marked `terminal()` completes the underlying workflow.

## How it relates to Skipper

A state machine is a *thin layer over a single Skipper workflow*. The framework compiles your DSL
into one workflow:

- One `@WorkflowMethod` — `execute(input)` runs the event loop until a terminal state.
- One `@SignalMethod` — `sendEvent(event)` appends to the durable event log and wakes the workflow.
- A few `@QueryMethod`s — `getState()`, `isTerminal()`, `getAdminSnapshot()`.
- A single `@StateField` holding a compact, compressed blob of the event log and metadata.

Because everything funnels through Skipper, all the standard guarantees apply: durable execution,
[action checkpointing](/docs/core-concepts/), [retries](/docs/error-handling/),
[compensation](/docs/compensation/). A `SkipperStateMachine` subclass *is* a `Workflow`, so you can
still add your own `@StateField`s, `@QueryMethod`s, and Actions when the DSL alone isn't enough.

## TransitionResult: `transitionTo` vs `stay` vs `ignore`

Every event handler returns a `TransitionResult`. The difference matters:

| Result | Hooks (onExit/onEntry) | Middleware | Timers reset | Use when |
|---|---|---|---|---|
| `transitionTo(S)` | yes | yes | yes | Moving to a different state |
| `stay()` | yes (re-enter same state) | yes | yes | Activity happened, same state — e.g. reset an inactivity timer |
| `ignore()` | no | no | no | The event is irrelevant here — a silent no-op |

`stay()` is a *full transition back to self*; `ignore()` is a *silent no-op*. Confusing the two is
a common subtle bug.

## Replay safety: `@StateField` vs local `var`

The single most important rule:

> **Do not use `@StateField` for variables that drive transition decisions** (counters,
> accumulators, flags). Use plain local `var` fields.

Skipper re-executes the workflow on every restart and event arrival; the event loop re-processes
the entire persisted event log from index 0. Local `var` fields rebuild deterministically from the
event sequence each time. `@StateField` values are restored from persisted state and can diverge.
Use `@StateField` only for data you expose via `@QueryMethod` or persist alongside Actions. See
[Persistence & Replay](/docs/state-machine/persistence/).

## Installation

State Machine ships as a separate artifact, `com.airbnb.skipper:skipper-state-machine`, layered on
top of `skipper-core` and released with the same version. Add it next to core:

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.airbnb.skipper:skipper-core:0.5.0")
  implementation("com.airbnb.skipper:skipper-state-machine:0.5.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.airbnb.skipper</groupId>
  <artifactId>skipper-state-machine</artifactId>
  <version>0.5.0</version>
</dependency>
```

The admin UI is part of this module, not a separate artifact. It uses the same Kotlin AllOpen
plugin Skipper requires, so your workflow and action classes are already `open`. To enable the
admin UI, register `StateMachineAdminResource` (a JAX-RS resource) with your server alongside
Skipper's own `AdminResource` — see [Admin UI](/docs/state-machine/admin-ui/).

## Next steps

- [Your First State Machine](/docs/state-machine/first-state-machine/) — build, invoke, and test one.
- [DSL Reference](/docs/state-machine/dsl-reference/) — every builder method and the validation rules.
- [Persistence & Replay](/docs/state-machine/persistence/) — the durability model.
- [Middleware](/docs/state-machine/middleware/) — cross-cutting lifecycle hooks.
- [Admin UI](/docs/state-machine/admin-ui/) — inspect any instance in the browser.
- [Evolution](/docs/state-machine/evolution/) — change a machine that has live instances.
