package com.airbnb.skipper

import com.airbnb.skipper.Timer.Status
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimerTest {
    @Test
    fun `ACTIVE can transition to CANCELLED`() {
        assertTrue(Status.ACTIVE.canTransitionTo(Status.CANCELLED))
    }

    @Test
    fun `ACTIVE can transition to EXPIRED`() {
        assertTrue(Status.ACTIVE.canTransitionTo(Status.EXPIRED))
    }

    @Test
    fun `ACTIVE cannot transition to ACTIVE`() {
        assertFalse(Status.ACTIVE.canTransitionTo(Status.ACTIVE))
    }

    @Test
    fun `CANCELLED cannot transition to ACTIVE`() {
        assertFalse(Status.CANCELLED.canTransitionTo(Status.ACTIVE))
    }

    @Test
    fun `CANCELLED cannot transition to EXPIRED`() {
        assertFalse(Status.CANCELLED.canTransitionTo(Status.EXPIRED))
    }

    @Test
    fun `CANCELLED cannot transition to CANCELLED`() {
        assertFalse(Status.CANCELLED.canTransitionTo(Status.CANCELLED))
    }

    @Test
    fun `EXPIRED can transition to CANCELLED`() {
        assertTrue(Status.EXPIRED.canTransitionTo(Status.CANCELLED))
    }

    @Test
    fun `EXPIRED cannot transition to ACTIVE`() {
        assertFalse(Status.EXPIRED.canTransitionTo(Status.ACTIVE))
    }

    @Test
    fun `EXPIRED cannot transition to EXPIRED`() {
        assertFalse(Status.EXPIRED.canTransitionTo(Status.EXPIRED))
    }
}
