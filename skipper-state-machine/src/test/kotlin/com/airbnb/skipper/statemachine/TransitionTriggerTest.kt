package com.airbnb.skipper.statemachine

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the [TransitionTrigger] sealed class.
 *
 * Verifies equality, hashCode, pattern matching (when), covariance of the `out EventT`
 * type parameter, and the three subtypes: [TransitionTrigger.Event],
 * [TransitionTrigger.Timeout], and [TransitionTrigger.AutoTransition].
 */
class TransitionTriggerTest {
    sealed class TestEvent {
        object Go : TestEvent()

        object Stop : TestEvent()
    }

    // ── Event ──

    @Test
    fun `Event - equal when same event`() {
        val t1 = TransitionTrigger.Event(TestEvent.Go)
        val t2 = TransitionTrigger.Event(TestEvent.Go)
        assertThat(t1).isEqualTo(t2)
    }

    @Test
    fun `Event - not equal when different event`() {
        val t1 = TransitionTrigger.Event(TestEvent.Go)
        val t2 = TransitionTrigger.Event(TestEvent.Stop)
        assertThat(t1).isNotEqualTo(t2)
    }

    @Test
    fun `Event - hashCode consistent with equals`() {
        val t1 = TransitionTrigger.Event(TestEvent.Go)
        val t2 = TransitionTrigger.Event(TestEvent.Go)
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode())
    }

    @Test
    fun `Event - extracts wrapped event`() {
        val trigger = TransitionTrigger.Event(TestEvent.Stop)
        assertThat(trigger.event).isEqualTo(TestEvent.Stop)
    }

    @Test
    fun `Event - is instance of TransitionTrigger`() {
        val trigger: TransitionTrigger<TestEvent> = TransitionTrigger.Event(TestEvent.Go)
        assertThat(trigger).isInstanceOf(TransitionTrigger::class.java)
        assertThat(trigger).isInstanceOf(TransitionTrigger.Event::class.java)
    }

    // ── Timeout ──

    @Test
    fun `Timeout - equal when same duration`() {
        val t1 = TransitionTrigger.Timeout(Duration.ofDays(7))
        val t2 = TransitionTrigger.Timeout(Duration.ofDays(7))
        assertThat(t1).isEqualTo(t2)
    }

    @Test
    fun `Timeout - not equal when different duration`() {
        val t1 = TransitionTrigger.Timeout(Duration.ofDays(7))
        val t2 = TransitionTrigger.Timeout(Duration.ofDays(14))
        assertThat(t1).isNotEqualTo(t2)
    }

    @Test
    fun `Timeout - hashCode consistent with equals`() {
        val t1 = TransitionTrigger.Timeout(Duration.ofDays(7))
        val t2 = TransitionTrigger.Timeout(Duration.ofDays(7))
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode())
    }

    @Test
    fun `Timeout - extracts duration`() {
        val trigger = TransitionTrigger.Timeout(Duration.ofHours(48))
        assertThat(trigger.duration).isEqualTo(Duration.ofHours(48))
    }

    @Test
    fun `Timeout - is instance of TransitionTrigger`() {
        val trigger: TransitionTrigger<Nothing> = TransitionTrigger.Timeout(Duration.ofDays(1))
        assertThat(trigger).isInstanceOf(TransitionTrigger::class.java)
        assertThat(trigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
    }

    // ── AutoTransition ──

    @Test
    fun `AutoTransition - is a singleton`() {
        assertThat(TransitionTrigger.AutoTransition).isSameAs(TransitionTrigger.AutoTransition)
    }

    @Test
    fun `AutoTransition - equals itself`() {
        assertThat(TransitionTrigger.AutoTransition).isEqualTo(TransitionTrigger.AutoTransition)
    }

    @Test
    fun `AutoTransition - is instance of TransitionTrigger`() {
        val trigger: TransitionTrigger<Nothing> = TransitionTrigger.AutoTransition
        assertThat(trigger).isInstanceOf(TransitionTrigger::class.java)
        assertThat(trigger).isInstanceOf(TransitionTrigger.AutoTransition::class.java)
    }

    // ── Cross-type inequality ──

    @Test
    fun `Event is not equal to Timeout`() {
        val event: TransitionTrigger<TestEvent> = TransitionTrigger.Event(TestEvent.Go)
        val timeout: TransitionTrigger<TestEvent> = TransitionTrigger.Timeout(Duration.ofDays(7))
        assertThat(event).isNotEqualTo(timeout)
    }

    @Test
    fun `Event is not equal to AutoTransition`() {
        val event: TransitionTrigger<TestEvent> = TransitionTrigger.Event(TestEvent.Go)
        val auto: TransitionTrigger<TestEvent> = TransitionTrigger.AutoTransition
        assertThat(event).isNotEqualTo(auto)
    }

    @Test
    fun `Timeout is not equal to AutoTransition`() {
        val timeout: TransitionTrigger<TestEvent> = TransitionTrigger.Timeout(Duration.ofDays(7))
        val auto: TransitionTrigger<TestEvent> = TransitionTrigger.AutoTransition
        assertThat(timeout).isNotEqualTo(auto)
    }

    // ── Pattern matching (when) ──

    @Test
    fun `when expression exhaustively matches all trigger types`() {
        val triggers: List<TransitionTrigger<TestEvent>> = listOf(
            TransitionTrigger.Event(TestEvent.Go),
            TransitionTrigger.Timeout(Duration.ofDays(7)),
            TransitionTrigger.AutoTransition,
            TransitionTrigger.InitialState,
        )

        val results = triggers.map { trigger ->
            when (trigger) {
                is TransitionTrigger.Event -> "event:${trigger.event::class.simpleName}"
                is TransitionTrigger.Timeout -> "timeout:${trigger.duration}"
                is TransitionTrigger.AutoTransition -> "auto"
                is TransitionTrigger.InitialState -> "initial"
            }
        }

        assertThat(results).containsExactly(
            "event:Go",
            "timeout:PT168H",
            "auto",
            "initial",
        )
    }

    @Test
    fun `when expression extracts Event payload correctly`() {
        val trigger: TransitionTrigger<TestEvent> = TransitionTrigger.Event(TestEvent.Stop)
        val result = when (trigger) {
            is TransitionTrigger.Event -> trigger.event
            is TransitionTrigger.Timeout -> null
            is TransitionTrigger.AutoTransition -> null
            is TransitionTrigger.InitialState -> null
        }
        assertThat(result).isEqualTo(TestEvent.Stop)
    }

    @Test
    fun `when expression extracts Timeout duration correctly`() {
        val trigger: TransitionTrigger<TestEvent> = TransitionTrigger.Timeout(Duration.ofMinutes(30))
        val result = when (trigger) {
            is TransitionTrigger.Event -> null
            is TransitionTrigger.Timeout -> trigger.duration
            is TransitionTrigger.AutoTransition -> null
            is TransitionTrigger.InitialState -> null
        }
        assertThat(result).isEqualTo(Duration.ofMinutes(30))
    }

    // ── Covariance ──

    @Test
    fun `Event is covariant - subtype event can be assigned to supertype trigger`() {
        // TestEvent.Go is a TestEvent, so TransitionTrigger<TestEvent.Go> is a subtype of TransitionTrigger<TestEvent>
        val specific: TransitionTrigger<TestEvent.Go> = TransitionTrigger.Event(TestEvent.Go)
        val general: TransitionTrigger<TestEvent> = specific
        assertThat(general).isEqualTo(TransitionTrigger.Event(TestEvent.Go))
    }

    @Test
    fun `Timeout uses Nothing type parameter - assignable to any TransitionTrigger`() {
        val timeout: TransitionTrigger<Nothing> = TransitionTrigger.Timeout(Duration.ofDays(1))
        // Nothing is a subtype of every type, so this assignment should work via covariance
        val anyTrigger: TransitionTrigger<TestEvent> = timeout
        assertThat(anyTrigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
    }

    @Test
    fun `AutoTransition uses Nothing type parameter - assignable to any TransitionTrigger`() {
        val auto: TransitionTrigger<Nothing> = TransitionTrigger.AutoTransition
        val anyTrigger: TransitionTrigger<TestEvent> = auto
        assertThat(anyTrigger).isEqualTo(TransitionTrigger.AutoTransition)
    }

    // ── toString ──

    @Test
    fun `Event toString contains the event`() {
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        assertThat(trigger.toString()).contains("Go")
    }

    @Test
    fun `Timeout toString contains the duration`() {
        val trigger = TransitionTrigger.Timeout(Duration.ofDays(7))
        assertThat(trigger.toString()).contains("PT168H")
    }

    @Test
    fun `AutoTransition toString is stable`() {
        assertThat(TransitionTrigger.AutoTransition.toString()).isNotEmpty()
    }
}
