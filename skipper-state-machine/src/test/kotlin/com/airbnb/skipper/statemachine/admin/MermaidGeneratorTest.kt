package com.airbnb.skipper.statemachine.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MermaidGeneratorTest {
    /** Converts epoch millis to [PreciseTimestampView] for test readability. */
    private fun tsView(ms: Long) = PreciseTimestampView(ms / 1000, (ms % 1000).toInt() * 1_000_000)

    /** Builds a [TimeRangeView] from millisecond timestamps for test readability. */
    private fun timeRange(
        startMs: Long,
        endMs: Long
    ): TimeRangeView {
        val durationMs = endMs - startMs
        return TimeRangeView(
            start = PreciseTimestampView(startMs / 1000, (startMs % 1000).toInt() * 1_000_000),
            end = PreciseTimestampView(endMs / 1000, (endMs % 1000).toInt() * 1_000_000),
            durationNanos = durationMs * 1_000_000,
        )
    }

    @Test
    fun `generates diagram with states events and payloads in chronological order`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("CLOSED", tsView(3000L)),
        )
        val events = listOf(
            EventEntry("Revoked", "payload", tsView(2000L)),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(states, events, emptyList())

        assertThat(diagram).isEqualTo(
            """
            sequenceDiagram
                participant Ext as Events
                participant SM as State Machine
                Note over SM: → OPEN (00:00:01) [2s]
                Ext->>SM: Revoked (00:00:02)
                Note right of Ext: payload
                Note over SM: → CLOSED (00:00:03)
            """.trimIndent(),
        )
    }

    @Test
    fun `after hooks arrive from Timer participant with timestamps`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("FULFILLED", tsView(5000L)),
        )
        val afterHooks = listOf(
            AfterHookEntry("PT168H", tsView(3000L), null),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(states, emptyList(), afterHooks)

        assertThat(diagram).contains("participant Timer as Timers")
        assertThat(diagram).contains("Timer->>SM: after(PT168H) (00:00:03)")
        assertThat(diagram).doesNotContain("participant Ext")
    }

    @Test
    fun `timeouts arrive from Timer participant with timestamps`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("EXPIRED", tsView(5000L)),
        )
        val timeouts = listOf(
            TimeoutEntry("PT336H", tsView(4500L)),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            timeouts,
        )

        assertThat(diagram).contains("participant Timer as Timers")
        assertThat(diagram).contains("Timer->>SM: timeout(PT336H) (00:00:04)")
        assertThat(diagram.indexOf("→ OPEN")).isLessThan(diagram.indexOf("timeout"))
        assertThat(diagram.indexOf("timeout")).isLessThan(diagram.indexOf("→ EXPIRED"))
    }

    @Test
    fun `actions show iteration and duration`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val actions = listOf(
            ActionEntry("StatusActions", "updateStatus", 0, tsView(1500L), tsView(1600L), true, false, null, null),
            ActionEntry("NotifyActions", "sendNotification", 0, tsView(2000L), tsView(2100L), false, false, null, null),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actions,
        )

        assertThat(diagram).contains("participant Act as Actions")
        assertThat(diagram).contains("SM->>Act: StatusActions.updateStatus() #0 (00:00:01)")
        assertThat(diagram).contains("Act-->>SM: ok [100ms]")
        assertThat(diagram).contains("SM->>Act: NotifyActions.sendNotification() #0 (00:00:02)")
        assertThat(diagram).contains("Act-->>SM: error [100ms]")
    }

    @Test
    fun `returns empty string for empty input`() {
        val diagram =
            MermaidGenerator.generateSequenceDiagram(emptyList(), emptyList(), emptyList())

        assertThat(diagram).isEmpty()
    }

    @Test
    fun `omits unused participants`() {
        val states = listOf(
            StateEntry("INITIAL", tsView(1000L)),
            StateEntry("DONE", tsView(2000L)),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(states, emptyList(), emptyList())

        assertThat(diagram).contains("participant SM as State Machine")
        assertThat(diagram).doesNotContain("participant Ext")
        assertThat(diagram).doesNotContain("participant Act")
        assertThat(diagram).doesNotContain("participant Timer")
    }

    @Test
    fun `compensation actions show looping arrow prefix`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val actions = listOf(
            ActionEntry("SomeActions", "compensateDoSomething", 0, tsView(1500L), tsView(1600L), true, true, null, null),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actions,
        )

        assertThat(diagram).contains("SM->>Act: ⟲ SomeActions.compensateDoSomething()")
    }

    @Test
    fun `comprehensive timeline with all entry types`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("OPEN", tsView(2500L)),
            StateEntry("CLOSED", tsView(7000L)),
            StateEntry("EXPIRED", tsView(9000L)),
        )
        val events = listOf(
            EventEntry("ResponseCreated", null, tsView(2000L)),
            EventEntry("Revoked", null, tsView(6500L)),
        )
        val afterHooks = listOf(
            AfterHookEntry("PT168H", tsView(4000L), null),
        )
        val timeouts = listOf(
            TimeoutEntry("PT336H", tsView(8500L)),
        )
        val actions = listOf(
            ActionEntry("StatusActions", "markActive", 0, tsView(3000L), tsView(3100L), true, false, null, null),
            ActionEntry("NotifyActions", "notifyHost", 0, tsView(5000L), tsView(5200L), true, false, null, null),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            events,
            afterHooks,
            timeouts,
            actions,
        )

        assertThat(diagram).isEqualTo(
            """
            sequenceDiagram
                participant Timer as Timers
                participant Ext as Events
                participant SM as State Machine
                participant Act as Actions
                Note over SM: → OPEN (00:00:01) [1s]
                Ext->>SM: ResponseCreated (00:00:02)
                Note over SM: → OPEN (00:00:02) [4s]
                SM->>Act: StatusActions.markActive() #0 (00:00:03)
                Act-->>SM: ok [100ms]
                Timer->>SM: after(PT168H) (00:00:04)
                SM->>Act: NotifyActions.notifyHost() #0 (00:00:05)
                Act-->>SM: ok [200ms]
                Ext->>SM: Revoked (00:00:06)
                Note over SM: → CLOSED (00:00:07) [2s]
                Timer->>SM: timeout(PT336H) (00:00:08)
                Note over SM: → EXPIRED (00:00:09)
            """.trimIndent(),
        )
    }

    @Test
    fun `null event payloads produce no note`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val events = listOf(EventEntry("Ping", null, tsView(2000L)))

        val diagram = MermaidGenerator.generateSequenceDiagram(states, events, emptyList())

        assertThat(diagram).contains("Ext->>SM: Ping (00:00:02)")
        assertThat(diagram).doesNotContain("Note right of Ext")
    }

    @Test
    fun `pending timers render as notes with Timer participant`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val pendingTimers = listOf(
            PendingTimerInfo("timeout", "PT336H", tsView(100_000L)),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            pendingTimers,
        )

        assertThat(diagram).contains("participant Timer as Timers")
        assertThat(diagram).contains("Note over Timer,Act: [Scheduled] timeout(PT336H) fires at")
    }

    @Test
    fun `multi-day workflow includes date in timestamps`() {
        val day1 = 1000L
        val day2 = 86_400_000L + 1000L
        val states = listOf(
            StateEntry("OPEN", tsView(day1)),
            StateEntry("CLOSED", tsView(day2)),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(states, emptyList(), emptyList())

        assertThat(diagram).contains("01-01 00:00:01")
        assertThat(diagram).contains("01-02 00:00:01")
    }

    @Test
    fun `formatDuration covers minutes hours and days`() {
        val states = listOf(StateEntry("OPEN", tsView(0L)))

        // minutes: 90_000ms = 1m 30s
        val actionsMin = listOf(
            ActionEntry("A", "m", 0, tsView(0L), tsView(90_000L), true, false, null, null),
        )
        val diagramMin = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actionsMin,
        )
        assertThat(diagramMin).contains("[1m 30s]")

        // hours: 3_661_000ms = 1h 1m 1s
        val actionsHr = listOf(
            ActionEntry("A", "h", 0, tsView(0L), tsView(3_661_000L), true, false, null, null),
        )
        val diagramHr = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actionsHr,
        )
        assertThat(diagramHr).contains("[1h 1m 1s]")

        // days: 90_061_000ms = 1d 1h 1m
        val actionsDays = listOf(
            ActionEntry("A", "d", 0, tsView(0L), tsView(90_061_000L), true, false, null, null),
        )
        val diagramDays = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actionsDays,
        )
        assertThat(diagramDays).contains("[1d 1h 1m]")
    }

    @Test
    fun `action without endTime shows no duration`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val actions = listOf(
            ActionEntry("A", "run", 0, tsView(1500L), null, true, false, null, null),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            emptyList(),
            actions,
        )

        assertThat(diagram).contains("Act-->>SM: ok")
        assertThat(diagram).doesNotContain("[")
    }

    @Test
    fun `payload with newlines is sanitized`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val events = listOf(EventEntry("Ev", "line1\nline2\rline3", tsView(2000L)))

        val diagram = MermaidGenerator.generateSequenceDiagram(states, events, emptyList())

        assertThat(diagram).contains("Note right of Ext: line1 line2line3")
    }

    @Test
    fun `long payload is truncated`() {
        val longPayload = "A".repeat(100)
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val events = listOf(EventEntry("Big", longPayload, tsView(2000L)))

        val diagram = MermaidGenerator.generateSequenceDiagram(states, events, emptyList())

        assertThat(diagram).contains("Note right of Ext: ${"A".repeat(60)}...")
        assertThat(diagram).doesNotContain("A".repeat(61))
    }

    @Test
    fun `ignored event renders cross-ended arrow`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = null,
                outcome = "IGNORE_EXPLICIT",
                triggerKind = "EVENT",
                triggerName = "Ping",
                span = timeRange(2000L, 2001L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).contains("Ext--xSM: Ping [ignored] (00:00:02)")
    }

    @Test
    fun `guard rejected event renders with reason`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = null,
                outcome = "IGNORE_GUARD",
                triggerKind = "EVENT",
                triggerName = "Capture",
                span = timeRange(2000L, 2001L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).contains("Ext--xSM: Capture [guard rejected] (00:00:02)")
    }

    @Test
    fun `no handler event renders with reason`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = null,
                outcome = "INVALID_NO_HANDLER",
                triggerKind = "EVENT",
                triggerName = "Unknown",
                span = timeRange(2000L, 2001L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).contains("Ext--xSM: Unknown [no handler] (00:00:02)")
    }

    /** Helper to create a minimal initial-entry transition for the first state. */
    private fun initialTransition(
        toState: String,
        spanMs: Long
    ) = TransitionLogEntryView(
        fromState = null,
        toState = toState,
        outcome = "TRANSITION_TO",
        triggerKind = "INITIAL",
        triggerName = "initial",
        span = timeRange(spanMs, spanMs + 1),
        handlerSpan = null,
        eventIndex = null,
        initialMiddlewareSpan = null,
        beforeMiddlewareSpan = null,
        onExitSpan = null,
        onEntrySpan = null,
        afterMiddlewareSpan = null,
        terminalMiddlewareSpan = null,
    )

    @Test
    fun `hook duration notes appear for successful transitions`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("CLOSED", tsView(2000L)),
        )
        val transitionLog = listOf(
            initialTransition("OPEN", 1000L),
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = "CLOSED",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "Close",
                span = timeRange(1500L, 2000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = timeRange(1600L, 1650L),
                onEntrySpan = timeRange(1900L, 1920L),
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).contains("Note over SM: onExit [50ms]")
        assertThat(diagram).contains("Note over SM: onEntry [20ms]")
        assertThat(diagram).contains("(transition: 500ms)")
        // onExit appears before the state note
        assertThat(diagram.indexOf("onExit")).isLessThan(diagram.indexOf("→ CLOSED"))
        // onEntry appears after the state note
        assertThat(diagram.indexOf("→ CLOSED")).isLessThan(diagram.indexOf("onEntry"))
    }

    @Test
    fun `zero duration hooks are not shown`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("CLOSED", tsView(2000L)),
        )
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = "CLOSED",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "Close",
                span = timeRange(1500L, 2000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = timeRange(1600L, 1600L),
                onEntrySpan = timeRange(1900L, 1900L),
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).doesNotContain("onExit")
        assertThat(diagram).doesNotContain("onEntry")
    }

    @Test
    fun `positional matching handles re-entry states correctly`() {
        val states = listOf(
            StateEntry("A", tsView(1000L)),
            StateEntry("B", tsView(2000L)),
            StateEntry("C", tsView(3000L)),
            StateEntry("B", tsView(4000L)),
        )
        val transitionLog = listOf(
            // A→B: onExit 10ms
            TransitionLogEntryView(
                fromState = "A",
                toState = "B",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "GoToB",
                span = timeRange(1500L, 2000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = timeRange(1600L, 1610L),
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
            // B→C: onExit 20ms
            TransitionLogEntryView(
                fromState = "B",
                toState = "C",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "GoToC",
                span = timeRange(2500L, 3000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = timeRange(2600L, 2620L),
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
            // C→B: onExit 30ms
            TransitionLogEntryView(
                fromState = "C",
                toState = "B",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "BackToB",
                span = timeRange(3500L, 4000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = timeRange(3600L, 3630L),
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        // The first B entry (index 1) should match A→B (10ms onExit)
        // The C entry should match B→C (20ms onExit)
        // The second B entry (index 3) should match C→B (30ms onExit)
        val lines = diagram.lines()
        val onExitLines = lines.filter { it.contains("onExit") }
        assertThat(onExitLines).hasSize(3)
        assertThat(onExitLines[0]).contains("[10ms]")
        assertThat(onExitLines[1]).contains("[20ms]")
        assertThat(onExitLines[2]).contains("[30ms]")
    }

    @Test
    fun `empty transition log preserves existing behavior`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("CLOSED", tsView(3000L)),
        )
        val events = listOf(EventEntry("Revoked", null, tsView(2000L)))

        val withLog = MermaidGenerator.generateSequenceDiagram(
            states,
            events,
            emptyList(),
            transitionLog = emptyList(),
        )
        val without = MermaidGenerator.generateSequenceDiagram(states, events, emptyList())

        assertThat(withLog).isEqualTo(without)
    }

    @Test
    fun `ignored events cause Ext participant to appear even without regular events`() {
        val states = listOf(StateEntry("OPEN", tsView(1000L)))
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = null,
                outcome = "IGNORE_GUARD",
                triggerKind = "EVENT",
                triggerName = "Blocked",
                span = timeRange(2000L, 2001L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
        )
        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )
        assertThat(diagram).contains("participant Ext as Events")
    }

    @Test
    fun `middleware span notes do not require action checkpoints`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("CLOSED", tsView(3000L)),
        )
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = null,
                toState = "OPEN",
                outcome = "TRANSITION_TO",
                triggerKind = "INITIAL_STATE",
                triggerName = "InitialState",
                span = timeRange(1000L, 1100L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = "CLOSED",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "Closed",
                span = timeRange(2000L, 3000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = timeRange(2100L, 2110L),
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = timeRange(2900L, 2915L),
                terminalMiddlewareSpan = null,
            ),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            transitionLog = transitionLog,
        )

        assertThat(diagram).doesNotContain("participant Act as Actions")
        assertThat(diagram).contains("Note over SM: Before MW [10ms]")
        assertThat(diagram).contains("Note over SM: After MW [15ms]")
    }

    @Test
    fun `middleware action checkpoints render alongside transition span notes`() {
        val states = listOf(
            StateEntry("OPEN", tsView(1000L)),
            StateEntry("DECLINED", tsView(3000L)),
        )
        val middlewareActions = listOf(
            ActionEntry(
                "OpportunityBoardResponseMetricsMiddleware",
                "beforeTransitionMiddleware",
                0,
                tsView(60_000L),
                tsView(60_001L),
                true,
                false,
                null,
                null,
            ),
            ActionEntry(
                "OpportunityBoardResponseMetricsMiddleware",
                "afterTransitionMiddleware",
                0,
                tsView(60_002L),
                tsView(60_003L),
                true,
                false,
                null,
                null,
            ),
        )
        val transitionLog = listOf(
            TransitionLogEntryView(
                fromState = null,
                toState = "OPEN",
                outcome = "TRANSITION_TO",
                triggerKind = "INITIAL_STATE",
                triggerName = "InitialState",
                span = timeRange(1000L, 1100L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = null,
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = null,
                terminalMiddlewareSpan = null,
            ),
            TransitionLogEntryView(
                fromState = "OPEN",
                toState = "DECLINED",
                outcome = "TRANSITION_TO",
                triggerKind = "EVENT",
                triggerName = "LeadStatusUpdate",
                span = timeRange(2000L, 3000L),
                handlerSpan = null,
                eventIndex = null,
                initialMiddlewareSpan = null,
                beforeMiddlewareSpan = timeRange(2100L, 2110L),
                onExitSpan = null,
                onEntrySpan = null,
                afterMiddlewareSpan = timeRange(2900L, 2915L),
                terminalMiddlewareSpan = null,
            ),
        )

        val diagram = MermaidGenerator.generateSequenceDiagram(
            states,
            emptyList(),
            emptyList(),
            actionHistory = middlewareActions,
            transitionLog = transitionLog,
        )

        assertThat(diagram).contains("participant Act as Actions")
        assertThat(diagram).contains(
            "SM->>Act: OpportunityBoardResponseMetricsMiddleware.beforeTransitionMiddleware() #0",
        )
        assertThat(diagram).contains(
            "SM->>Act: OpportunityBoardResponseMetricsMiddleware.afterTransitionMiddleware() #0",
        )
        assertThat(diagram).contains("Note over SM: Before MW [10ms]")
        assertThat(diagram).contains("Note over SM: After MW [15ms]")
    }
}
