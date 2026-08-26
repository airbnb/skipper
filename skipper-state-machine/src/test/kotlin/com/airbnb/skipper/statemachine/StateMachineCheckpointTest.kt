package com.airbnb.skipper.statemachine

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Execute
import com.airbnb.skipper.internal.serde.SmartSerde
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StateMachineCheckpoint].
 *
 * Verifies the class structure, annotations, checkpoint modes, and pass-through compact segment
 * behavior. Replay safety through the Skipper engine is covered by [SkipperStateMachineTest].
 */
class StateMachineCheckpointTest {
    private val checkpoint = StateMachineCheckpoint()

    // ── Segment pass-through tests ──

    @Test
    fun `recordStateMachineJournalSegment returns provided segment`() {
        val segment = StateMachineJournalSegment(byteArrayOf(1, 2, 3))
        assertThat(checkpoint.recordStateMachineJournalSegment(segment)).isSameAs(segment)
    }

    @Test
    fun `recordStateMachineJournalSegmentEventually returns provided segment`() {
        val segment = StateMachineJournalSegment(byteArrayOf(1, 2, 3))
        assertThat(checkpoint.recordStateMachineJournalSegmentEventually(segment)).isSameAs(segment)
    }

    @Test
    fun `StateMachineJournalSegment equality uses byte content`() {
        val left = StateMachineJournalSegment(byteArrayOf(1, 2, 3))
        val right = StateMachineJournalSegment(byteArrayOf(1, 2, 3))

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
    }

    @Test
    fun `journal segment wrapper is serializable by Skipper action checkpoint serde`() {
        val segment = StateMachineJournalSegment(byteArrayOf(1, 2, 3))
        val serde = SmartSerde()

        val decoded = serde.deserialize(serde.serialize(segment)) as StateMachineJournalSegment

        assertThat(decoded.payload).containsExactly(1.toByte(), 2.toByte(), 3.toByte())
    }

    // ── Annotation verification tests ──

    @Test
    fun `recordStateMachineJournalSegment has IMMEDIATE_CHECKPOINT annotation`() {
        val method = StateMachineCheckpoint::class.java.getMethod(
            "recordStateMachineJournalSegment",
            StateMachineJournalSegment::class.java,
        )
        val annotation = method.getAnnotation(Execute::class.java)
        assertThat(annotation).isNotNull()
        assertThat(annotation.checkpointMode).isEqualTo(CheckpointMode.IMMEDIATE_CHECKPOINT)
    }

    @Test
    fun `recordStateMachineJournalSegmentEventually has EVENTUAL_CHECKPOINT annotation`() {
        val method = StateMachineCheckpoint::class.java.getMethod(
            "recordStateMachineJournalSegmentEventually",
            StateMachineJournalSegment::class.java,
        )
        val annotation = method.getAnnotation(Execute::class.java)
        assertThat(annotation).isNotNull()
        assertThat(annotation.checkpointMode).isEqualTo(CheckpointMode.EVENTUAL_CHECKPOINT)
    }

    // ── Class structure tests ──

    @Test
    fun `extends Actions`() {
        assertThat(Actions::class.java).isAssignableFrom(StateMachineCheckpoint::class.java)
    }
}
