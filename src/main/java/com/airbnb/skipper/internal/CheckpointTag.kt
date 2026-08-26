package com.airbnb.skipper.internal

/**
 * Domain object that holds the fields that in combination uniquely identify an action checkpoint
 */
// Value POJO -> Kotlin data class: equals/hashCode/toString cover the same five
// fields as the original Java. The hand-written builder()/toBuilder() are reproduced verbatim
// because external/internal callers construct exclusively through the builder. The primary
// constructor is `internal` (the Java all-args ctor was package-private; there are zero external
// `new CheckpointTag(...)` call sites — all go through the builder).
data class CheckpointTag
    internal constructor(
        /** The workflow instance id */
        val workflowId: String,
        /** The action class */
        val actionClass: Class<*>,
        /** The method in the action class */
        val actionMethod: String,
        /**
         * The execution iteration. This is useful for actions that are executed multiple times in a
         * single workflow instance.
         */
        val iteration: Long,
        /**
         * Optional checkpoint name for name-based matching. When present, checkpoint matching uses the
         * name instead of the positional (actionClass, actionMethod, iteration) tuple. Null for unnamed
         * (positional) checkpoints.
         */
        val checkpointName: String?,
    ) {
        /**
         * Matches this tag against another tag. When both tags have a checkpoint name, matching is
         * name-based (ignoring class/method/iteration). Otherwise, matching is positional (existing
         * behavior).
         */
        fun matches(other: CheckpointTag): Boolean {
            if (this.checkpointName != null && other.checkpointName != null) {
                return this.workflowId == other.workflowId &&
                    this.checkpointName == other.checkpointName
            }
            if (this.checkpointName != null || other.checkpointName != null) {
                return false
            }
            return this.workflowId == other.workflowId &&
                this.actionClass == other.actionClass &&
                this.actionMethod == other.actionMethod &&
                this.iteration == other.iteration
        }

        fun toBuilder(): CheckpointTagBuilder =
            CheckpointTagBuilder()
                .workflowId(this.workflowId)
                .actionClass(this.actionClass)
                .actionMethod(this.actionMethod)
                .iteration(this.iteration)
                .checkpointName(this.checkpointName)

        class CheckpointTagBuilder internal constructor() {
            private var workflowId: String? = null
            private var actionClass: Class<*>? = null
            private var actionMethod: String? = null
            private var iteration: Long = 0
            private var checkpointName: String? = null

            /**
             * The workflow instance id
             *
             * @return `this`.
             */
            fun workflowId(workflowId: String): CheckpointTagBuilder {
                this.workflowId = workflowId
                return this
            }

            /**
             * The action class
             *
             * @return `this`.
             */
            fun actionClass(actionClass: Class<*>): CheckpointTagBuilder {
                this.actionClass = actionClass
                return this
            }

            /**
             * The method in the action class
             *
             * @return `this`.
             */
            fun actionMethod(actionMethod: String): CheckpointTagBuilder {
                this.actionMethod = actionMethod
                return this
            }

            /**
             * The execution iteration. This is useful for actions that are executed multiple times in a
             * single workflow instance.
             *
             * @return `this`.
             */
            fun iteration(iteration: Long): CheckpointTagBuilder {
                this.iteration = iteration
                return this
            }

            /**
             * Optional checkpoint name for name-based matching. When present, checkpoint matching uses
             * the name instead of the positional (actionClass, actionMethod, iteration) tuple. Null for
             * unnamed (positional) checkpoints.
             *
             * @return `this`.
             */
            fun checkpointName(checkpointName: String?): CheckpointTagBuilder {
                this.checkpointName = checkpointName
                return this
            }

            fun build(): CheckpointTag =
                CheckpointTag(
                    workflowId
                        ?: throw NullPointerException(
                            "workflowId is marked non-null but is null",
                        ),
                    actionClass
                        ?: throw NullPointerException(
                            "actionClass is marked non-null but is null",
                        ),
                    actionMethod
                        ?: throw NullPointerException(
                            "actionMethod is marked non-null but is null",
                        ),
                    iteration,
                    checkpointName,
                )

            override fun toString(): String =
                "CheckpointTag.CheckpointTagBuilder(workflowId=$workflowId" +
                    ", actionClass=$actionClass" +
                    ", actionMethod=$actionMethod" +
                    ", iteration=$iteration" +
                    ", checkpointName=$checkpointName)"
        }

        companion object {
            @JvmStatic
            fun builder(): CheckpointTagBuilder = CheckpointTagBuilder()

            @JvmStatic
            fun fromExecuteActionRequest(request: ActionExecutor.ExecuteActionRequest): CheckpointTag {
                val name = request.checkpointName
                return builder()
                    .actionClass(request.baseActionClass)
                    .actionMethod(request.actionMethodName)
                    .workflowId(request.executionContext.workflow.workflowId)
                    .iteration(
                        if (name != null) {
                            0L
                        } else {
                            request.executionContext
                                .getActionIteration(request.baseActionClass, request.actionMethodName)
                                .toLong()
                        }
                    )
                    .checkpointName(name)
                    .build()
            }
        }
    }
