package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.ContextSnapshot
import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.ExponentialRetryStrategy
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.PersistentRetryStrategy
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperAnnotationNames.DEFAULT_CHECKPOINT_MODE
import com.airbnb.skipper.SuspendSupport
import com.airbnb.skipper.WorkflowCancelledException
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.google.common.collect.ImmutableMap
import io.opentracing.Tracer
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javassist.util.proxy.ProxyObject
import javax.inject.Inject
import javax.inject.Named

/** ActionExecutor is responsible for executing action methods. */
// `open` so this non-final (Java-baseline) class stays Mockito-mockable — skipper's own
// InjectorTest mocks it. ABI-faithful (matches the non-final baseline); no behavior change.
open class ActionExecutor
    @Inject
    constructor(
        private val workflowStore: WorkflowStore,
        @param:Named(DEFAULT_CHECKPOINT_MODE) private val defaultCheckpointMode: CheckpointMode,
        private val metrics: Metrics,
        private val errorMapper: ActionErrorMapper,
        private val eventPublisher: EventPublisher,
        private val executionMetricsCollector: ExecutionMetricsCollector,
        private val tracer: Tracer,
        private val contextPropagator: ContextPropagator,
        private val featureGate: FeatureGate?,
    ) {

        /**
         * Executes an action method.
         *
         * <p>This method will execute the action method of the client action implementation.
         *
         * <p>Upon successful execution of the action method, this will: 1) Increment the action
         * iteration count for the action method. 2) Create an {@link ActionCheckpoint} object with the
         * result of the action method and add it to the dirty checkpoints list in the {@link
         * ExecutionContext}. This will allow the upstreams to persist the checkpoint later. 3) Return
         * the result of the action method.
         *
         * @param request The execution action request.
         * @return The result of the action method.
         */
        // Catches Throwable to faithfully reproduce the original Java behavior: every error thrown by
        // the action method is mapped and rethrown. Narrowing the catch would change behavior, so the
        // detekt rule is suppressed rather than the catch tightened.
        @Suppress("TooGenericExceptionCaught")
        open fun executeAction(request: ExecuteActionRequest): Any? {
            val checkpointName = request.checkpointName
            // Named checkpoints must be unique within a workflow execution. Fail fast on the second
            // occurrence so the author gets an immediate error rather than a confusing replay mismatch.
            if (checkpointName != null) {
                if (request.executionContext.isNamedCheckpointConsumed(checkpointName)) {
                    throw IllegalStateException(
                        "Duplicate checkpoint name '" +
                            checkpointName +
                            "' in workflow method. Each named checkpoint must be unique within a workflow" +
                            " method."
                    )
                }
                request.executionContext.addConsumedNamedCheckpoint(checkpointName)
            }
            // Check if the action has already been executed and we have a checkpoint for it
            val checkpoint: Option<ActionCheckpoint> = request.getActionCheckpoint()
            if (checkpoint.isDefined) {
                // If we have a checkpoint, just return it.
                // For named checkpoints the name is the identity, so positional iteration is irrelevant.
                if (checkpointName == null) {
                    request.executionContext.incrementActionIteration(
                        request.baseActionClass,
                        request.actionMethodName,
                    )
                }
                return checkpoint.get().generateResult()
            }
            // In-flight cancellation checkpoint. Before starting a NOT-yet-checkpointed action,
            // re-read the workflow's CURRENT status from the store. If it was cancelled (e.g. by
            // WorkflowsService.cancelWorkflows) while this execution was running, stop here rather than
            // start another action — bounding a mid-flight cancel to at most the one action already
            // running. The store read (not executionContext.workflow, which is a start-of-execution
            // snapshot) is what lets a cancel written by another worker be observed. Opt-in and off by
            // default.
            if (featureGate?.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS) == true) {
                val workflowId = request.executionContext.workflow.workflowId
                val current: Option<WorkflowInstance> = workflowStore.getWorkflow(workflowId)
                if (current.isDefined && current.get().status == WorkflowInstance.Status.CANCELLED) {
                    log.info(
                        "workflow {} was CANCELLED while executing; stopping before action {}#{}",
                        workflowId,
                        request.baseActionClass.simpleName,
                        request.actionMethodName,
                    )
                    metrics
                        .counter(
                            ImmutableMap.of(
                                "workflowClass",
                                request.executionContext.workflow.workflowClass.simpleName,
                                ACTION_CLASS_TAG,
                                request.baseActionClass.simpleName,
                            ),
                            METRIC_COMPONENT_NAME,
                            "inflightCancellationStopped",
                        )
                        .inc()
                    throw WorkflowCancelledException(
                        "workflow " + workflowId + " was CANCELLED before action " +
                            request.baseActionClass.simpleName + "#" + request.actionMethodName +
                            " started",
                    )
                }
            }
            val startTime = request.executionContext.clock.instant()
            val checkpointTag = CheckpointTag.fromExecuteActionRequest(request)
            // Get the action implementation object
            var actionCheckpointBuilder =
                ActionCheckpoint.builder().checkpointTag(checkpointTag).executionStartTime(startTime)
            var shouldIncrementIteration = true
            var shouldAddCheckpoint = true
            var isAsyncResult = false
            var checkpointMode = getCheckpointMode(request.originalMethod)
            // Override checkpoint mode for actions that have compensation methods
            // Compensable actions must always be checkpointed to enable compensation flow
            val actionInspector = ActionInspector(request.baseActionClass)
            val isCompensable = actionInspector.hasCompensationMethod(request.actionMethodName)
            // If the action is compensable, we need to enforce a checkpointing and make sure that
            // the input is stored for compensation purposes.
            if (isCompensable) {
                // Store input for compensation purposes only if action is compensable
                actionCheckpointBuilder = actionCheckpointBuilder.input(request.getFirstArgOrNull())
                // If checkpoint mode is DEFAULT, use the default checkpoint mode
                if (checkpointMode == CheckpointMode.DEFAULT) {
                    checkpointMode = defaultCheckpointMode
                }
                // Force checkpointing for compensable actions if mode is NO_CHECKPOINT
                if (checkpointMode == CheckpointMode.NO_CHECKPOINT) {
                    log.warn(
                        "Action {}#{} has compensation method but NO_CHECKPOINT mode. Overriding to" +
                            " EVENTUAL_CHECKPOINT for compensation support",
                        request.baseActionClass.simpleName,
                        request.actionMethodName,
                    )
                    checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
                }
            }
            // If already set to checkpoint (IMMEDIATE or EVENTUAL), keep that setting
            // Create final copies for lambda usage
            val finalCheckpointMode = checkpointMode
            val finalIsCompensable = isCompensable
            val timer =
                metrics.timer(
                    ImmutableMap.of(
                        ACTION_CLASS_TAG,
                        request.baseActionClass.simpleName,
                        ACTION_METHOD_TAG,
                        request.actionMethodName,
                    ),
                    METRIC_COMPONENT_NAME,
                    "actionExecutionTime",
                )
            val span = executionMetricsCollector.createActionSpan(request)
            try {
                timer.time().use { _ ->
                    tracer.activateSpan(span).use { _ ->
                        // Invoke the actual action implementation, this will call the client's action
                        // code.
                        // For Kotlin suspend functions, use SuspendSupport.invokeSuspendFunctionAsync
                        // which
                        // returns a CompletableFuture without blocking. The future naturally falls
                        // through to
                        // the existing CompletableFuture handling path below (line ~189) which creates
                        // the
                        // checkpoint, maps errors, increments iterations, and finishes the span in
                        // whenComplete.
                        // The args array has already had the Continuation stripped by the
                        // Workflow.actions()
                        // proxy handler, so we pass user args directly here.
                        request.proxyMethod.isAccessible = true
                        val result: Any?
                        if (SuspendSupport.isSuspendFunction(request.proxyMethod)) {
                            // Capture context for WITHIN-ACTION suspension: if the action's own suspend
                            // function suspends internally (e.g., calls .await() on a downstream
                            // service),
                            // the Continuation.resumeWith in SuspendSupport restores context on the
                            // completing thread. This is separate from the BETWEEN-ACTION propagation in
                            // Workflow.actions() proxy handler, which restores context when the workflow
                            // coroutine resumes after an action completes.
                            var snapshot: ContextSnapshot
                            try {
                                snapshot = contextPropagator.capture()
                            } catch (e: Throwable) {
                                log.warn(
                                    "ContextPropagator.capture() failed, context propagation disabled for this action",
                                    e,
                                )
                                snapshot = ContextSnapshot.NOOP
                            }
                            val userArgs: Array<out Any?> =
                                if (request.arg != null) request.arg else arrayOfNulls<Any?>(0)
                            result =
                                SuspendSupport.invokeSuspendFunctionAsync(
                                    request.proxyMethod,
                                    request.actionObject,
                                    snapshot,
                                    *userArgs,
                                )
                        } else {
                            result =
                                if (request.arg != null) {
                                    request.proxyMethod.invoke(request.actionObject, *request.arg)
                                } else {
                                    request.proxyMethod.invoke(request.actionObject)
                                }
                        }
                        if (result is CompletableFuture<*>) {
                            shouldIncrementIteration = false
                            shouldAddCheckpoint = false
                            isAsyncResult = true
                            // If the result is a CompletableFuture, we'll create the checkpoint once the
                            // CompletableFuture
                            // completes.
                            return result
                                .exceptionally { e ->
                                    var err = e
                                    if (err is CompletionException) {
                                        err = err.cause
                                    }
                                    throw errorMapper.convertToSkipperError(
                                        err,
                                        request,
                                        request.exceptionClassifier,
                                    )
                                }
                                .whenComplete { r, e ->
                                    var err = e
                                    val eitherResult: Either<Throwable, Any?>
                                    var isTransient = false
                                    if (err != null) {
                                        err = errorMapper.unwrapError(err)
                                        // Only non-retryable errors are considered as final results.
                                        isTransient = err !is NonRetryableError
                                        eitherResult = Either.left(err)
                                        // Set error tags on span for async errors
                                        span.setTag(
                                            ExecutionMetricsCollector.TAG_ACTION_RESULT,
                                            "error",
                                        )
                                        span.setTag(ExecutionMetricsCollector.TAG_ERROR, true)
                                        span.log(
                                            ImmutableMap.of(
                                                ExecutionMetricsCollector.TAG_ERROR_OBJECT,
                                                err,
                                            )
                                        )
                                    } else {
                                        eitherResult = Either.right(r)
                                        // Set success tag on span for async success
                                        span.setTag(
                                            ExecutionMetricsCollector.TAG_ACTION_RESULT,
                                            "success",
                                        )
                                    }
                                    // Finish the span after async action completes
                                    span.finish()
                                    if (!isTransient) {
                                        request.incrementActionIteration()
                                    }
                                    var asyncCheckpointBuilder =
                                        ActionCheckpoint.builder()
                                            .checkpointTag(checkpointTag)
                                            .executionStartTime(startTime)
                                            .result(eitherResult)
                                            .executionEndTime(
                                                request.executionContext.clock.instant()
                                            )
                                            .isTransient(isTransient)
                                            .resultIsAsync(true)
                                    // Only store input for compensable actions
                                    if (finalIsCompensable) {
                                        asyncCheckpointBuilder =
                                            asyncCheckpointBuilder.input(request.getFirstArgOrNull())
                                    }
                                    addCheckpoint(
                                        request.executionContext,
                                        asyncCheckpointBuilder.build(),
                                        finalCheckpointMode,
                                        request,
                                    )
                                    // Emit event for async action completion/failure
                                    if (err != null) {
                                        eventPublisher.publishEvent(
                                            Event.create(
                                                Event.Type.ACTION_FAILED,
                                                request.executionContext.workflow.workflowId,
                                                String.format(
                                                    "%s.%s (async) - %s",
                                                    request.baseActionClass.simpleName,
                                                    request.actionMethodName,
                                                    err.javaClass.simpleName,
                                                ),
                                            )
                                        )
                                    } else {
                                        eventPublisher.publishEvent(
                                            Event.create(
                                                Event.Type.ACTION_COMPLETED,
                                                request.executionContext.workflow.workflowId,
                                                String.format(
                                                    "%s.%s (async)",
                                                    request.baseActionClass.simpleName,
                                                    request.actionMethodName,
                                                ),
                                            )
                                        )
                                    }
                                }
                        } else {
                            actionCheckpointBuilder =
                                actionCheckpointBuilder
                                    .result(Either.right(result))
                                    .executionEndTime(request.executionContext.clock.instant())
                                    .isTransient(false)
                        }
                        metrics
                            .counter(
                                ImmutableMap.of(
                                    ACTION_CLASS_TAG,
                                    request.baseActionClass.simpleName,
                                    ACTION_METHOD_TAG,
                                    request.actionMethodName,
                                    RESULT_TAG,
                                    "success",
                                ),
                                METRIC_COMPONENT_NAME,
                                ACTION_EXECUTION_RESULT,
                            )
                            .inc()
                        // Emit event for successful action completion
                        eventPublisher.publishEvent(
                            Event.create(
                                Event.Type.ACTION_COMPLETED,
                                request.executionContext.workflow.workflowId,
                                String.format(
                                    "%s.%s",
                                    request.baseActionClass.simpleName,
                                    request.actionMethodName,
                                ),
                            )
                        )
                        span.setTag(ExecutionMetricsCollector.TAG_ACTION_RESULT, "success")
                        return result
                    }
                }
            } catch (e: Throwable) {
                // All errors thrown by the action method are considered retryable unless explicitly
                // marked
                // as NonRetryable errors.
                val wrappedError =
                    errorMapper.mapActionExecutionError(e, request, request.exceptionClassifier)
                // Only non-retryable errors are considered as final results.
                shouldIncrementIteration = wrappedError is NonRetryableError
                val isTransient = !shouldIncrementIteration
                actionCheckpointBuilder =
                    actionCheckpointBuilder.result(Either.left(wrappedError)).isTransient(isTransient)
                // This is the error we want to report to metrics, we want to know the underlying error
                // type and not the wrapper type.
                val cause = if (e is InvocationTargetException) e.cause else e
                metrics
                    .counter(
                        ImmutableMap.of(
                            ACTION_CLASS_TAG,
                            request.baseActionClass.simpleName,
                            ACTION_METHOD_TAG,
                            request.actionMethodName,
                            RESULT_TAG,
                            "error",
                            ERROR_TAG,
                            wrappedError.javaClass.simpleName,
                            ERROR_CAUSE,
                            cause!!.javaClass.simpleName,
                        ),
                        METRIC_COMPONENT_NAME,
                        ACTION_EXECUTION_RESULT,
                    )
                    .inc()
                // Emit event for action failure
                eventPublisher.publishEvent(
                    Event.create(
                        Event.Type.ACTION_FAILED,
                        request.executionContext.workflow.workflowId,
                        String.format(
                            "%s.%s - %s",
                            request.baseActionClass.simpleName,
                            request.actionMethodName,
                            wrappedError.javaClass.simpleName,
                        ),
                    )
                )
                span.setTag(ExecutionMetricsCollector.TAG_ACTION_RESULT, "error")
                span.setTag(ExecutionMetricsCollector.TAG_ERROR, true)
                span.log(ImmutableMap.of(ExecutionMetricsCollector.TAG_ERROR_OBJECT, e))
                throw wrappedError
            } finally {
                // Only finish the span for synchronous actions
                // For async actions, the span is finished in the whenComplete handler
                if (!isAsyncResult) {
                    span.finish()
                }
                if (shouldIncrementIteration) {
                    request.incrementActionIteration()
                }
                // If the result is a CompletableFuture, the checkpoint will be created once the
                // CompletableFuture completes, otherwise, we'll create the checkpoint now.
                if (shouldAddCheckpoint) {
                    // Add this execution checkpoint to the list of dirty checkpoints so that they can be
                    // persisted late if needed.
                    addCheckpoint(
                        request.executionContext,
                        actionCheckpointBuilder
                            .executionEndTime(request.executionContext.clock.instant())
                            .resultIsAsync(false)
                            .build(),
                        finalCheckpointMode,
                        request,
                    )
                }
            }
        }

        /**
         * Get the checkpoint mode for the action method or compensation method. If this is an action
         * method (it's annotated with {@link Execute}), it will return the checkpoint mode specified in
         * the {@link Execute} annotation. If this is a compensation method (annotated with {@link
         * Compensate}), it will return the checkpoint mode specified in the {@link Compensate}
         * annotation. If neither annotation specifies a checkpoint mode, it will return the default
         * checkpoint mode.
         */
        private fun getCheckpointMode(originalMethod: Method): CheckpointMode {
            val executeAnnotation = originalMethod.getAnnotation(Execute::class.java)
            if (executeAnnotation != null) {
                return executeAnnotation.checkpointMode
            }
            val compensateAnnotation = originalMethod.getAnnotation(Compensate::class.java)
            if (compensateAnnotation != null) {
                return compensateAnnotation.checkpointMode
            }
            return defaultCheckpointMode
        }

        private fun addCheckpoint(
            executionContext: ExecutionContext,
            checkpoint: ActionCheckpoint,
            actionCheckpointMode: CheckpointMode,
            request: ExecuteActionRequest,
        ) {
            var checkpointMode = defaultCheckpointMode
            if (actionCheckpointMode != CheckpointMode.DEFAULT) {
                checkpointMode = actionCheckpointMode
            }
            if (checkpointMode == CheckpointMode.IMMEDIATE_CHECKPOINT) {
                try {
                    workflowStore.storeActionCheckpoints(
                        executionContext.workflow.workflowId,
                        List.of(checkpoint),
                    )
                } catch (e: Exception) {
                    throw RetryableError(
                        "Failed to persist action checkpoint immediately after execution",
                        ApplicationError.fromException(e),
                        DEFAULT_CHECKPOINT_ERROR_RETRY_STRTEGY,
                        request.getRetryCount(),
                    )
                }
                executionContext.shouldReloadWorkflowInstance.set(true)
            } else {
                // Checkpoints are not persisted immediately, so we add them to the dirty list, and they
                // will
                // be flushed to disk all at once later in the execution.
                executionContext.addDirtyCheckpoint(checkpoint)
            }
        }

        class ExecuteActionRequest(
            val actionObject: Actions,
            /** The action method to execute. This will be the proxy method */
            val proxyMethod: Method,
            /** The method that was overridden by the action method (the proxy). */
            val originalMethod: Method,
            /**
             * The argument to pass to the action method. Regular action methods will have a single
             * argument. Compensation methods can potentially have 2 arguments. The first argument is the
             * input to the original action method, and the second is the result of the original action
             * method.
             */
            val arg: Array<out Any?>?,
            /** The current execution context. */
            val executionContext: ExecutionContext,
            val retryStrategy: RetryStrategy,
            /** The action-level exception classifier. If null, the global classifier will be used. */
            val exceptionClassifier: ExceptionClassifier?,
            @get:JvmName("isCompensatingAction") val isCompensatingAction: Boolean,
            /**
             * Optional checkpoint name from invocation-site naming (Actions.named()). When present,
             * checkpoint matching uses the name instead of positional iteration.
             */
            val checkpointName: String?,
        ) {
            constructor(
                actionObject: Actions,
                proxyMethod: Method,
                originalMethod: Method,
                arg: Array<out Any?>?,
                executionContext: ExecutionContext,
                retryStrategy: RetryStrategy,
                exceptionClassifier: ExceptionClassifier?,
                isCompensatingAction: Boolean,
            ) : this(
                actionObject,
                proxyMethod,
                originalMethod,
                arg,
                executionContext,
                retryStrategy,
                exceptionClassifier,
                isCompensatingAction,
                null,
            )

            /**
             * Get the number of times the action associated with the current execution request has been
             * retried.
             *
             * @return
             */
            fun getRetryCount(): Int {
                // Build the tag exactly as getActionCheckpoint() does, via
                // CheckpointTag.fromExecuteActionRequest — it carries the checkpointName when the
                // action was invoked with named(...), falling back to the positional
                // (class, method, iteration) tuple otherwise. Hand-rolling a positional-only tag
                // here made retry accounting miss named checkpoints entirely (they never match a
                // nameless tag), so a bounded RetryStrategy could never exhaust for named steps.
                return executionContext.getActionRetryCount(
                    CheckpointTag.fromExecuteActionRequest(this)
                )
            }

            fun getActionCheckpoint(): Option<ActionCheckpoint> {
                val checkpoint: Option<ActionCheckpoint> =
                    executionContext.getActionCheckpoint(CheckpointTag.fromExecuteActionRequest(this))
                if (isCompensatingAction && checkpoint.isDefined) {
                    if (!checkpoint.get().isSuccessful && !checkpoint.get().isTransient) {
                        // Non transient error checkpoints in the compensation flow should be ignored.
                        // This is because all errors in the compensation flow are to be considered
                        // transient,
                        // so that they can be retried. In other words, non-retryable errors cannot
                        // transition
                        // the workflow to a terminal state in the compensation flow.
                        return Option.none()
                    }
                }
                return checkpoint
            }

            /**
             * If the action class is a proxy, the name will be the name of the proxy class, which is
             * different from the real Action class name. This method will return the real action class
             * name.
             */
            val baseActionClass: Class<out Actions>
                get() {
                    val actionClass: Class<out Actions> = actionObject.javaClass
                    // Check if the class is a proxy created by Java Assist
                    if (ProxyObject::class.java.isAssignableFrom(actionClass)) {
                        // This is a proxy class, so we need to get the real action class
                        // If Java Assist is used, typically the superclass of the proxy is the actual
                        // class
                        val superClass: Class<*>? = actionClass.superclass
                        if (superClass != null && Actions::class.java.isAssignableFrom(superClass)) {
                            @Suppress("UNCHECKED_CAST")
                            return superClass as Class<out Actions>
                        }
                    }
                    return actionClass
                }

            val actionMethodName: String
                get() = originalMethod.name

            fun incrementActionIteration() {
                // For named checkpoints the name is the identity, so positional iteration is irrelevant.
                if (checkpointName != null) {
                    return
                }
                executionContext.incrementActionIteration(baseActionClass, actionMethodName)
            }

            fun getFirstArgOrNull(): Any? = if (arg != null && arg.isNotEmpty()) arg[0] else null

            class ExecuteActionRequestBuilder internal constructor() {
                private var actionObject: Actions? = null
                private var proxyMethod: Method? = null
                private var originalMethod: Method? = null
                private var arg: Array<out Any?>? = null
                private var executionContext: ExecutionContext? = null
                private var retryStrategy: RetryStrategy? = null
                private var exceptionClassifier: ExceptionClassifier? = null
                private var isCompensatingActionSet = false
                private var isCompensatingActionValue = false
                private var checkpointName: String? = null

                /**
                 * @return `this`.
                 */
                fun actionObject(actionObject: Actions): ExecuteActionRequestBuilder {
                    this.actionObject = actionObject
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun proxyMethod(proxyMethod: Method): ExecuteActionRequestBuilder {
                    this.proxyMethod = proxyMethod
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun originalMethod(originalMethod: Method): ExecuteActionRequestBuilder {
                    this.originalMethod = originalMethod
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun arg(arg: Array<out Any?>?): ExecuteActionRequestBuilder {
                    this.arg = arg
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun executionContext(executionContext: ExecutionContext): ExecuteActionRequestBuilder {
                    this.executionContext = executionContext
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun retryStrategy(retryStrategy: RetryStrategy): ExecuteActionRequestBuilder {
                    this.retryStrategy = retryStrategy
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun exceptionClassifier(exceptionClassifier: ExceptionClassifier?): ExecuteActionRequestBuilder {
                    this.exceptionClassifier = exceptionClassifier
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun isCompensatingAction(isCompensatingAction: Boolean): ExecuteActionRequestBuilder {
                    this.isCompensatingActionValue = isCompensatingAction
                    isCompensatingActionSet = true
                    return this
                }

                /**
                 * @return `this`.
                 */
                fun checkpointName(checkpointName: String?): ExecuteActionRequestBuilder {
                    this.checkpointName = checkpointName
                    return this
                }

                fun build(): ExecuteActionRequest {
                    val isCompensatingActionValue =
                        if (isCompensatingActionSet) {
                            this.isCompensatingActionValue
                        } else {
                            `$default$isCompensatingAction`()
                        }
                    return ExecuteActionRequest(
                        this.actionObject
                            ?: throw NullPointerException(
                                "actionObject is marked non-null but is null",
                            ),
                        this.proxyMethod
                            ?: throw NullPointerException(
                                "proxyMethod is marked non-null but is null",
                            ),
                        this.originalMethod
                            ?: throw NullPointerException(
                                "originalMethod is marked non-null but is null",
                            ),
                        this.arg,
                        this.executionContext
                            ?: throw NullPointerException(
                                "executionContext is marked non-null but is null",
                            ),
                        this.retryStrategy
                            ?: throw NullPointerException(
                                "retryStrategy is marked non-null but is null",
                            ),
                        this.exceptionClassifier,
                        isCompensatingActionValue,
                        this.checkpointName,
                    )
                }

                override fun toString(): String =
                    "ActionExecutor.ExecuteActionRequest.ExecuteActionRequestBuilder(actionObject=" +
                        this.actionObject +
                        ", proxyMethod=" +
                        this.proxyMethod +
                        ", originalMethod=" +
                        this.originalMethod +
                        ", arg=" +
                        java.util.Arrays.deepToString(this.arg) +
                        ", executionContext=" +
                        this.executionContext +
                        ", retryStrategy=" +
                        this.retryStrategy +
                        ", exceptionClassifier=" +
                        this.exceptionClassifier +
                        ", isCompensatingAction\$value=" +
                        this.isCompensatingActionValue +
                        ", checkpointName=" +
                        this.checkpointName +
                        ")"
            }

            fun toBuilder(): ExecuteActionRequestBuilder =
                ExecuteActionRequestBuilder()
                    .actionObject(this.actionObject)
                    .proxyMethod(this.proxyMethod)
                    .originalMethod(this.originalMethod)
                    .arg(this.arg)
                    .executionContext(this.executionContext)
                    .retryStrategy(this.retryStrategy)
                    .exceptionClassifier(this.exceptionClassifier)
                    .isCompensatingAction(this.isCompensatingAction)
                    .checkpointName(this.checkpointName)

            override fun equals(o: Any?): Boolean {
                if (o === this) return true
                if (o !is ExecuteActionRequest) return false
                val other: ExecuteActionRequest = o
                if (this.isCompensatingAction != other.isCompensatingAction) return false
                if (this.actionObject != other.actionObject) return false
                if (this.proxyMethod != other.proxyMethod) return false
                if (this.originalMethod != other.originalMethod) return false
                if (!java.util.Arrays.deepEquals(this.arg, other.arg)) return false
                if (this.executionContext != other.executionContext) return false
                if (this.retryStrategy != other.retryStrategy) return false
                if (this.exceptionClassifier != other.exceptionClassifier) return false
                if (this.checkpointName != other.checkpointName) return false
                return true
            }

            override fun hashCode(): Int {
                val prime = 59
                var result = 1
                result = result * prime + (if (this.isCompensatingAction) 79 else 97)
                result = result * prime + (this.actionObject?.hashCode() ?: 43)
                result = result * prime + (this.proxyMethod?.hashCode() ?: 43)
                result = result * prime + (this.originalMethod?.hashCode() ?: 43)
                result = result * prime + java.util.Arrays.deepHashCode(this.arg)
                result = result * prime + (this.executionContext?.hashCode() ?: 43)
                result = result * prime + (this.retryStrategy?.hashCode() ?: 43)
                result = result * prime + (this.exceptionClassifier?.hashCode() ?: 43)
                result = result * prime + (this.checkpointName?.hashCode() ?: 43)
                return result
            }

            override fun toString(): String =
                "ActionExecutor.ExecuteActionRequest(actionObject=" +
                    this.actionObject +
                    ", proxyMethod=" +
                    this.proxyMethod +
                    ", originalMethod=" +
                    this.originalMethod +
                    ", arg=" +
                    java.util.Arrays.deepToString(this.arg) +
                    ", executionContext=" +
                    this.executionContext +
                    ", retryStrategy=" +
                    this.retryStrategy +
                    ", exceptionClassifier=" +
                    this.exceptionClassifier +
                    ", isCompensatingAction=" +
                    this.isCompensatingAction +
                    ", checkpointName=" +
                    this.checkpointName +
                    ")"

            companion object {
                private fun `$default$isCompensatingAction`(): Boolean = false

                @JvmStatic
                fun builder(): ExecuteActionRequestBuilder = ExecuteActionRequestBuilder()
            }
        }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(ActionExecutor::class.java)

            private const val METRIC_COMPONENT_NAME = "actionExecutor"
            private const val ACTION_CLASS_TAG = "actionClass"
            private const val ACTION_METHOD_TAG = "actionMethod"
            private const val ACTION_EXECUTION_RESULT = "actionExecutionResult"
            private const val ERROR_TAG = "error"
            private const val RESULT_TAG = "result"
            private const val ERROR_CAUSE = "cause"
            private val DEFAULT_CHECKPOINT_ERROR_RETRY_STRTEGY: RetryStrategy =
                PersistentRetryStrategy.of(
                    ExponentialRetryStrategy(Duration.ofSeconds(1), 10, 2.0, Duration.ofMinutes(30))
                )
        }
    }
