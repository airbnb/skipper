package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.WorkflowInstance
import java.time.Instant

/** Filter criteria for searching workflow instances via the admin API. */
class WorkflowSearchFilter internal constructor(
    val workflowEntryPoints: List<@JvmSuppressWildcards WorkflowEntryPoint>?,
    val statuses: List<@JvmSuppressWildcards WorkflowInstance.Status>?,
    val createdAfter: Instant?,
    val createdBefore: Instant?,
    val parentWorkflowId: String?,
    val cursor: Cursor?,
) {
    /** A workflow entry point: the (class, method) pair that uniquely identifies a workflow type. */
    class WorkflowEntryPoint(
        val workflowClass: String,
        val workflowMethod: String,
    )

    /** Cursor for keyset-based pagination, encoding the last seen (created_at, workflow_id). */
    class Cursor(
        val createdAt: Instant,
        val workflowId: String,
    )

    class WorkflowSearchFilterBuilder internal constructor() {
        private var workflowEntryPoints: List<WorkflowEntryPoint>? = null
        private var statuses: List<WorkflowInstance.Status>? = null
        private var createdAfter: Instant? = null
        private var createdBefore: Instant? = null
        private var parentWorkflowId: String? = null
        private var cursor: Cursor? = null

        /**
         * @return `this`.
         */
        fun workflowEntryPoints(workflowEntryPoints: List<@JvmSuppressWildcards WorkflowEntryPoint>?,): WorkflowSearchFilterBuilder {
            this.workflowEntryPoints = workflowEntryPoints
            return this
        }

        /**
         * @return `this`.
         */
        fun statuses(statuses: List<@JvmSuppressWildcards WorkflowInstance.Status>?,): WorkflowSearchFilterBuilder {
            this.statuses = statuses
            return this
        }

        /**
         * @return `this`.
         */
        fun createdAfter(createdAfter: Instant?): WorkflowSearchFilterBuilder {
            this.createdAfter = createdAfter
            return this
        }

        /**
         * @return `this`.
         */
        fun createdBefore(createdBefore: Instant?): WorkflowSearchFilterBuilder {
            this.createdBefore = createdBefore
            return this
        }

        /**
         * @return `this`.
         */
        fun parentWorkflowId(parentWorkflowId: String?): WorkflowSearchFilterBuilder {
            this.parentWorkflowId = parentWorkflowId
            return this
        }

        /**
         * @return `this`.
         */
        fun cursor(cursor: Cursor?): WorkflowSearchFilterBuilder {
            this.cursor = cursor
            return this
        }

        fun build(): WorkflowSearchFilter =
            WorkflowSearchFilter(
                workflowEntryPoints,
                statuses,
                createdAfter,
                createdBefore,
                parentWorkflowId,
                cursor,
            )

        override fun toString(): String =
            "WorkflowSearchFilter.WorkflowSearchFilterBuilder(workflowEntryPoints=" +
                "$workflowEntryPoints, statuses=$statuses, createdAfter=$createdAfter" +
                ", createdBefore=$createdBefore, parentWorkflowId=$parentWorkflowId" +
                ", cursor=$cursor)"
    }

    companion object {
        @JvmStatic
        fun builder(): WorkflowSearchFilterBuilder = WorkflowSearchFilterBuilder()
    }
}
