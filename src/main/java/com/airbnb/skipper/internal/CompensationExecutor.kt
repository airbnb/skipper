package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.ContextSnapshot
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperAnnotationNames.COMPENSATION_RETRY_STRATEGY
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.SuspendSupport
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowOptions
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.util.SkipperInternalDeps
import com.airbnb.skipper.util.SpanTagger
import com.airbnb.skipper.util.injectWorkflowMembers
import com.google.common.collect.ImmutableMap
import io.opentracing.Tracer
import io.vavr.collection.List
import io.vavr.control.Option
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Named

/** CompensationExecutor is responsible creating and executing compensation flows. */
// `open` (not final) restores the Java baseline's non-final class semantics: the original
// `public class CompensationExecutor` was subclassable, and existing immutable scheduler tests
// (e.g. WorkflowExecutionTaskHandlerTest) Mockito-mock this class. Kotlin classes are final by
// default; without `open` Mockito cannot create the mock subclass.
open class CompensationExecutor
    @Inject
    constructor(
        private val injector: SkipperInjector,
        private val internalDeps: SkipperInternalDeps,
        private val workflowOptions: WorkflowOptions,
        private val middleware: RawRequestContextMiddleware,
        private val tracer: Tracer,
        private val metrics: Metrics,
        private val actionExecutor: ActionExecutor,
        private val contextPropagator: ContextPropagator,
        @param:Named(COMPENSATION_RETRY_STRATEGY)
        @field:Named(COMPENSATION_RETRY_STRATEGY)
        private val compensationRetryStrategy: RetryStrategy,
        private val spanTagger: SpanTagger,
    ) {
        /**
         * Executes compensation flow for the given workflow instance.
         *
         * <p>This method processes all successful action checkpoints that have compensation methods
         * defined, executing them in reverse chronological order (most recent first). Each compensation
         * method is executed sequentially to avoid race conditions.
         *
         * <p>Compensation methods are checkpointed for fault tolerance, similar to regular action
         * execution. The method handles both synchronous and asynchronous compensation methods.
         *
         * <p>This method is stateless and constructs the compensation flow based solely on the action
         * checkpoints present in the execution context.
         *
         * @param workflowInstance The workflow instance to execute compensation for.
         * @param executorService The executor service to use for the execution.
         * @param executionContext The execution context containing action checkpoints.
         * @return A future with the result of the compensation execution.
         */
        @Suppress("TooGenericExceptionCaught")
        open fun executeCompensationFlow(
            workflowInstance: WorkflowInstance,
            executorService: ExecutorService,
            executionContext: ExecutionContext,
        ): CompletableFuture<CompensationResult> {
            val invocation =
                RawActionInvocation(
                    workflowInstance.workflowId,
                    workflowInstance.requestContext,
                    workflowInstance.extraRequestData,
                )
            return (
                // Get compensable action checkpoints in reverse chronological order
                // Middleware: install host-specific state. afterExecution in the outer finally
                // guarantees cleanup.
                // Capture context before the compensation chain. Each thenCompose callback
                // may run on a different thread; restore context before each compensation
                // action so ThreadLocal-based state is available.
                //
                // The try-with-resources activation in the thenCompose callback below is
                // intentionally short-lived: it only needs to last long enough for
                // ActionExecutor.executeAction to capture its own fresh snapshot for
                // internal async propagation (within-action dispatcher switches and
                // Continuation.resumeWith). For non-suspend compensation methods, the
                // method executes synchronously within the scope.
                // Execute compensation methods sequentially in reverse order.
                // Each compensation method may return a CompletableFuture (for suspend
                // @Compensate methods). Chain them via thenCompose to maintain sequential
                // execution without blocking a thread between compensations.
                CompletableFuture.supplyAsync(
                    {
                        val compensableCheckpoints = getCompensableCheckpoints(executionContext)
                        if (compensableCheckpoints.isEmpty) {
                            log.info(
                                "No compensable actions found for workflowId={}",
                                workflowInstance.workflowId,
                            )
                            return@supplyAsync CompletableFuture.completedFuture(
                                CompensationResult.success(),
                            )
                        }
                        val workflow =
                            getWorkflowInstance(
                                workflowInstance.workflowClass,
                                workflowInstance.workflowId,
                            )
                        workflow.executionContext = executionContext
                        val inspector = WorkflowInspector(workflow)
                        inspector.validate()
                        inspector.setState(workflowInstance.state)
                        middleware.rawBeforeExecution(invocation)
                        var capturedSnapshot: ContextSnapshot
                        try {
                            capturedSnapshot = contextPropagator.capture()
                        } catch (e: Throwable) {
                            log.warn(
                                "ContextPropagator.capture() failed, context propagation disabled for" +
                                    " compensation chain",
                                e,
                            )
                            capturedSnapshot = ContextSnapshot.NOOP
                        }
                        val contextSnapshot = capturedSnapshot
                        val span =
                            workflowInstance
                                .extraRequestData
                                .startNewSpan(
                                    tracer,
                                    workflowInstance.workflowClass.canonicalName,
                                    "compensation",
                                    ImmutableMap.of<String, String>(),
                                    false,
                                    spanTagger,
                                )
                        val scope = tracer.activateSpan(span)
                        try {
                            var chain: CompletableFuture<*> = CompletableFuture.completedFuture<Any?>(null)
                            for (checkpoint in compensableCheckpoints) {
                                chain =
                                    chain.thenCompose {
                                        try {
                                            contextSnapshot.activate().use {
                                                executeCompensationForCheckpointAsync(
                                                    checkpoint,
                                                    executionContext,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            val failed = CompletableFuture<Void>()
                                            failed.completeExceptionally(e)
                                            failed
                                        }
                                    }
                            }
                            return@supplyAsync chain
                                .thenApply {
                                    metrics
                                        .counter(
                                            ImmutableMap.of(
                                                "outcome",
                                                "completed",
                                                "workflowClass",
                                                executionContext
                                                    .workflow
                                                    .workflowClass
                                                    .simpleName,
                                                "workflowMethod",
                                                executionContext.workflow.workflowMethod,
                                                "source",
                                                "compensation",
                                            ),
                                            METRIC_COMPONENT_NAME,
                                            "workflowExecutions",
                                        )
                                        .inc()
                                    log.info(
                                        "compensation flow completed successfully. workflowId={}," +
                                            " compensatedActions={}",
                                        executionContext.workflow.workflowId,
                                        compensableCheckpoints.size(),
                                    )
                                    CompensationResult.success()
                                }
                                .whenComplete { _, _ ->
                                    span.finish()
                                    scope.close()
                                }
                        } catch (e: Throwable) {
                            span.finish()
                            scope.close()
                            throw e
                        } finally {
                            middleware.rawAfterExecution(invocation)
                        }
                    },
                    executorService,
                )
                    .thenCompose { cf -> cf }
                    .exceptionally { e -> handleCompensationExecutionError(e, executionContext) }
            )
        }

        private fun getCompensableCheckpoints(executionContext: ExecutionContext,): List<ActionCheckpoint> {
            return List.ofAll(executionContext.actionCheckpoints)
                .appendAll(executionContext.dirtyCheckpoints)
                .filter { checkpoint -> !checkpoint.isTransient }
                .filter { it.isSuccessful }
                .filter { checkpoint ->
                    @Suppress("UNCHECKED_CAST")
                    val actionClass =
                        checkpoint.checkpointTag.actionClass as Class<out Actions>
                    val inspector = ActionInspector(actionClass)
                    inspector.hasCompensationMethod(
                        checkpoint.checkpointTag.actionMethod,
                    )
                }
                .sortBy { it.executionStartTime }
                .reverse()
        }

        /**
         * Executes a single compensation method for the given checkpoint.
         *
         * <p>Returns a {@link CompletableFuture} that completes when the compensation method finishes.
         * For suspend {@code @Compensate} methods, {@link ActionExecutor#executeAction} returns a
         * CompletableFuture (from {@link SuspendSupport#invokeSuspendFunctionAsync}), which is chained
         * without blocking. For non-suspend methods, the result is normalized to a completed future.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun executeCompensationForCheckpointAsync(
            checkpoint: ActionCheckpoint,
            executionContext: ExecutionContext,
        ): CompletableFuture<Void> {
            try {
                @Suppress("UNCHECKED_CAST")
                val actionClass =
                    checkpoint.checkpointTag.actionClass as Class<out Actions>
                val actionMethodName = checkpoint.checkpointTag.actionMethod
                // Create action instance
                val actionInstance: Actions
                try {
                    actionInstance = injector.getInstance(actionClass)
                } catch (e: Exception) {
                    throw NonRetryableError(
                        String.format("Cannot create action instance for compensation: %s", actionClass),
                        e,
                    )
                }
                // Get compensation method using ActionInspector
                val actionInspector = ActionInspector(actionClass)
                val compensationMethod: Method =
                    actionInspector
                        .getCompensationMethod(actionMethodName)
                        .getOrElseThrow {
                            NonRetryableError(
                                String.format(
                                    "Compensation method not found for action method %s in class %s",
                                    actionMethodName,
                                    actionClass,
                                ),
                            )
                        }
                // Get original action input from checkpoint
                val originalInput = getOriginalActionInput(checkpoint)
                // Get action result from checkpoint
                val actionResult = getActionResult(checkpoint)
                // Determine arguments based on compensation method parameter count
                val compensationArgs =
                    buildCompensationArguments(compensationMethod, originalInput, actionResult)
                log.debug(
                    "Executing compensation method {} for action {} with {} arguments",
                    compensationMethod.name,
                    actionMethodName,
                    compensationArgs.size,
                )
                // Create ExecuteActionRequest for compensation method execution
                // Note: We use a simple retry strategy since compensation methods should be reliable
                val compensationRequest =
                    ActionExecutor.ExecuteActionRequest.builder()
                        .actionObject(actionInstance)
                        .proxyMethod(compensationMethod)
                        .originalMethod(compensationMethod)
                        .arg(compensationArgs)
                        .executionContext(executionContext)
                        .retryStrategy(compensationRetryStrategy)
                        .isCompensatingAction(true)
                        .build()
                // Execute compensation method through ActionExecutor to get checkpointing and
                // instrumentation.
                // For suspend @Compensate methods, this returns a CompletableFuture. For non-suspend methods,
                // it returns the direct result. Normalize to CompletableFuture for uniform chaining.
                val result = actionExecutor.executeAction(compensationRequest)
                val resultFuture: CompletableFuture<*> =
                    if (result is CompletableFuture<*>) {
                        result
                    } else {
                        CompletableFuture.completedFuture(result)
                    }
                return resultFuture.thenAccept {
                    log.debug(
                        "Compensation method {} completed for action {}",
                        compensationMethod.name,
                        actionMethodName,
                    )
                }
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        /**
         * Extracts the original input parameter that was passed to the action when it was executed.
         *
         * @param checkpoint The action checkpoint containing the stored input data.
         * @return An Option containing the original input, or the input wrapped as Some(input).
         */
        private fun getOriginalActionInput(checkpoint: ActionCheckpoint): Option<Any?> {
            // Return the stored input from the checkpoint, or None if no input was stored
            return Option.of(checkpoint.input)
        }

        /**
         * Extracts the result value from a successful action execution checkpoint.
         *
         * <p>For action methods that return CompletableFuture<T>, the checkpoint stores the unwrapped T
         * value after the future completes. This method returns that unwrapped value, which is exactly
         * what compensation methods expect as their second parameter.
         *
         * @param checkpoint The action checkpoint containing the execution result.
         * @return An Option containing the unwrapped action result if the execution was successful and
         *     produced a result, or None if the action failed or had no result.
         */
        private fun getActionResult(checkpoint: ActionCheckpoint): Option<Any?> {
            // Return the stored result from the checkpoint if it's successful, or None otherwise
            // Note: For async actions (CompletableFuture<T>), checkpoint.getResult().get() returns
            // the unwrapped T value, not the CompletableFuture<T>
            if (checkpoint.isSuccessful && checkpoint.result.isRight) {
                return Option.of(checkpoint.result.get())
            }
            return Option.none()
        }

        /**
         * Builds the argument array for invoking a compensation method based on its parameter count.
         *
         * <p>Compensation methods can have 1 or 2 parameters:
         *
         * <ul>
         *   <li>1 parameter: receives the original input that was passed to the action
         *   <li>2 parameters: receives both the original input and the action's return value
         * </ul>
         *
         * <p>For action methods that return CompletableFuture&lt;T&gt;, the second parameter will be the
         * unwrapped T value, not the CompletableFuture&lt;T&gt; itself. This is validated at action
         * creation time by ActionValidator.
         *
         * @param compensationMethod The compensation method to build arguments for.
         * @param originalInput The original input that was passed to the corresponding action method.
         * @param actionResult The unwrapped result returned by the corresponding action method.
         * @return An array of arguments suitable for invoking the compensation method.
         * @throws IllegalStateException if the compensation method has an unexpected parameter count.
         */
        private fun buildCompensationArguments(
            compensationMethod: Method,
            originalInput: Option<Any?>,
            actionResult: Option<Any?>,
        ): Array<Any?> {
            // Use getUserParameterCount to exclude the hidden Continuation parameter that the
            // Kotlin compiler appends to suspend compensation methods.
            val parameterCount = SuspendSupport.getUserParameterCount(compensationMethod)
            if (parameterCount == 1) {
                // Single parameter: pass original input (or null if undefined)
                return arrayOf(originalInput.getOrElse(null as Any?))
            } else if (parameterCount == 2) {
                // Two parameters: pass original input and action result
                val firstArg = originalInput.getOrElse(null as Any?)
                val secondArg = actionResult.getOrElse(null as Any?)
                return arrayOf(firstArg, secondArg)
            } else {
                // This should not happen due to validation
                throw IllegalStateException(
                    String.format(
                        "Compensation method %s has %d parameters, expected 1 or 2. This should have been" +
                            " caught by validation.",
                        compensationMethod.name,
                        parameterCount,
                    ),
                )
            }
        }

        private fun handleCompensationExecutionError(
            e: Throwable,
            executionContext: ExecutionContext,
        ): CompensationResult {
            var cause: Throwable? = e
            if (e is CompletionException) {
                cause = e.cause
            }
            // Wrap unexpected errors
            cause = wrapUnexpectedError(cause!!, "compensation")
            log.warn(
                "compensation execution threw exception. workflowId={}",
                executionContext.workflow.workflowId,
                cause,
            )
            if (cause is RetryableError) {
                // Retryable error: set status to COMPENSATION_IN_PROGRESS for retry
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "retryableError",
                            "error",
                            cause.javaClass.simpleName,
                            "workflowClass",
                            executionContext.workflow.workflowClass.simpleName,
                            "workflowMethod",
                            executionContext.workflow.workflowMethod,
                            "source",
                            "compensation",
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                return CompensationResult.retryableError(cause as SkipperError)
            } else {
                // Non-retryable error: set status to COMPENSATION_ERROR
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "nonRetryableError",
                            "error",
                            cause.javaClass.simpleName,
                            "workflowClass",
                            executionContext.workflow.workflowClass.simpleName,
                            "workflowMethod",
                            executionContext.workflow.workflowMethod,
                            "source",
                            "compensation",
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                return CompensationResult.nonRetryableError(cause as SkipperError)
            }
        }

        private fun getWorkflowInstance(
            workflowClass: Class<out Workflow>,
            workflowId: String,
        ): Workflow {
            try {
                val workflow = injector.getInstance(workflowClass)
                workflow.id = workflowId
                injector.injectWorkflowMembers(workflow, internalDeps)
                return workflow
            } catch (e: Exception) {
                throw NonRetryableError(
                    String.format("Cannot provide a workflow for class %s", workflowClass),
                    e,
                )
            }
        }

        private fun wrapUnexpectedError(
            e: Throwable,
            source: String
        ): Throwable {
            if (e is NonRetryableError ||
                e is RetryableError ||
                e is WaitSignal ||
                e is InternalError ||
                e is PersistentRetryableError
            ) {
                return e
            }
            // All unexpected errors throws by the workflow code are considered non-retryable.
            // These will mostly be bugs in the workflow code.
            metrics
                .counter(
                    ImmutableMap.of("error", e.javaClass.simpleName, "source", source),
                    METRIC_COMPONENT_NAME,
                    "workflowExecutionUnexpectedErrors",
                )
                .inc()
            return NonRetryableError(
                String.format("workflow method code threw an unexpected exception: %s", e.message),
                ApplicationError.fromException(e),
            )
        }

        /**
         * Represents the result of a compensation flow execution.
         *
         * <p>Unlike regular workflow execution results, compensation results are simpler: - No workflow
         * state changes (compensation just executes methods) - No return values (compensation either
         * succeeds or fails) - Simple error model (retryable vs non-retryable)
         */
        class CompensationResult internal constructor(
            /** The new status for the workflow after compensation execution */
            val newStatus: WorkflowInstance.Status,
            /** Optional error information if compensation failed */
            val error: Option<SkipperError>,
        ) {
            init {
                @Suppress("SENSELESS_COMPARISON")
                if (newStatus == null) {
                    throw NullPointerException("newStatus is marked non-null but is null")
                }
            }

            class CompensationResultBuilder internal constructor() {
                private var newStatus: WorkflowInstance.Status? = null
                private var errorSet = false
                private var errorValue: Option<SkipperError>? = null

                /**
                 * @return `this`.
                 */
                fun newStatus(newStatus: WorkflowInstance.Status): CompensationResultBuilder {
                    @Suppress("SENSELESS_COMPARISON")
                    if (newStatus == null) {
                        throw NullPointerException("newStatus is marked non-null but is null")
                    }
                    this.newStatus = newStatus
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun error(error: Option<SkipperError>): CompensationResultBuilder {
                    this.errorValue = error
                    errorSet = true
                    return this
                }

                fun build(): CompensationResult {
                    var errorValue = this.errorValue
                    if (!this.errorSet) errorValue = defaultError()
                    return CompensationResult(this.newStatus!!, errorValue!!)
                }

                override fun toString(): String =
                    "CompensationExecutor.CompensationResult.CompensationResultBuilder(newStatus=" +
                        this.newStatus +
                        ", error\$value=" +
                        this.errorValue +
                        ")"
            }

            fun toBuilder(): CompensationResultBuilder =
                CompensationResultBuilder()
                    .newStatus(this.newStatus)
                    .error(this.error)

            override fun equals(o: Any?): Boolean {
                if (o === this) return true
                if (o !is CompensationResult) return false
                val other: CompensationResult = o
                val thisNewStatus: Any? = this.newStatus
                val otherNewStatus: Any? = other.newStatus
                if (if (thisNewStatus == null) otherNewStatus != null else thisNewStatus != otherNewStatus) {
                    return false
                }
                val thisError: Any? = this.error
                val otherError: Any? = other.error
                if (if (thisError == null) otherError != null else thisError != otherError) return false
                return true
            }

            override fun hashCode(): Int {
                val prime = 59
                var result = 1
                val newStatus: Any? = this.newStatus
                result = result * prime + (if (newStatus == null) 43 else newStatus.hashCode())
                val error: Any? = this.error
                result = result * prime + (if (error == null) 43 else error.hashCode())
                return result
            }

            override fun toString(): String =
                "CompensationExecutor.CompensationResult(newStatus=" +
                    this.newStatus +
                    ", error=" +
                    this.error +
                    ")"

            companion object {
                /** Creates a successful compensation result. */
                @JvmStatic
                fun success(): CompensationResult =
                    builder()
                        .newStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
                        .build()

                /** Creates a retryable error compensation result. */
                @JvmStatic
                fun retryableError(error: SkipperError): CompensationResult =
                    builder()
                        .newStatus(WorkflowInstance.Status.COMPENSATION_IN_PROGRESS)
                        .error(Option.of(error))
                        .build()

                /** Creates a non-retryable error compensation result. */
                @JvmStatic
                fun nonRetryableError(error: SkipperError): CompensationResult =
                    builder()
                        .newStatus(WorkflowInstance.Status.COMPENSATION_ERROR)
                        .error(Option.of(error))
                        .build()

                private fun defaultError(): Option<SkipperError> = Option.none()

                @JvmStatic
                fun builder(): CompensationResultBuilder = CompensationResultBuilder()
            }
        }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(CompensationExecutor::class.java)

            private const val METRIC_COMPONENT_NAME = "compensationExecutor"
        }
    }
