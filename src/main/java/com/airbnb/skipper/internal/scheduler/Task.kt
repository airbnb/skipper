package com.airbnb.skipper.internal.scheduler

import java.time.Duration
import java.time.Instant

/** Represents a task to be executed by the scheduler. */
data class Task<TYPE>(
    val id: String,
    val createdAt: Instant,
    val runAfter: Instant,
    val retryCount: Int,
    val payload: TYPE?,
    val dedupToken: String,
    val type: Type,
    val status: Status,
    val statusMessage: String?,
    val executionTimeout: Duration,
    val isShouldRefreshPayload: Boolean,
    val version: Int,
) {
    enum class Type {
        WORKFLOW,
        TIMER,
        EXECUTION_TIMEOUT,
        COMPENSATION,
    }

    enum class Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
    }

    fun hasPayload(): Boolean = payload != null

    /**
     * Checks if the current task should be considered to have an active lease based on the current
     * time provided and the lease duration used.
     *
     * @param now The current time
     * @param leaseDuration The lease duration
     * @return true if the task has an active lease, false otherwise
     */
    fun hasActiveLease(
        now: Instant,
        leaseDuration: Duration
    ): Boolean =
        (status == Status.RUNNING || status == Status.PENDING) &&
            (runAfter.isBefore(now.plus(leaseDuration)) || runAfter == now.plus(leaseDuration))

    fun toBuilder(): TaskBuilder<TYPE> =
        TaskBuilder<TYPE>()
            .id(id)
            .createdAt(createdAt)
            .runAfter(runAfter)
            .retryCount(retryCount)
            .payload(payload)
            .dedupToken(dedupToken)
            .type(type)
            .status(status)
            .statusMessage(statusMessage)
            .executionTimeout(executionTimeout)
            .shouldRefreshPayload(isShouldRefreshPayload)
            .version(version)

    class TaskBuilder<TYPE> {
        private var id: String? = null
        private var createdAt: Instant? = null
        private var runAfter: Instant = Instant.EPOCH
        private var retryCount: Int = 0
        private var payload: TYPE? = null
        private var dedupToken: String? = null
        private var type: Type? = null
        private var status: Status = Status.PENDING
        private var statusMessage: String? = null
        private var executionTimeout: Duration? = null
        private var shouldRefreshPayload: Boolean = true
        private var version: Int = 0

        /**
         * @return `this`.
         */
        fun id(id: String): TaskBuilder<TYPE> {
            this.id = id
            return this
        }

        /**
         * @return `this`.
         */
        fun createdAt(createdAt: Instant): TaskBuilder<TYPE> {
            this.createdAt = createdAt
            return this
        }

        /**
         * @return `this`.
         */
        fun runAfter(runAfter: Instant): TaskBuilder<TYPE> {
            this.runAfter = runAfter
            return this
        }

        /**
         * @return `this`.
         */
        fun retryCount(retryCount: Int): TaskBuilder<TYPE> {
            this.retryCount = retryCount
            return this
        }

        /**
         * @return `this`.
         */
        fun payload(payload: TYPE?): TaskBuilder<TYPE> {
            this.payload = payload
            return this
        }

        /**
         * @return `this`.
         */
        fun dedupToken(dedupToken: String): TaskBuilder<TYPE> {
            this.dedupToken = dedupToken
            return this
        }

        /**
         * @return `this`.
         */
        fun type(type: Type): TaskBuilder<TYPE> {
            this.type = type
            return this
        }

        /**
         * @return `this`.
         */
        fun status(status: Status): TaskBuilder<TYPE> {
            this.status = status
            return this
        }

        /**
         * @return `this`.
         */
        fun statusMessage(statusMessage: String?): TaskBuilder<TYPE> {
            this.statusMessage = statusMessage
            return this
        }

        /**
         * @return `this`.
         */
        fun executionTimeout(executionTimeout: Duration): TaskBuilder<TYPE> {
            this.executionTimeout = executionTimeout
            return this
        }

        /**
         * @return `this`.
         */
        fun shouldRefreshPayload(shouldRefreshPayload: Boolean): TaskBuilder<TYPE> {
            this.shouldRefreshPayload = shouldRefreshPayload
            return this
        }

        /**
         * @return `this`.
         */
        fun version(version: Int): TaskBuilder<TYPE> {
            this.version = version
            return this
        }

        fun build(): Task<TYPE> =
            Task(
                id ?: throw NullPointerException("id is marked non-null but is null"),
                createdAt
                    ?: throw NullPointerException("createdAt is marked non-null but is null"),
                runAfter,
                retryCount,
                payload,
                dedupToken
                    ?: throw NullPointerException("dedupToken is marked non-null but is null"),
                type ?: throw NullPointerException("type is marked non-null but is null"),
                status,
                statusMessage,
                executionTimeout
                    ?: throw NullPointerException(
                        "executionTimeout is marked non-null but is null",
                    ),
                shouldRefreshPayload,
                version,
            )

        override fun toString(): String =
            "Task.TaskBuilder(id=$id, createdAt=$createdAt, runAfter\$value=$runAfter" +
                ", retryCount=$retryCount, payload=$payload, dedupToken=$dedupToken, type=$type" +
                ", status\$value=$status, statusMessage=$statusMessage" +
                ", executionTimeout=$executionTimeout" +
                ", shouldRefreshPayload\$value=$shouldRefreshPayload, version\$value=$version)"
    }

    companion object {
        @JvmStatic
        fun <TYPE> builder(): TaskBuilder<TYPE> = TaskBuilder()
    }
}
