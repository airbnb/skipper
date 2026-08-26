package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Duration
import java.time.Instant

/** Represents a timer used by a workflow execution to model arbitrary wait times.  */
@Serializable
data class Timer(
    val workflowId: String,
    val id: String,
    val createdAt: Instant? = null,
    val duration: Duration,
    val expiresAt: Instant,
    val status: Status = Status.ACTIVE,
    val version: Int = 0
) {
    @JsonIgnore
    fun getUniqueId(): String {
        return "$workflowId:$id"
    }

    enum class Status {
        ACTIVE,
        CANCELLED,
        EXPIRED;

        lateinit var transitions: List<Status>

        val isTerminal: Boolean
            get() = this == CANCELLED || this == EXPIRED

        fun canTransitionTo(newStatus: Status): Boolean {
            return transitions.contains(newStatus)
        }

        companion object {
            init {
                ACTIVE.transitions = listOf(CANCELLED, EXPIRED)
                CANCELLED.transitions = emptyList()
                /**
                 * EXPIRED can indeed transition to CANCELLED. This is because a timer expiration could in theory
                 * happen right in the middle of a workflow execution, therefore the workflow could potentially proceed
                 * assuming the timer is NOT expired, then at the time of trying to persist the execution, it would fail
                 * because the timer expired mid execution. This would result in non-deterministic behavior in the workflow
                 * code, so if the workflow proceeds assuming the timer is good, we'll uphold that and allow transition from
                 * expired to cancelled.
                 */
                EXPIRED.transitions = listOf(CANCELLED)
            }
        }
    }
}
