package com.airbnb.skipper.statemachine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransitionResultTest {
    enum class S { A, B, C }

    // ── TransitionTo ──

    @Test
    fun `TransitionTo - equal when same newState`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isEqualTo(TransitionResult.TransitionTo(S.A))
    }

    @Test
    fun `TransitionTo - not equal when different newState`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isNotEqualTo(TransitionResult.TransitionTo(S.B))
    }

    @Test
    fun `TransitionTo - hashCode consistent with equality`() {
        val a1 = TransitionResult.TransitionTo(S.A)
        val a2 = TransitionResult.TransitionTo(S.A)
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode())
    }

    @Test
    fun `TransitionTo - holds the correct newState`() {
        val result = TransitionResult.TransitionTo(S.C)
        assertThat(result.newState).isEqualTo(S.C)
    }

    @Test
    fun `TransitionTo - is a TransitionResult`() {
        val result: TransitionResult<S> = TransitionResult.TransitionTo(S.A)
        assertThat(result).isInstanceOf(TransitionResult::class.java)
    }

    @Test
    fun `TransitionTo - not equal to Stay`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isNotEqualTo(TransitionResult.Stay)
    }

    @Test
    fun `TransitionTo - not equal to Ignore`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isNotEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
    }

    // ── Stay ──

    @Test
    fun `Stay - is a singleton`() {
        val s1: TransitionResult<S> = TransitionResult.Stay
        val s2: TransitionResult<String> = TransitionResult.Stay
        assertThat(s1).isSameAs(s2)
    }

    @Test
    fun `Stay - not equal to TransitionTo`() {
        assertThat(TransitionResult.Stay).isNotEqualTo(TransitionResult.TransitionTo(S.A))
    }

    @Test
    fun `Stay - not equal to Ignore`() {
        assertThat(TransitionResult.Stay).isNotEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
    }

    @Test
    fun `Stay - is a TransitionResult`() {
        val result: TransitionResult<S> = TransitionResult.Stay
        assertThat(result).isInstanceOf(TransitionResult::class.java)
    }

    // ── Ignore ──

    @Test
    fun `Ignore - equal when same reason`() {
        assertThat(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
            .isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
    }

    @Test
    fun `Ignore - not equal when different reason`() {
        assertThat(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
            .isNotEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))
    }

    @Test
    fun `Ignore - hashCode consistent with equality`() {
        val a1 = TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)
        val a2 = TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode())
    }

    @Test
    fun `Ignore - not equal to TransitionTo`() {
        assertThat(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)).isNotEqualTo(TransitionResult.TransitionTo(S.A))
    }

    @Test
    fun `Ignore - not equal to Stay`() {
        assertThat(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)).isNotEqualTo(TransitionResult.Stay)
    }

    @Test
    fun `Ignore - is a TransitionResult`() {
        val result: TransitionResult<S> = TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)
        assertThat(result).isInstanceOf(TransitionResult::class.java)
    }

    @Test
    fun `TransitionTo - not equal to null`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isNotEqualTo(null)
    }

    @Test
    fun `TransitionTo - not equal to unrelated type`() {
        assertThat(TransitionResult.TransitionTo(S.A)).isNotEqualTo("other")
    }

    @Test
    fun `TransitionTo - copy produces equal instance with same state`() {
        val original = TransitionResult.TransitionTo(S.B)
        val copy = original.copy()
        assertThat(copy).isEqualTo(original)
    }

    @Test
    fun `TransitionTo - destructuring via component1`() {
        val result = TransitionResult.TransitionTo(S.C)
        val (state) = result
        assertThat(state).isEqualTo(S.C)
    }

    // ── Sealed exhaustiveness ──

    @Test
    fun `when expression is exhaustive over all subtypes`() {
        val results: List<TransitionResult<S>> = listOf(
            TransitionResult.TransitionTo(S.A),
            TransitionResult.Stay,
            TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT),
        )
        val labels = results.map {
            when (it) {
                is TransitionResult.TransitionTo -> "to"
                TransitionResult.Stay -> "stay"
                is TransitionResult.Ignore -> "ignore"
            }
        }
        assertThat(labels).containsExactly("to", "stay", "ignore")
    }
}
