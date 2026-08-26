package com.airbnb.skipper

import java.time.Instant

/** Repesents an event in the workflow lifecycle. */
data class Event internal constructor(
    val type: Type,
    val workflowId: String,
    val description: String?,
    val time: Instant?
) {
    enum class Type {
        WORKFLOW_CREATED,
        WORKFLOW_DUPLICATE_ID,
        WORKFLOW_EXECUTION_STARTED,
        WORKFLOW_COMPLETED,
        WORKFLOW_EXECUTION_FAILED,
        WORKFLOW_TIMEOUT,
        WORKFLOW_WAITING,
        WORKFLOW_RETRIES_EXHAUSTED,
        WORKFLOW_ERROR,
        WORKFLOW_RETRIABLE_ERROR,
        SIGNAL_RECEIVED,
        SIGNAL_COMPLETED,
        SIGNAL_FAILED,
        COMPENSATION_STARTED,
        COMPENSATION_CANNOT_START,
        COMPENSATION_NOOP,
        COMPENSATION_COMPLETED,
        COMPENSATION_ERROR,
        COMPENSATION_RETRYABLE_ERROR,
        ACTION_STARTED,
        ACTION_COMPLETED,
        ACTION_FAILED,
        ACTION_REPLAY_FROM_CHECKPOINT,
        WORKFLOW_CANCELLED,
        WORKFLOW_DELETED
    }

    fun toBuilder(): EventBuilder =
        EventBuilder()
            .type(this.type)
            .workflowId(this.workflowId)
            .description(this.description)
            .time(this.time)

    class EventBuilder internal constructor() {
        private var type: Type? = null
        private var workflowId: String? = null
        private var description: String? = null
        private var time: Instant? = null

        fun type(type: Type): EventBuilder {
            this.type = type
            return this
        }

        fun workflowId(workflowId: String): EventBuilder {
            this.workflowId = workflowId
            return this
        }

        fun description(description: String?): EventBuilder {
            this.description = description
            return this
        }

        fun time(time: Instant?): EventBuilder {
            this.time = time
            return this
        }

        fun build(): Event = Event(this.type!!, this.workflowId!!, this.description, this.time)

        override fun toString(): String =
            "Event.EventBuilder(type=" + this.type +
                ", workflowId=" + this.workflowId +
                ", description=" + this.description +
                ", time=" + this.time + ")"
    }

    companion object {
        @JvmStatic
        fun create(
            type: Type,
            workflowId: String
        ): Event = builder().type(type).workflowId(workflowId).build()

        @JvmStatic
        fun create(
            type: Type,
            workflowId: String,
            description: String?
        ): Event = builder().type(type).workflowId(workflowId).description(description).build()

        @JvmStatic
        fun builder(): EventBuilder = EventBuilder()
    }
}
