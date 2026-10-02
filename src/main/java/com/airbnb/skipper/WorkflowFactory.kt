package com.airbnb.skipper

import com.airbnb.skipper.SkipperAnnotationNames.RESULT_POLLING_SLEEP_DURATION
import com.airbnb.skipper.SkipperAnnotationNames.RESULT_POLLING_TIME_LIMIT
import com.airbnb.skipper.SkipperAnnotationNames.SKIPPER_MAIN_THREAD_POOL
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.util.ExtraRequestData
import com.airbnb.skipper.util.SkipperInternalDeps
import com.airbnb.skipper.util.getAllFields
import com.airbnb.skipper.util.injectWorkflowMembers
import io.opentracing.Tracer
import io.vavr.control.Option
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeoutException
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.cast

/**
 * A concrete implementation of [IWorkflowFactory] that generates proxy instances of [Workflow] classes.
 * This factory leverages dynamic proxy generation to intercept method calls to workflow instances,
 * allowing for customized handling of methods annotated with [WorkflowMethod].
 *
 * The proxy creation is handled using [ProxyFactory], which dynamically subclasses the specified [Workflow] class.
 * Method invocations on the proxy are managed by a [MethodHandler] which decides whether to execute methods
 * directly or process them as part of the Skipper workflow system based on the presence of the [WorkflowMethod]
 * annotation.
 *
 * ### Implementation Details:
 * - **Non-Workflow Methods**: Methods not annotated with [WorkflowMethod] are invoked directly using the original
 *   method reference, effectively bypassing the workflow system.
 * - **Workflow Methods**: Methods annotated with [WorkflowMethod] are intended to be executed as part of a Skipper
 *   workflow.
 *
 * ### Example Usage:
 * To create a proxy instance of a specific workflow implementation, you can do the following:
 *
 * ```kotlin
 * val factory = WorkflowFactory()
 * factory<MyWorkflow>().someWorkflowMethod()
 * ```
 */
