package com.airbnb.skipper.internal.scheduler

import java.time.Duration
import java.time.Instant

/** Represent the data required to schedule the execution of a new task. */
data class ScheduleRequest<T> internal constructor(
    val type: Task.Type,
    /**
     * The unique identifier for the task. When payload is missing, this ID will be used to
     * de-reference the payload from the storage.
     */
    val id: String,
    /**
     * A token to deduplicate tasks. Only one task with the same dedupToken will be scheduled at the
     * same time. If another tasks exist with the same dedupToken, the existing task will be
     * overwritten.
     */
    val dedupToken: String,
    /**
     * The task payload. This might be null, in which case the task handler will use the task ID to
     * hydrate the payload from the storage.
     */
    val payload: T?,
    /**
     * The time at which the task should be scheduled to run. If this is null or any instant in the
     * past, the task will be executed immediately.
     */
    val runAfter: Instant?,
    /** The maximum amount of time the task is allowed to run before being considered timeout. */
    val executionTimeout: Duration,
    /**
     * If true, the task will be executed in memory if and only if the runAfter is either null or
     * less or equal to the current time. If false, the task will be scheduled in the persistent
     * scheduler regardless of the runAfter value.
     */
    @get:JvmName("isInMemoryExecutionEnabled")
    val inMemoryExecutionEnabled: Boolean,
    val isHonorActiveLeaseWhenOverwriting: Boolean,
) {
    fun toBuilder(): ScheduleRequestBuilder<T> =
        ScheduleRequestBuilder<T>()
            .type(type)
            .id(id)
            .dedupToken(dedupToken)
            .payload(payload)
            .runAfter(runAfter)
            .executionTimeout(executionTimeout)
            .inMemoryExecutionEnabled(inMemoryExecutionEnabled)
            .honorActiveLeaseWhenOverwriting(isHonorActiveLeaseWhenOverwriting)

    class ScheduleRequestBuilder<T> internal constructor() {
        private var type: Task.Type? = null
        private var id: String? = null
        private var dedupToken: String? = null
        private var payload: T? = null
        private var runAfter: Instant? = null
        private var executionTimeout: Duration = Duration.ofMinutes(5)
        private var inMemoryExecutionEnabled: Boolean = true
        private var honorActiveLeaseWhenOverwriting: Boolean = false

        /**
         * @return `this`.
         */
        fun type(type: Task.Type): ScheduleRequestBuilder<T> {
            this.type = type
            return this
        }

        /**
         * The unique identifier for the task. When payload is missing, this ID will be used to
         * de-reference the payload from the storage.
         *
         * @return `this`.
         */
        fun id(id: String): ScheduleRequestBuilder<T> {
            this.id = id
            return this
        }

        /**
         * A token to deduplicate tasks. Only one task with the same dedupToken will be scheduled at
         * the same time. If another tasks exist with the same dedupToken, the existing task will be
         * overwritten.
         *
         * @return `this`.
         */
        fun dedupToken(dedupToken: String): ScheduleRequestBuilder<T> {
            this.dedupToken = dedupToken
            return this
        }

        /**
         * The task payload. This might be null, in which case the task handler will use the task ID
         * to hydrate the payload from the storage.
         *
         * @return `this`.
         */
        fun payload(payload: T?): ScheduleRequestBuilder<T> {
            this.payload = payload
            return this
        }

        /**
         * The time at which the task should be scheduled to run. If this is null or any instant in
         * the past, the task will be executed immediately.
         *
         * @return `this`.
         */
        fun runAfter(runAfter: Instant?): ScheduleRequestBuilder<T> {
            this.runAfter = runAfter
            return this
        }

        /**
         * The maximum amount of time the task is allowed to run before being considered timeout.
         *
         * @return `this`.
         */
        fun executionTimeout(executionTimeout: Duration): ScheduleRequestBuilder<T> {
            this.executionTimeout = executionTimeout
            return this
        }

        /**
         * If true, the task will be executed in memory if and only if the runAfter is either null
         * or less or equal to the current time. If false, the task will be scheduled in the
         * persistent scheduler regardless of the runAfter value.
         *
         * @return `this`.
         */
        fun inMemoryExecutionEnabled(inMemoryExecutionEnabled: Boolean): ScheduleRequestBuilder<T> {
            this.inMemoryExecutionEnabled = inMemoryExecutionEnabled
            return this
        }

        /**
         * @return `this`.
         */
        fun honorActiveLeaseWhenOverwriting(honorActiveLeaseWhenOverwriting: Boolean): ScheduleRequestBuilder<T> {
            this.honorActiveLeaseWhenOverwriting = honorActiveLeaseWhenOverwriting
            return this
        }

        fun build(): ScheduleRequest<T> =
            ScheduleRequest(
                type ?: throw NullPointerException("type is marked non-null but is null"),
                id ?: throw NullPointerException("id is marked non-null but is null"),
                dedupToken
                    ?: throw NullPointerException("dedupToken is marked non-null but is null"),
                payload,
                runAfter,
                executionTimeout,
                inMemoryExecutionEnabled,
                honorActiveLeaseWhenOverwriting,
            )

        override fun toString(): String =
            "ScheduleRequest.ScheduleRequestBuilder(type=$type, id=$id, dedupToken=$dedupToken" +
                ", payload=$payload, runAfter=$runAfter" +
                ", executionTimeout\$value=$executionTimeout" +
                ", inMemoryExecutionEnabled\$value=$inMemoryExecutionEnabled" +
                ", honorActiveLeaseWhenOverwriting\$value=$honorActiveLeaseWhenOverwriting)"
    }

    companion object {
        @JvmStatic
        fun <T> builder(): ScheduleRequestBuilder<T> = ScheduleRequestBuilder()
    }
}
