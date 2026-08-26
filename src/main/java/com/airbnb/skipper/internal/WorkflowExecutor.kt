package com.airbnb.skipper.internal

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.ContextSnapshot
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.SuspendSupport
import com.airbnb.skipper.ValidationError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.util.SkipperInternalDeps
import com.airbnb.skipper.util.injectWorkflowMembers
import com.google.common.collect.ImmutableMap
import io.opentracing.Tracer
import io.vavr.collection.Map
import io.vavr.control.Either
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * This component takes care of executing the appropriate workflow method implementation for a given
 * workflow instance through reflection.
 */
// `open` (not final) restores the Java baseline's non-final class semantics: the original
// `public class WorkflowExecutor` was subclassable, and existing immutable tests (e.g.
// SkipperEngineTest, WorkflowExecutionTaskHandlerTest) Mockito-mock this class. Kotlin classes are
// final by default; without `open` Mockito cannot create the mock subclass. The public instance
// methods are likewise `open` for the same reason.
open class WorkflowExecutor
    @Inject
    constructor(
        /**
         * The injector used to create workflow instances.
         *
         * <p><b>Important:</b> the injector must always return a new instance of the workflow class for
         * each call to {@link SkipperInjector#getInstance(Class)}, otherwise race conditions may occur.
         */
        private val injector: SkipperInjector,
        private val internalDeps: SkipperInternalDeps,
        private val middleware: RawRequestContextMiddleware,
        private val contextPropagator: ContextPropagator,
        private val tracer: Tracer,
        private val metrics: Metrics,
        private val executionMetricsCollector: ExecutionMetricsCollector,
    ) {
        /**
         * Executes the appropriate workflow method implementation for the given workflow instance.
         *
         * <p>The underlying workflow method is expected to throw exceptions in case of errors. The
         * exceptions should be of type {@link RetryableError} or {@link NonRetryableError}. In the case
         * of an unexpected error, the exception will be wrapped in a {@link NonRetryableError} and the
         * workflow will be marked as failed.
         *
         * <p>This method is not expected to throw any exception to the upstream caller. Any error will
         * be handled internally and returned as part of the result.
         *
         * @param workflowInstance The workflow instance to execute.
         * @param executorService The executor service to use for the execution of the workflow method.
         * @param executionContext The execution context to use for the execution of the workflow
         *     method.
         * @return A future with the result of the workflow execution. This result will contain a
         *     response value which would be of the type of the workflow's method return type in case
         *     the workflow completed successfully or an exception in case of error.
         */
        // Faithful port of the Java baseline's `catch (Throwable e)` cleanup-and-rethrow around the
        // workflow invocation (span.finish()/scope.close() then rethrow); broad catch is intentional.
        @Suppress("TooGenericExceptionCaught")
        open fun executeWorkflowMethod(
            workflowInstance: WorkflowInstance,
            executorService: ExecutorService,
            executionContext: ExecutionContext,
        ): CompletableFuture<ExecutionResult> {
            val inspector = AtomicReference<WorkflowInspector>()
            val invocation =
                RawActionInvocation(
                    workflowInstance.workflowId,
                    workflowInstance.requestContext,
                    workflowInstance.extraRequestData,
                )
            // Middleware: install host-specific state (e.g. thread-local request context).
            // afterExecution in the finally below guarantees cleanup runs whether the
            // invocation succeeds or throws.
            // invokeMethod returns a CompletableFuture for suspend functions (non-blocking)
            // or a direct result for regular methods.
            // Normalize to CompletableFuture for uniform chaining.
            // - Suspend functions: invokeMethod returns CF from invokeSuspendFunctionAsync
            //   (our internal non-blocking bridge). resultIsAsync must be false because the
            //   method's declared return type is NOT CompletableFuture.
            // - CF-returning methods: invokeMethod returns CF directly. resultIsAsync is true.
            // - Regular methods: invokeMethod returns a direct value. Wrap in completed CF.
            // Only mark as truly async if the method declares CF return type,
            // not if it's our internal suspend bridge
            // Close span/scope when the async workflow method completes
            // invokeMethod threw synchronously (non-suspend methods) — ensure span/scope
            // are cleaned up before the exception propagates through the CF chain.
            return CompletableFuture.supplyAsync(
                {
                    if (workflowInstance.status.isTerminal() ||
                        workflowInstance.status.isCompensationInProgress()
                    ) {
                        log.warn(
                            "workflow instance is in a terminal state. workflowId={}",
                            workflowInstance.workflowId,
                        )
                        return@supplyAsync CompletableFuture.completedFuture(
                            convertToExecutionResult(workflowInstance),
                        )
                    }
                    val workflow =
                        getWorkflowInstance(
                            workflowInstance.workflowClass,
                            workflowInstance.workflowId,
                        )
                    workflow.executionContext = executionContext
                    inspector.set(WorkflowInspector(workflow))
                    inspector.get().validate()
                    inspector.get().setState(workflowInstance.state)
                    val workflowMethod =
                        inspector.get().getWorkflowMethod(workflowInstance.workflowMethod)
                    val span = executionMetricsCollector.createWorkflowSpan(workflowInstance)
                    val scope = tracer.activateSpan(span)
                    middleware.rawBeforeExecution(invocation)
                    try {
                        val invokeResult =
                            invokeMethod(workflowMethod, workflow, workflowInstance.input)
                        val isSuspend = SuspendSupport.isSuspendFunction(workflowMethod)
                        val resultFuture: CompletableFuture<Any?>
                        val resultIsAsync: Boolean
                        if (invokeResult is CompletableFuture<*>) {
                            resultIsAsync = !isSuspend
                            resultFuture =
                                invokeResult.thenApply { value -> value as Any? }
                        } else {
                            resultIsAsync = false
                            resultFuture = CompletableFuture.completedFuture(invokeResult)
                        }
                        return@supplyAsync resultFuture
                            .thenApply { result ->
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
                                            "workflow",
                                        ),
                                        METRIC_COMPONENT_NAME,
                                        "workflowExecutions",
                                    )
                                    .inc()
                                log.debug(
                                    "workflow execution completed successfully. workflowId={}," +
                                        " result={}",
                                    executionContext.workflow.workflowId,
                                    result,
                                )
                                ExecutionResult.builder()
                                    .result(Either.right(result))
                                    .resultIsAsync(resultIsAsync)
                                    .newState(inspector.get().getState())
                                    .newStatus(WorkflowInstance.Status.COMPLETED)
                                    .build()
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
                .exceptionally { e ->
                    handleExecutionError(e, executionContext, "workflow", false, inspector)
                }
        }

        private fun convertToExecutionResult(workflowInstance: WorkflowInstance): ExecutionResult {
            val result: WorkflowInstance.Result<out Any?, out SkipperError>? =
                workflowInstance.flattenResult()
            if (result == null) {
                throw IllegalStateException(
                    "workflow instance is in a terminal state but has no result. workflowId=" +
                        workflowInstance.workflowId,
                )
            }
            return ExecutionResult.builder()
                .result(
                    if (!result.isError()) Either.right(result.ok) else Either.left(result.error),
                )
                .resultIsAsync(workflowInstance.isResultAsync())
                .newState(workflowInstance.state)
                .newStatus(workflowInstance.status)
                .build()
        }

        @Suppress("TooGenericExceptionCaught")
        private fun waitForAsyncResultIfNeeded(result: Any?): Any? {
            try {
                val resultIsAsync = result is CompletableFuture<*>
                if (resultIsAsync) {
                    try {
                        return (result as CompletableFuture<*>).join()
                    } catch (e: CompletionException) {
                        throw e.cause!!
                    }
                }
                return result
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        /**
         * Executes a signal method on the given workflow instance.
         *
         * <p>Similar to {@link #executeWorkflowMethod(WorkflowInstance, ExecutorService,
         * ExecutionContext)}, this method is not expected to throw any exception to the upstream caller.
         * Any error will be handled internally and returned as part of the result.
         *
         * @param workflowInstance The workflow instance to execute.
         * @param executorService The executor service to use for the execution of the workflow method.
         * @param executionContext The execution context to use for the execution of the workflow
         *     method.
         * @param signalMethodName The name of the signal method to execute. Must be a valid method name
         *     inside workflowInstance's workflow class that is annotated with {@link
         *     com.airbnb.skipper.SignalMethod}.
         * @param input The input to the signal method. Null is expected if the signal method does not
         *     take any arguments.
         * @return A future with the result of the signal execution.
         */
        @Suppress("TooGenericExceptionCaught")
        open fun executeSignalMethod(
            workflowInstance: WorkflowInstance,
            executorService: ExecutorService,
            executionContext: ExecutionContext,
            signalMethodName: String,
            input: Any?,
        ): CompletableFuture<ExecutionResult> {
            try {
                val inspector = AtomicReference<WorkflowInspector>()
                val invocation =
                    RawActionInvocation(
                        workflowInstance.workflowId,
                        workflowInstance.requestContext,
                        workflowInstance.extraRequestData,
                    )
                // Normalize to CompletableFuture for suspend signal methods
                return CompletableFuture.supplyAsync(
                    {
                        val workflow =
                            getWorkflowInstance(
                                workflowInstance.workflowClass,
                                workflowInstance.workflowId,
                            )
                        workflow.executionContext = executionContext
                        middleware.rawBeforeExecution(invocation)
                        try {
                            inspector.set(WorkflowInspector(workflow))
                            inspector.get().validate()
                            inspector.get().setState(workflowInstance.state)
                            val signalMethod =
                                inspector.get().getSignalMethod(signalMethodName)
                            val invokeResult = invokeMethod(signalMethod, workflow, input)
                            val resultFuture: CompletableFuture<Any?>
                            if (invokeResult is CompletableFuture<*>) {
                                resultFuture =
                                    invokeResult.thenApply { value -> value as Any? }
                            } else {
                                resultFuture = CompletableFuture.completedFuture(invokeResult)
                            }
                            return@supplyAsync resultFuture.thenApply { result ->
                                metrics
                                    .counter(
                                        ImmutableMap.of(
                                            "result",
                                            "completed",
                                            "workflowClass",
                                            executionContext
                                                .workflow
                                                .workflowClass
                                                .simpleName,
                                            "workflowMethod",
                                            executionContext.workflow.workflowMethod,
                                            "source",
                                            "signal",
                                        ),
                                        METRIC_COMPONENT_NAME,
                                        "workflowExecutions",
                                    )
                                    .inc()
                                ExecutionResult.builder()
                                    .result(Either.right(result))
                                    .newState(inspector.get().getState())
                                    .build()
                            }
                        } finally {
                            middleware.rawAfterExecution(invocation)
                        }
                    },
                    executorService,
                )
                    .thenCompose { cf -> cf }
                    .exceptionally { e ->
                        handleExecutionError(e, executionContext, "signal", true, inspector)
                    }
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        /**
         * Executes a query method on the given workflow instance.
         *
         * @param workflowClass The workflow class
         * @param workflowId The workflow id
         * @param state The persisted state of a running workflow, or empty map if it has never run
         * @param queryMethodName The name of the query method to execute. Must be a valid method name
         *     annotated with @QueryMethod
         * @param input The input to the query method. Null is expected if the query method does not take
         *     any arguments.
         * @return The result of the query method execution. This is the raw result from the query
         *     method invocation.
         */
        @Suppress("TooGenericExceptionCaught")
        open fun executeQueryMethod(
            workflowClass: Class<out Workflow>,
            workflowId: String,
            state: Map<String, Any?>,
            queryMethodName: String,
            input: Any?,
        ): Any? {
            try {
                val workflow = getWorkflowInstance(workflowClass, workflowId)
                val inspector = WorkflowInspector(workflow)
                inspector.validate()
                inspector.setState(state)
                val queryMethod = inspector.getQueryMethod(queryMethodName)
                val result = invokeMethod(queryMethod, workflow, input)
                // For suspend query methods, invokeMethod returns a CompletableFuture from
                // invokeSuspendFunctionAsync (our internal bridge). Unwrap it since the caller expects
                // the raw value. Do NOT unwrap for non-suspend methods that legitimately return
                // CompletableFuture as their declared type.
                if (SuspendSupport.isSuspendFunction(queryMethod)) {
                    return waitForAsyncResultIfNeeded(result)
                }
                return result
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun handleExecutionError(
            e: Throwable,
            executionContext: ExecutionContext,
            source: String,
            failOnValidationError: Boolean,
            inspector: AtomicReference<WorkflowInspector>,
        ): ExecutionResult {
            val resultBuilder = ExecutionResult.builder()
            var cause = e
            if (e is CompletionException) {
                cause = e.cause!!
            }
            if (cause is ValidationError && failOnValidationError) {
                throw cause
            }
            // If the error is not a NonRetryableError or a Retryable or a WaitSignal, we'll wrap it into
            // a non-retryable error. This means that any exception, including ApplicationError errors
            // WILL be converted to a non-retryable error.
            cause = wrapUnexpectedError(cause, source)
            if (cause is PersistentRetryableError) {
                val workflowInstance = executionContext.workflow
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "waitForRetry",
                            "workflowClass",
                            workflowInstance.workflowClass.simpleName,
                            "workflowMethod",
                            workflowInstance.workflowMethod,
                            "source",
                            source,
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                log.debug(
                    "workflow execution exhausted retries and is waiting for manual retry. workflowId={}," +
                        " newState={}, prevState={}",
                    workflowInstance.workflowId,
                    inspector.get().getState(),
                    workflowInstance.state,
                )
                return resultBuilder
                    .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
                    .newState(inspector.get().getState())
                    .result(Either.left(cause))
                    .build()
            }
            if (cause is WaitSignal) {
                // The workflow is on a conditional wait that has not been satisfied yet.
                // We will treat this as a delayed retry, where the retry delay represents the
                // wait timeout time.
                // It is the responsibility of the Skipper engine to determine if the
                // wait has reached its timeout and a non-retryable error should be thrown,
                // which can then be handled by the workflow code.
                val workflowInstance = executionContext.workflow
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "wait",
                            "workflowClass",
                            workflowInstance.workflowClass.simpleName,
                            "workflowMethod",
                            workflowInstance.workflowMethod,
                            "source",
                            source,
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                val waitDuration = cause.waitDuration
                log.debug(
                    "workflow execution is waiting on a conditional wait. workflowId={}, waitDuration={}," +
                        " newState={}, prevState={}",
                    workflowInstance.workflowId,
                    waitDuration,
                    inspector.get().getState(),
                    workflowInstance.state,
                )
                return resultBuilder
                    .waitDuration(waitDuration)
                    .newStatus(WorkflowInstance.Status.WAITING)
                    .newState(inspector.get().getState())
                    .build()
            }
            log.warn(
                "workflow execution threw exception. workflowId={}",
                executionContext.workflow.workflowId,
                e,
            )
            if (cause is RetryableError) {
                val retryableError = cause
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "retryableError",
                            "workflowClass",
                            executionContext.workflow.workflowClass.simpleName,
                            "workflowMethod",
                            executionContext.workflow.workflowMethod,
                            "source",
                            source,
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                return resultBuilder
                    .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                    .result(Either.left(retryableError))
                    .retryDelay(retryableError.getNextRetryDelay())
                    .build()
            }
            if (cause is NonRetryableError) {
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "nonRetryableError",
                            "workflowClass",
                            executionContext.workflow.workflowClass.simpleName,
                            "workflowMethod",
                            executionContext.workflow.workflowMethod,
                            "source",
                            source,
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                resultBuilder
                    .result(Either.left(cause))
                    .newStatus(WorkflowInstance.Status.ERROR)
                if (inspector.get() != null) {
                    resultBuilder.newState(inspector.get().getState())
                }
                return resultBuilder.build()
            }
            if (cause is InternalError) {
                metrics
                    .counter(
                        ImmutableMap.of(
                            "result",
                            "tempoInternalError",
                            "workflowClass",
                            executionContext.workflow.workflowClass.simpleName,
                            "workflowMethod",
                            executionContext.workflow.workflowMethod,
                            "source",
                            source,
                        ),
                        METRIC_COMPONENT_NAME,
                        "workflowExecutions",
                    )
                    .inc()
                throw cause
            }
            metrics
                .counter(
                    ImmutableMap.of(
                        "result",
                        "unexpectedError",
                        "error",
                        cause.javaClass.simpleName,
                        "workflowClass",
                        executionContext.workflow.workflowClass.simpleName,
                        "workflowMethod",
                        executionContext.workflow.workflowMethod,
                        "source",
                        source,
                    ),
                    METRIC_COMPONENT_NAME,
                    "workflowExecutions",
                )
                .inc()
            throw e as RuntimeException
        }

        open fun getWorkflowInstance(
            workflowClass: Class<out Workflow>,
            workflowId: String
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

        @Suppress("TooGenericExceptionCaught")
        private fun invokeMethod(
            workflowMethod: Method,
            workflow: Workflow,
            input: Any?
        ): Any? {
            try {
                try {
                    // Kotlin suspend functions have an extra Continuation parameter appended to their
                    // bytecode signature. Use SuspendSupport.invokeSuspendFunctionAsync to invoke them
                    // without blocking — returns a CompletableFuture that completes when the coroutine
                    // finishes. The caller (executeWorkflowMethod) chains on this future via
                    // thenCompose.
                    //
                    // Capture context snapshot before invoking so that ThreadLocal-based context
                    // (request context, tracing spans) is restored when the coroutine's Continuation
                    // completes on a potentially different thread.
                    if (SuspendSupport.isSuspendFunction(workflowMethod)) {
                        var snapshot: ContextSnapshot
                        try {
                            snapshot = contextPropagator.capture()
                        } catch (e: Throwable) {
                            log.warn(
                                "ContextPropagator.capture() failed, context propagation disabled for this" +
                                    " workflow method",
                                e,
                            )
                            snapshot = ContextSnapshot.NOOP
                        }
                        val userParamCount = SuspendSupport.getUserParameterCount(workflowMethod)
                        if (userParamCount == 0) {
                            return SuspendSupport.invokeSuspendFunctionAsync(
                                workflowMethod,
                                workflow,
                                snapshot,
                            )
                        } else {
                            return SuspendSupport.invokeSuspendFunctionAsync(
                                workflowMethod,
                                workflow,
                                snapshot,
                                input,
                            )
                        }
                    }
                    val args: Array<Any?> =
                        if (workflowMethod.parameterCount == 0) arrayOf() else arrayOf(input)
                    return workflowMethod.invoke(workflow, *args)
                } catch (e: InvocationTargetException) {
                    // The underlying method threw an exception. Bubble up the exception and let the
                    // error be handled upstream.
                    throw e.cause!!
                } catch (e: IllegalAccessException) {
                    // The underlying method is not accessible
                    throw NonRetryableError(
                        String.format("workflow method %s is not accessible", workflowMethod),
                        e,
                    )
                } catch (e: IllegalArgumentException) {
                    // The underlying method was passed an invalid argument, typically the argument
                    // doesn't match
                    // the method signature etc.
                    throw NonRetryableError(
                        String.format(
                            "workflow method %s was passed an invalid argument %s",
                            workflowMethod,
                            input,
                        ),
                        e,
                    )
                }
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
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
            if (e is RejectedExecutionException) {
                // This typically happens when the workflow code schedules a future at the unfortunate
                // timing
                // when the app is shutting down and the executors are no longer taking new tasks. This
                // should
                // be treated as a retryable error.
                return RetryableError(
                    "unable to schedule task execution in thread pool",
                    ApplicationError.fromException(e),
                    Duration.ZERO,
                )
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

        // Public `val` properties so both Kotlin property access (.result/.newState/.isResultIsAsync —
        // used by the immutable suspend tests) AND the JVM getters (getResult()/getNewState()/
        // isResultIsAsync()/...) are available. The all-args ctor is package-private in Java -> `internal`
        // here; the builder is the public construction path. All fields except the boolean are nullable
        // (the Java package-private ctor performed no null validation).
        class ExecutionResult
            internal constructor(
                /**
                 * The result of the workflow execution. In case of an error, Either.Left will contain the
                 * exception. In case of success, Either.Right will contain the result of the workflow
                 * execution.
                 *
                 * Having an Either.Right means the workflow has completed and a result is available. On the
                 * other hand, having an Either.Left with an error doesn't necessarily mean that the workflow
                 * has failed, it could be a retryable error.
                 */
                // Nullable: the WAITING builder path leaves `result` unset (null in the original Java) and
                // it is never read on that path. Stays nullable so SkipperEngine's `result.result` null-check
                // and the Java consumers' getResult()!=null guards keep their exact semantics.
                val result: Either<SkipperError, Any?>?,
                /** Indicates if the result of the workflow execution is an asynchronous result. */
                // Property named `isResultIsAsync` -> Kotlin keeps the `is` prefix for the JVM getter
                // (isResultIsAsync()), matching the original Java getter, and the tests' .isResultIsAsync.
                val isResultIsAsync: Boolean,
                /**
                 * The new state of the workflow instance after the execution of the workflow method. This
                 * state will be persisted in the workflow store. A null value means the state has NOT
                 * changed (WorkflowUpdateRequest.computeWorkflowInstanceAfterUpdate skips the state write
                 * on null) — distinct from an empty map, so this MUST remain nullable to preserve behavior.
                 */
                val newState: Map<String, Any?>?,
                /**
                 * The new status of the workflow instance after the execution of the workflow method. In
                 * case of empty value, the status has not changed.
                 */
                val newStatus: WorkflowInstance.Status?,
                /**
                 * When workflow execution hits an unsatisfied conditional wait that has not been expired,
                 * this will hold the wait timeout duration, which is the duration before the wait expires.
                 */
                val waitDuration: Duration?,
                /**
                 * The duration to wait before the workflow should be replayed. This will be set in case of
                 * retryable errors.
                 */
                val retryDelay: Duration?,
            ) {
                /**
                 * Gets the result of the workflow execution in the format that the caller expects.
                 *
                 * @return The result of the workflow execution wrapped in a CompletableFuture if necessary.
                 */
                val wrappedSuccessfulResult: Any?
                    get() {
                        if (result!!.isLeft) {
                            throw IllegalStateException("result is not a success result")
                        }
                        return if (isResultIsAsync) {
                            CompletableFuture.completedFuture(result.get())
                        } else {
                            result.get()
                        }
                    }

                class ExecutionResultBuilder internal constructor() {
                    private var result: Either<SkipperError, Any?>? = null
                    private var resultIsAsync = false
                    private var newState: Map<String, Any?>? = null
                    private var newStatus: WorkflowInstance.Status? = null
                    private var waitDuration: Duration? = null
                    private var retryDelay: Duration? = null

                    fun result(result: Either<SkipperError, Any?>?): ExecutionResultBuilder {
                        this.result = result
                        return this
                    }

                    fun resultIsAsync(resultIsAsync: Boolean): ExecutionResultBuilder {
                        this.resultIsAsync = resultIsAsync
                        return this
                    }

                    fun newState(newState: Map<String, Any?>?): ExecutionResultBuilder {
                        this.newState = newState
                        return this
                    }

                    fun newStatus(newStatus: WorkflowInstance.Status?): ExecutionResultBuilder {
                        this.newStatus = newStatus
                        return this
                    }

                    fun waitDuration(waitDuration: Duration?): ExecutionResultBuilder {
                        this.waitDuration = waitDuration
                        return this
                    }

                    fun retryDelay(retryDelay: Duration?): ExecutionResultBuilder {
                        this.retryDelay = retryDelay
                        return this
                    }

                    fun build(): ExecutionResult =
                        ExecutionResult(
                            this.result,
                            this.resultIsAsync,
                            this.newState,
                            this.newStatus,
                            this.waitDuration,
                            this.retryDelay,
                        )

                    override fun toString(): String =
                        "WorkflowExecutor.ExecutionResult.ExecutionResultBuilder(result=" +
                            this.result +
                            ", resultIsAsync=" +
                            this.resultIsAsync +
                            ", newState=" +
                            this.newState +
                            ", newStatus=" +
                            this.newStatus +
                            ", waitDuration=" +
                            this.waitDuration +
                            ", retryDelay=" +
                            this.retryDelay +
                            ")"
                }

                fun toBuilder(): ExecutionResultBuilder =
                    ExecutionResultBuilder()
                        .result(this.result)
                        .resultIsAsync(this.isResultIsAsync)
                        .newState(this.newState)
                        .newStatus(this.newStatus)
                        .waitDuration(this.waitDuration)
                        .retryDelay(this.retryDelay)

                override fun equals(o: Any?): Boolean {
                    if (o === this) return true
                    if (o !is ExecutionResult) return false
                    val other = o
                    if (this.isResultIsAsync != other.isResultIsAsync) return false
                    if (this.result != other.result) return false
                    if (this.newState != other.newState) return false
                    if (this.newStatus != other.newStatus) return false
                    if (this.waitDuration != other.waitDuration) return false
                    if (this.retryDelay != other.retryDelay) return false
                    return true
                }

                override fun hashCode(): Int {
                    val prime = 59
                    var result = 1
                    result = result * prime + (if (this.isResultIsAsync) 79 else 97)
                    result = result * prime + (this.result?.hashCode() ?: 43)
                    result = result * prime + (this.newState?.hashCode() ?: 43)
                    result = result * prime + (this.newStatus?.hashCode() ?: 43)
                    result = result * prime + (this.waitDuration?.hashCode() ?: 43)
                    result = result * prime + (this.retryDelay?.hashCode() ?: 43)
                    return result
                }

                override fun toString(): String =
                    "WorkflowExecutor.ExecutionResult(result=" +
                        this.result +
                        ", resultIsAsync=" +
                        this.isResultIsAsync +
                        ", newState=" +
                        this.newState +
                        ", newStatus=" +
                        this.newStatus +
                        ", waitDuration=" +
                        this.waitDuration +
                        ", retryDelay=" +
                        this.retryDelay +
                        ")"

                companion object {
                    @JvmStatic
                    fun builder(): ExecutionResultBuilder = ExecutionResultBuilder()
                }
            }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(WorkflowExecutor::class.java)

            private const val METRIC_COMPONENT_NAME = "workflowExecutor"
        }
    }