open class WorkflowFactory
    @Inject
    constructor(
        val workflowOptions: WorkflowOptions,
        @Named(UTC_CLOCK) val clock: Clock,
        val injector: SkipperInjector,
        private val skipperEngine: SkipperEngine,
        private val middleware: RawRequestContextMiddleware,
        @Named(RESULT_POLLING_TIME_LIMIT) val resultPollingTimeLimit: Duration,
        @Named(RESULT_POLLING_SLEEP_DURATION) val resultPollingSleepDuration: Duration,
        private val workflowValidator: WorkflowValidator,
        private val actionValidator: ActionValidator,
        private val tracer: Tracer,
        @Named(SKIPPER_MAIN_THREAD_POOL) private val executor: ExecutorService,
        val internalDeps: SkipperInternalDeps
    ) : IWorkflowFactory {
        override operator fun <T : Workflow> invoke(
            workflowClass: Class<T>,
            workflowId: String,
            requestContext: Any?,
            callbackHandler: Class<out WorkflowCallbackHandler>?,
            workflowOptions: WorkflowOptions?,
            runAsync: Boolean,
            parentWorkflowId: String?
        ): T = invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, runAsync, parentWorkflowId, false)

        override operator fun <T : Workflow> invoke(
            workflowClass: Class<T>,
            workflowId: String,
            requestContext: Any?,
            callbackHandler: Class<out WorkflowCallbackHandler>?,
            workflowOptions: WorkflowOptions?,
            runAsync: Boolean,
            parentWorkflowId: String?,
            detached: Boolean
        ): T {
            // Merge global workflow options with per-invocation options
            val effectiveOptions = this.workflowOptions.mergeWith(workflowOptions)

            val proxyFactory = ProxyFactory()
            proxyFactory.superclass = workflowClass
            val handler =
                MethodHandler { self: Any, thisMethod: Method, originalMethod: Method, args: Array<Any?> ->
                    // call original method if it's a non-workflow method
                    val workflowMethodAnnotation = thisMethod.getAnnotation(WorkflowMethod::class.java)
                    val signalMethodAnnotation = thisMethod.getAnnotation(SignalMethod::class.java)
                    val queryMethodAnnotation = thisMethod.getAnnotation(QueryMethod::class.java)
                    if (workflowMethodAnnotation == null && signalMethodAnnotation == null && queryMethodAnnotation == null) {
                        return@MethodHandler originalMethod.invoke(self, *args)
                    }
                    // For Kotlin suspend workflow methods, the Javassist proxy receives the
                    // Continuation as the last element of args. The user input is always args[0].
                    val isSuspend = SuspendSupport.isSuspendFunction(thisMethod)
                    val methodArg = when {
                        args.isEmpty() -> null
                        isSuspend && args.size >= 2 -> args[0] // user input; Continuation is last
                        isSuspend -> null // suspend fun with no user args
                        else -> args[0]
                    }
                    validateWorkflowInvocation(workflowClass, thisMethod, methodArg, runAsync, detached)
                    // Host integrations (via a custom request-context middleware) optionally
                    // transform the caller-supplied context — for example deriving a missing user
                    // ID from an auth token. Default impl is identity.
                    //
                    // Only run rawOnCreate for workflow-method invocations (i.e. workflow
                    // creation). Signals and queries target an already-existing workflow whose
                    // request context was persisted at creation time; running rawOnCreate
                    // again would overwrite that persisted context with whatever the
                    // signal/query caller happened to pass (often null), erasing the original
                    // identity.
                    val inputRequestContext =
                        if (workflowMethodAnnotation != null) {
                            middleware.rawOnCreate(workflowId, requestContext)
                        } else {
                            requestContext
                        }

                    val extraRequestData = ExtraRequestData()
                    extraRequestData.storeSpanContext(tracer)
                    // At workflow creation only (same guard as rawOnCreate above), let the host
                    // persist any per-workflow metadata it derives from the options into the
                    // durable extraRequestData, so the execution-time hooks can read it after the
                    // workflow resumes. Signals/queries target an already-created workflow and must
                    // not re-derive it.
                    if (workflowMethodAnnotation != null) {
                        middleware.rawStoreWorkflowCreationMetadata(effectiveOptions, extraRequestData)
                    }

                    val request =
                        RunRequest(
                            workflowId,
                            workflowClass,
                            thisMethod.name,
                            methodArg,
                            inputRequestContext,
                            extraRequestData,
                            callbackHandler,
                            effectiveOptions.executionTimeout,
                            false,
                            effectiveOptions.allowQueryOnNonExistentWorkflow,
                            effectiveOptions.createExistingWorkflowIsNoop,
                            runAsync,
                            parentWorkflowId,
                        )
                    var result: Any?

                    if (signalMethodAnnotation != null) {
                        val signalResult = executeSignalWithRetries(request)
                        // The synchronous proxy path always expects a signal response. signalResponse is
                        // null only when the handler issued a conditional wait; dereferencing here matches
                        // the earlier Java version, which read the field unguarded and NPEd on that same path.
                        val signalResponse = signalResult.signalResponse!!
                        if (signalResponse.isLeft) {
                            throw signalResponse.left
                        }
                        // Signal result contains a response which a handle of the workflow instance result and can be
                        // waited on, so we will cache that object so that the client can get a hold of it later if needed.
                        (self as Workflow).resumeExecutionMap[signalResult.workflowInstance.workflowMethod] = signalResult.workflowInstance.result
                        return@MethodHandler signalResponse.get()
                    }
                    if (queryMethodAnnotation != null) {
                        val queryResult = skipperEngine.invokeQueryMethod(request)
                        return@MethodHandler queryResult
                    }
                    val workflowInstance =
                        skipperEngine.startWorkflow(request)
                    result = workflowInstance.result

                    // Fire-and-forget: the caller opted out of the result, so return now that
                    // startWorkflow has persisted the instance and handed it to the scheduler — the
                    // point at which "this workflow will run" becomes guaranteed. Returning here
                    // deliberately skips the result chains below, which are what attach the polling
                    // loop in handleTransientError, so a detached call never holds a thread waiting on
                    // a result nobody reads. validateWorkflowInvocation has already rejected
                    // result-bearing methods, so there is nothing to hand back.
                    if (detached) {
                        return@MethodHandler when {
                            // Any value other than COROUTINE_SUSPENDED tells Kotlin's state machine the
                            // suspend function completed without suspending, so the caller resumes
                            // immediately. Unit is the value a Unit-returning suspend function yields.
                            isSuspend -> Unit
                            // Hand CompletableFuture callers a future that refuses to be awaited rather
                            // than one that is already complete. Completing it normally would falsely
                            // signal that the workflow had finished, since a detached call returns before
                            // execution starts. This mirrors how runAsync marks its result unavailable
                            // (see SkipperEngine.startWorkflow). Callers that ignore the future — the
                            // whole point of detaching — are unaffected.
                            CompletableFuture::class.java.isAssignableFrom(thisMethod.returnType) ->
                                // completeExceptionally rather than CompletableFuture.failedFuture:
                                // this module targets Java 8 (its build pins the Java 8 language level).
                                CompletableFuture<Any?>().apply {
                                    completeExceptionally(
                                        ResultUnavailable(
                                            "this future cannot be used to wait on the workflow result " +
                                                "because the invocation was detached; register a " +
                                                "WorkflowCallbackHandler to observe the outcome"
                                        )
                                    )
                                }
                            // Unit/void-returning methods hand back no awaitable handle to misuse.
                            else -> null
                        }
                    }

                    // For suspend @WorkflowMethod, return COROUTINE_SUSPENDED and resume the
                    // caller's Continuation asynchronously. This makes suspend workflow calls
                    // genuinely non-blocking for the caller: the caller's coroutine suspends and
                    // its thread is freed while the workflow executes (or while the proxy polls for
                    // the result when runAsync=true). Signals and queries have already returned above,
                    // so this path is only reached for @WorkflowMethod.
                    if (isSuspend) {
                        @Suppress("UNCHECKED_CAST")
                        val callerContinuation = args.last() as Continuation<Any?>
                        @Suppress("UNCHECKED_CAST")
                        val resultFuture = result as CompletableFuture<Any?>
                        resultFuture.handleAsync({ success, e ->
                            // Pass handleWaitSignal=true: when the workflow suspends (WaitSignal),
                            // poll until the workflow reaches a terminal state and return the final
                            // result. This makes suspend @WorkflowMethod genuinely non-blocking —
                            // the caller's coroutine stays suspended through waitUntil boundaries.
                            if (e != null) handleTransientError(e, workflowId, handleWaitSignal = true) else success
                        }, executor).whenComplete { value, error ->
                            if (error != null) {
                                val cause = if (error is CompletionException) error.cause ?: error else error
                                callerContinuation.resumeWith(Result.failure(cause))
                            } else {
                                callerContinuation.resumeWith(Result.success(value))
                            }
                        }
                        return@MethodHandler COROUTINE_SUSPENDED
                    }

                    // For Java sync methods (non-CF return type), block until the workflow completes.
                    val shouldBlock = !thisMethod.returnType.isAssignableFrom(CompletableFuture::class.java)
                    if (shouldBlock) {
                        try {
                            result = result.handleAsync({ success, e ->
                                if (e != null) {
                                    handleTransientError(e, workflowId)
                                } else {
                                    success
                                }
                            }, executor).get()
                        } catch (e: ExecutionException) {
                            throw e.cause ?: e
                        }
                        return@MethodHandler result
                    }

                    // If the return type of the method is a completable future, then we will return a completable future.
                    // In this case, workflowInstance.result is a CompletableFuture<CompletableFuture<Any>>, so we need to
                    // flatten it to CompletableFuture<Any> and cast the result to the specified returnType of the workflowMethod.
                    // The client can then decide whether it wants to block on the result or not.
                    result = result.thenCompose { value -> value as CompletableFuture<*> }
                    // cast to CompletableFuture and use thenApply to transform the result
                    val transformedResult =
                        result.thenApply { value ->
                            // cast the value to the specified returnType of the workflowMethod.
                            // returnType is a Kotlin annotation element (KClass<*>); use .javaObjectType
                            // for the BOXED java.lang.Class the pre-port Java annotation exposed. .java
                            // would give the primitive class for e.g. Int::class -> int.class, and
                            // int.class.cast(boxedInteger) throws — the workflow result is always boxed.
                            value?.let { workflowMethodAnnotation.returnType.javaObjectType.cast(it) }
                        }
                    return@MethodHandler transformedResult.handleAsync({ success, e ->
                        if (e != null) {
                            var r = handleTransientError(e, workflowId)
                            if (r is CompletableFuture<*>) {
                                r.join()
                            } else {
                                r
                            }
                        } else {
                            success
                        }
                    }, executor)
                }
            try {
                val workflow =
                    workflowClass.cast(
                        proxyFactory.create(
                            arrayOf(),
                            arrayOf(),
                            handler
                        )
                    )
                workflow.id = workflowId
                workflow.parentWorkflowId = parentWorkflowId
                injector.injectWorkflowMembers(workflow, internalDeps)
                validateWorkflowCreation(workflowClass)
                return workflow
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        private fun executeSignalWithRetries(request: RunRequest): SkipperEngine.SendSignalResult {
            val retries = 3
            var lastSignalError: Throwable? = null
            for (i in 0..retries) {
                try {
                    return skipperEngine.sendSignal(request)
                } catch (e: OptimisticLockingError) {
                    // If we encounter an optimistic locking error, we will retry the signal operation
                    lastSignalError = e
                }
            }
            throw lastSignalError ?: IllegalStateException("Failed to send signal after $retries retries")
        }

        override fun cloneAsNew(
            workflowIdToClone: String,
            newWorkflowId: String,
            requestContext: Any?
        ) {
            skipperEngine.cloneWorkflowInstance(workflowIdToClone, newWorkflowId, requestContext)
        }

        private fun validateWorkflowActions(
            clazz: Class<*>,
            errors: MutableList<String>
        ) {
            clazz.getAllFields().filter {
                Actions::class.java.isAssignableFrom(it.type)
            }.forEach {
                validateWorkflowActions(it.type as Class<*>, errors)
                val err = actionValidator.validateCreation(it.type as Class<out Actions>)
                errors.addAll(err)
            }
        }

        private fun <T : Workflow> validateWorkflowCreation(workflowClass: Class<T>) {
            val creationErrors = workflowValidator.validateCreation(workflowClass)
            val actionErrors = mutableListOf<String>()
            validateWorkflowActions(workflowClass, actionErrors)
            val allErrors = creationErrors + actionErrors
            if (allErrors.isNotEmpty()) {
                throw IllegalArgumentException("Workflow creation validation failed: " + allErrors.joinToString(", "))
            }
        }

        private fun <T : Workflow> validateWorkflowInvocation(
            workflowClass: Class<T>,
            thisMethod: Method,
            methodArg: Any?,
            runAsync: Boolean,
            detached: Boolean
        ) {
            val errors = workflowValidator.validateInvocation(workflowClass, thisMethod, methodArg, runAsync, detached)
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("Workflow invocation validation failed: " + errors.joinToString(", "))
            }
        }

        private fun handleTransientError(
            error: Throwable,
            workflowId: String,
            handleWaitSignal: Boolean = false
        ): Any? {
            val cause = if (error is CompletionException) {
                // Unwrap the exception to get the actual cause
                error.cause ?: error
            } else {
                error
            }
            if (cause is TransientError || cause is ResultUnavailable || (handleWaitSignal && cause is WaitSignal)) {
                val timeLimit = clock.instant().plus(resultPollingTimeLimit)
                while (clock.instant().isBefore(timeLimit)) {
                    // This loop runs on the main pool, so an ignored future from a workflow that is
                    // waiting or retrying would otherwise hold scheduler.stop() for the whole polling
                    // window (30 s by default). Once shutdown has begun, give the future up: the
                    // instance itself is persisted and finishes on the next scheduler run.
                    if (executor.isShutdown) {
                        throw TimeoutException(
                            "skipper is shutting down; workflow instance $workflowId continues durably " +
                                "and its result can be read from the instance later",
                        )
                    }
                    val instance: Option<WorkflowInstance> = skipperEngine.getWorkflow(workflowId)
                    check(!instance.isEmpty) { "workflow instance not found" }
                    if (!instance.get().result.isDone) {
                        if (instance.get().status == WorkflowInstance.Status.RETRIES_EXHAUSTED) {
                            val lastError = getLatestError(instance.get())
                            lastError?.let {
                                throw RetriesExhaustedError(it)
                            } ?: throw RetriesExhaustedError("workflow instance $workflowId is waiting for manual retry.")
                        }
                        try {
                            Thread.sleep(resultPollingSleepDuration.toMillis())
                        } catch (e: InterruptedException) {
                            // shutdownNow() interrupts pool threads; stop polling instead of spinning.
                            Thread.currentThread().interrupt()
                            throw TimeoutException("interrupted while waiting for the result of workflow instance $workflowId")
                        }
                        continue
                    }
                    try {
                        return instance.get().result.join()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
                throw TimeoutException("unable to get workflow result within the time limit")
            }
            throw cause
        }

        private fun getLatestError(workflowInstance: WorkflowInstance): Throwable? {
            return workflowInstance.toView {
                skipperEngine.getActionCheckpoints(workflowInstance.workflowId).map(ActionCheckpoint::toView)
            }.getLastTransientError().getOrNull()
        }
    }
