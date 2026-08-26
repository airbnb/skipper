package com.airbnb.skipper.internal.scheduler

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class TaskTest {
    private fun createBaseTask(): Task<Void> =
        Task.builder<Void>()
            .id(TEST_ID)
            .createdAt(BASE_TIME)
            .dedupToken(TEST_DEDUP_TOKEN)
            .type(Task.Type.WORKFLOW)
            .executionTimeout(TEST_EXECUTION_TIMEOUT)
            .build()

    @Test
    fun hasActiveLease_whenStatusNotRunning_returnsFalse() {
        // Test all non-RUNNING statuses
        for (status in Task.Status.values()) {
            if (status == Task.Status.RUNNING || status == Task.Status.PENDING) {
                continue
            }

            val task = createBaseTask().toBuilder().status(status).build()

            assertThat(task.hasActiveLease(BASE_TIME, Duration.ofMinutes(1)))
                .`as`("Status %s should not have active lease", status)
                .isFalse()
        }
    }

    @Test
    fun hasActiveLease_whenRunningAndWithinLeaseDuration_returnsTrue() {
        val task =
            createBaseTask().toBuilder()
                .status(Task.Status.RUNNING)
                .runAfter(BASE_TIME.minus(Duration.ofMinutes(1))) // runAfter is in the past
                .build()

        val leaseDuration = Duration.ofMinutes(5)
        assertThat(task.hasActiveLease(BASE_TIME, leaseDuration)).isTrue()
    }

    @Test
    fun hasActiveLease_whenRunningButAfterLeaseDuration_returnsFalse() {
        val task =
            createBaseTask().toBuilder()
                .status(Task.Status.RUNNING)
                .runAfter(BASE_TIME.plus(Duration.ofMinutes(10))) // runAfter is far in the future
                .build()

        val leaseDuration = Duration.ofMinutes(5)
        assertThat(task.hasActiveLease(BASE_TIME, leaseDuration)).isFalse()
    }

    @Test
    fun hasActiveLease_whenRunningAndExactlyAtLeaseEnd_returnsTrue() {
        val leaseDuration = Duration.ofMinutes(5)
        val task =
            createBaseTask().toBuilder()
                .status(Task.Status.RUNNING)
                .runAfter(BASE_TIME.plus(leaseDuration).minus(Duration.ofNanos(1)))
                .build()

        assertThat(task.hasActiveLease(BASE_TIME, leaseDuration)).isTrue()
    }

    @Test
    fun hasActiveLease_whenRunningAndRunAtIsInThePast_returnsTrue() {
        val leaseDuration = Duration.ofMinutes(5)
        val task =
            createBaseTask().toBuilder().status(Task.Status.RUNNING).runAfter(Instant.EPOCH).build()

        assertThat(task.hasActiveLease(BASE_TIME, leaseDuration)).isTrue()
    }

    @Test
    @Suppress("CAST_NEVER_SUCCEEDS")
    fun hasActiveLease_withNullParameters_throwsException() {
        val task = createBaseTask()

        assertThatThrownBy { task.hasActiveLease(null as Instant, Duration.ofMinutes(1)) }
            .isInstanceOf(NullPointerException::class.java)

        assertThatThrownBy { task.hasActiveLease(BASE_TIME, null as Duration) }
            .isInstanceOf(NullPointerException::class.java)
    }

    companion object {
        private const val TEST_ID = "test-id"
        private const val TEST_DEDUP_TOKEN = "test-dedup-token"
        private val TEST_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val BASE_TIME: Instant = Instant.parse("2024-01-01T10:00:00Z")
    }
}
