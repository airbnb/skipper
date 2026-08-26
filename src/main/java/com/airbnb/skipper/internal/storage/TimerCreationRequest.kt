package com.airbnb.skipper.internal.storage

import java.time.Duration
import java.time.Instant

data class TimerCreationRequest(
    val workflowId: String,
    val timerId: String,
    val duration: Duration,
    val expiresAt: Instant,
) {
    class TimerCreationRequestBuilder internal constructor() {
        private var workflowId: String? = null
        private var timerId: String? = null
        private var duration: Duration? = null
        private var expiresAt: Instant? = null

        /**
         * @return `this`.
         */
        fun workflowId(workflowId: String): TimerCreationRequestBuilder {
            this.workflowId = workflowId
            return this
        }

        /**
         * @return `this`.
         */
        fun timerId(timerId: String): TimerCreationRequestBuilder {
            this.timerId = timerId
            return this
        }

        /**
         * @return `this`.
         */
        fun duration(duration: Duration): TimerCreationRequestBuilder {
            this.duration = duration
            return this
        }

        /**
         * @return `this`.
         */
        fun expiresAt(expiresAt: Instant): TimerCreationRequestBuilder {
            this.expiresAt = expiresAt
            return this
        }

        fun build(): TimerCreationRequest =
            TimerCreationRequest(
                workflowId ?: throw NullPointerException("workflowId is marked non-null but is null"),
                timerId ?: throw NullPointerException("timerId is marked non-null but is null"),
                duration ?: throw NullPointerException("duration is marked non-null but is null"),
                expiresAt ?: throw NullPointerException("expiresAt is marked non-null but is null"),
            )

        override fun toString(): String =
            "TimerCreationRequest.TimerCreationRequestBuilder(workflowId=$workflowId" +
                ", timerId=$timerId, duration=$duration, expiresAt=$expiresAt)"
    }

    companion object {
        @JvmStatic
        fun builder(): TimerCreationRequestBuilder = TimerCreationRequestBuilder()
    }
}
