package com.airbnb.skipper.internal.api

import com.airbnb.skipper.api.ActionCheckpointView
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.common.SneakyThrow
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.vavr.control.Either
import java.time.Instant
import java.util.Objects
import java.util.concurrent.CompletableFuture

/** Represents the trace of execution of an activity request. */
// Pins the serialized property order to the order the original Java value type emitted, so the
// JSON is byte-identical to pre-port output. In Java the `result` field (declared 4th) was
// @JsonIgnore'd and its slot reused by the computed @JsonProperty("result") getter; in Kotlin that
// computed getter is not a constructor property, so without this pin it would sort last. Order is
// wire-compat only, not behavior.
@JsonPropertyOrder(
    "checkpointTag",
    "executionStartTime",
    "executionEndTime",
    "result",
    "input",
    "isTransient",
    "resultIsAsync",
    "transient",
    "isSuccessful",
)
class ActionCheckpoint(
    /** The unique identifier of the action checkpoint. */
    val checkpointTag: CheckpointTag,
    val executionStartTime: Instant,
    val executionEndTime: Instant?,
    /**
     * The result of the action execution. This is an Either because the action can either complete
     * successfully or fail with an exception.
     */
    @field:JsonIgnore
    @get:JsonIgnore
    val result: Either<Throwable, Any?>,
    /**
     * The input parameter passed to the action method. This is stored for compensation purposes so
     * that compensation methods can receive the same input as the original action method. Will be
     * null for actions that don't have compensation methods or have no input parameters.
     */
    val input: Any?,
    // Java field name `isTransient` -> serializes under both "isTransient" (field) and "transient"
    // (the isTransient() getter, with Jackson's `is` prefix stripped), matching the original type.
    val isTransient: Boolean,
    /** True if the result was completed through an async path (CompletableFuture). */
    // Java field name `resultIsAsync` with getter isResultIsAsync(). To reproduce the single
    // "resultIsAsync" wire key (and not an extra "isResultIsAsync" from the Kotlin backing field),
    // the field is ignored and the getter — isResultIsAsync(), which Jackson maps to "resultIsAsync"
    // — is the sole contributor. The Kotlin property stays `isResultIsAsync` (the name consumers use)
    // and the JVM getter stays isResultIsAsync().
    @field:JsonIgnore
    val isResultIsAsync: Boolean,
) {
    /**
     * Get the result of the action execution in the exact form as it was produced by the original
     * action invocation.
     *
     * @return One of the following: - A successful result object when the action completes
     *   successfully and the action method is a regular synchronous method. - A CompletableFuture
     *   object when the action completes successfully and the action method is an asynchronous
     *   method. This future is always going to be completed. - A CompletableFuture object that is
     *   completed exceptionally with the exception that was raised if the action method invocation
     *   threw an exception in the async path through a failed completable future.
     * @throws com.airbnb.skipper.SkipperError If the action method invocation threw an exception in
     *   the sync path.
     */
    // Catches Throwable to faithfully reproduce the original Java behavior: any error (including
    // the thrown `result.left`) is rethrown unchecked via SneakyThrow. Narrowing the catch would
    // change behavior, so the detekt rule is suppressed rather than the catch tightened.
    @Suppress("TooGenericExceptionCaught")
    fun generateResult(): Any? {
        try {
            if (result.isRight) {
                val res = result.get()
                if (isResultIsAsync) {
                    return CompletableFuture.completedFuture(res)
                }
                return res
            } else {
                val error = result.left
                if (isResultIsAsync) {
                    val future = CompletableFuture<Any?>()
                    future.completeExceptionally(error)
                    return future
                }
                throw error
            }
        } catch (ex: Throwable) {
            throw SneakyThrow.sneakyThrow(ex)
        }
    }

    // Exposed as Kotlin `val`s (not `fun`s) so Kotlin consumers can use property access
    // (e.g. skipper-state-machine's `cp.resultOrError` / `cp.isSuccessful`, which worked when this
    // was a Java getter). The JVM getter names (getResultOrError()/isSuccessful()) and the Jackson
    // property names are unchanged, so Java callers, the ABI, and serialization are unaffected.
    @get:JsonProperty("result")
    val resultOrError: Any? get() = if (result.isRight) result.get() else result.left

    @get:JsonProperty("isSuccessful")
    val isSuccessful: Boolean get() = result.isRight

    fun toView(): ActionCheckpointView {
        val actionResult: ActionCheckpointView.ActionResult =
            if (result.isRight) {
                ActionCheckpointView.ActionResult.success(result.get())
            } else {
                ActionCheckpointView.ActionResult.error(result.left)
            }
        return ActionCheckpointView(
            checkpointTag.workflowId,
            checkpointTag.actionClass.name,
            checkpointTag.actionMethod,
            checkpointTag.iteration,
            executionStartTime,
            executionEndTime,
            actionResult,
            isTransient,
        )
    }

    class ActionCheckpointBuilder internal constructor() {
        private var checkpointTag: CheckpointTag? = null
        private var executionStartTime: Instant? = null
        private var executionEndTime: Instant? = null
        private var result: Either<Throwable, Any?>? = null
        private var input: Any? = null
        private var isTransient = false
        private var resultIsAsync = false

        /**
         * The unique identifier of the action checkpoint.
         *
         * @return `this`.
         */
        fun checkpointTag(checkpointTag: CheckpointTag): ActionCheckpointBuilder {
            this.checkpointTag = checkpointTag
            return this
        }

        fun executionStartTime(executionStartTime: Instant): ActionCheckpointBuilder {
            this.executionStartTime = executionStartTime
            return this
        }

        fun executionEndTime(executionEndTime: Instant?): ActionCheckpointBuilder {
            this.executionEndTime = executionEndTime
            return this
        }

        /**
         * The result of the action execution. This is an Either because the action can either
         * complete successfully or fail with an exception.
         *
         * @return `this`.
         */
        @JsonIgnore
        fun result(result: Either<Throwable, Any?>?): ActionCheckpointBuilder {
            this.result = result
            return this
        }

        /**
         * The input parameter passed to the action method. This is stored for compensation purposes
         * so that compensation methods can receive the same input as the original action method.
         * Will be null for actions that don't have compensation methods or have no input
         * parameters.
         *
         * @return `this`.
         */
        fun input(input: Any?): ActionCheckpointBuilder {
            this.input = input
            return this
        }

        fun isTransient(isTransient: Boolean): ActionCheckpointBuilder {
            this.isTransient = isTransient
            return this
        }

        /**
         * True if the result was completed through an async path (CompletableFuture).
         *
         * @return `this`.
         */
        fun resultIsAsync(resultIsAsync: Boolean): ActionCheckpointBuilder {
            this.resultIsAsync = resultIsAsync
            return this
        }

        fun build(): ActionCheckpoint =
            ActionCheckpoint(
                checkpointTag
                    ?: throw NullPointerException(
                        "checkpointTag is marked non-null but is null",
                    ),
                executionStartTime
                    ?: throw NullPointerException(
                        "executionStartTime is marked non-null but is null",
                    ),
                executionEndTime,
                result
                    ?: throw NullPointerException(
                        "result is marked non-null but is null",
                    ),
                input,
                isTransient,
                resultIsAsync,
            )

        override fun toString(): String =
            "ActionCheckpoint.ActionCheckpointBuilder(checkpointTag=$checkpointTag" +
                ", executionStartTime=$executionStartTime" +
                ", executionEndTime=$executionEndTime" +
                ", result=$result" +
                ", input=$input" +
                ", isTransient=$isTransient" +
                ", resultIsAsync=$resultIsAsync)"
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is ActionCheckpoint) return false
        return isTransient == other.isTransient &&
            isResultIsAsync == other.isResultIsAsync &&
            checkpointTag == other.checkpointTag &&
            executionStartTime == other.executionStartTime &&
            executionEndTime == other.executionEndTime &&
            result == other.result &&
            input == other.input
    }

    override fun hashCode(): Int =
        Objects.hash(
            isTransient,
            isResultIsAsync,
            checkpointTag,
            executionStartTime,
            executionEndTime,
            result,
            input,
        )

    override fun toString(): String =
        "ActionCheckpoint(checkpointTag=$checkpointTag" +
            ", executionStartTime=$executionStartTime" +
            ", executionEndTime=$executionEndTime" +
            ", result=$result" +
            ", input=$input" +
            ", isTransient=$isTransient" +
            ", resultIsAsync=$isResultIsAsync)"

    companion object {
        @JvmStatic
        fun builder(): ActionCheckpointBuilder = ActionCheckpointBuilder()
    }
}
