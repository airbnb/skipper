package com.airbnb.skipper

import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.ActionExecutor
import com.airbnb.skipper.internal.ExceptionClassifier
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.util.allDeclaredFields
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javax.inject.Inject
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KCallable
import kotlin.reflect.cast
import kotlin.reflect.jvm.jvmErasure
import org.slf4j.LoggerFactory

/**
 * Abstract base class for defining workflows in a Skipper-based system. This class serves as a foundation for creating
 * specific workflow implementations. Each derived workflow class must implement methods annotated with
 * [WorkflowMethod], which denote the logic that can be executed as part of the workflow process.
 *
 * Workflow instances should be created using [IWorkflowFactory] to ensure that method invocations are intercepted
 * and properly forwarded to a workflow executor. This interception allows the system to manage workflow states
 * and transitions effectively, encapsulating the business logic specific to each workflow step.
 *
 * ### Usage Example
 * Below is an example of how to define a workflow that transitions a pending state to an accepted state. The workflow
 * utilizes user-defined actions to perform its tasks.
 *
 * ```kotlin
 * class PendingToAcceptedWorkflow : Workflow() {
 *     private val userActions = actions<UserActions>()
 *
 *     @WorkflowMethod
 *     fun execute(state: State): State {
 *         return userActions.loadGuest(state)
 *     }
 * }
 *
 * // Creating and executing the workflow with an initial state using an instance of IWorkflowFactory.
 * val workflow: IWorkflowFactory = ...
 * val initialState = State(...)
 * val finalState = workflow<PendingToAcceptedWorkflow>().execute(initialState)
 * ```
 *
 * @see WorkflowMethod Annotation used to mark methods that define executable steps within the workflow.
 */
@SkipperOpen
abstract class Workflow {
    private val log = LoggerFactory.getLogger(Workflow::class.java)

    lateinit var id: String
    var parentWorkflowId: String? = null

    /**
     * The execution context for the workflow, providing access to the current workflow execution state and context.
     * This will be set by the workflow executor before invoking any workflow methods.
     */
    lateinit var executionContext: ExecutionContext
    @Inject lateinit var actionExecutor: ActionExecutor
    @Inject lateinit var skipperEngine: SkipperEngine
    @Inject lateinit var contextPropagator: ContextPropagator

    /**
     * A map that holds the completable future objects for the given workflow methods. This
     * is useful when the caller wants to resume the workflow execution after a signal is received
     * and want to wait until the workflow completes.
     */
    val resumeExecutionMap: MutableMap<String, CompletableFuture<Any?>> = mutableMapOf()

    companion object {
        const val DEFAULT_WAIT_DURATION_DAYS: Long = 365
    }

    private val checkpointHelpers = actions(CheckpointHelpers::class.java)
    private val suspendCheckpointHelpers = actions(SuspendCheckpointHelpers::class.java)
    private val versionGateHelpers = actions(VersionGateHelpers::class.java)

    /**
     * Creates a proxy instance of a specified actions class, enabling the execution of each method within the class as
     * a Skipper action. This method is ideal for use within Kotlin due to its inline and reified type parameter features
     * which simplify the creation of proxy instances without needing explicit class references. Use this method when
     * calling from Java.
     *
     * Each method in the actions class should either be annotated with [Execute] to denote regular action methods, or
     * with [Compensate] for compensation actions tied to workflow rollbacks. Methods not annotated with these will be
     * executed normally, without any special handling by the workflow system.
     *
     * ### Requirements:
     * - The action class must be open (non-final) with a zero-argument constructor to allow for proxy creation.
     *
     * ### Usage:
     * This function simplifies the process of creating action proxies within a workflow, automatically handling method
     * invocations based on the presence of specific annotations. This is particularly useful for defining clear,
     * maintainable business logic within workflows.
     *
     * ```kotlin
     * class MyWorkflow : Workflow() {
     *     private val userActions = actions<UserActions>()
     *
     *     @WorkflowMethod
     *     suspend fun run() {
     *         userActions.performAction()
     *     }
     * }
     *
     * // UserActions class should be defined as open and include methods annotated appropriately.
     * open class UserActions : Actions() {
     *     @Execute
     *     suspend fun performAction() {
     *         println("Performing action")
     *     }
     * }
     * ```
     *
     * @param actionsClass action class to create proxy for
     * @param T The type of the actions class, inferred from the call site in Kotlin.
     * @return A proxy instance of the provided class T, equipped to handle method calls according to their annotations.
     */
    protected final fun <T : Actions> actions(actionsClass: Class<T>): T {
        val proxyFactory = ProxyFactory()
        proxyFactory.superclass = actionsClass
        val handler =
            MethodHandler { self: Any, thisMethod: Method, originalMethod: Method, args: Array<Any?> ->
                // call original method if it's a non-action method, e.g. method not annotated with @Execute or @Compensate
                val executeAnnotation = thisMethod.getAnnotation(Execute::class.java)
                val compensateAnnotation = thisMethod.getAnnotation(Compensate::class.java)
                if (executeAnnotation == null && compensateAnnotation == null) {
                    return@MethodHandler try {
                        originalMethod.invoke(self, *args)
                    } catch (e: InvocationTargetException) {
                        throw e.cause ?: e
                    }
                }

                // For Kotlin suspend action methods, the Javassist proxy receives the
                // Continuation as the last element of args. Strip it before storing in
                // ExecuteActionRequest so that checkpoint storage, arg counting, and method
                // invocation all see only the user-visible arguments.
                val isSuspend = SuspendSupport.isSuspendFunction(thisMethod)
                val filteredArgs = SuspendSupport.filterContinuationArgs(args)

                // BETWEEN-ACTION context propagation: capture thread-local context BEFORE
                // executing the action. The action may modify or clear ThreadLocals during
                // execution. We restore the captured state when the coroutine resumes on a
                // (potentially different) thread, so subsequent action calls see the correct
                // context. This is separate from the WITHIN-ACTION propagation in
                // ActionExecutor, which handles dispatcher switches inside the action body.
                // Defensive: fall back to NOOP if capture fails.
                val snapshot = try {
                    contextPropagator.capture()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    log.warn("ContextPropagator.capture() failed, context propagation disabled for this action", e)
                    ContextSnapshot.NOOP
                }

                // Consume any invocation-site checkpoint name set by Actions.named(...) on this
                // proxy. The name is one-shot: read it, clear it, and pass it through to the
                // ExecuteActionRequest so CheckpointTag can use name-based matching.
                val actionsInstance = self as Actions
                val checkpointName = actionsInstance.pendingCheckpointName
                actionsInstance.pendingCheckpointName = null

                val result =
                    actionExecutor.executeAction(
                        ActionExecutor.ExecuteActionRequest(
                            actionsInstance,
                            originalMethod, // proxyMethod
                            thisMethod, // The overridden method, the original method
                            filteredArgs,
                            executionContext,
                            getRetryStrategy(self, executeAnnotation, thisMethod.declaringClass),
                            getExceptionClassifier(self, executeAnnotation, thisMethod.declaringClass),
                            false, // This is not a compensation flow
                            checkpointName
                        )
                    )

                // For suspend action methods, the result from ActionExecutor is a
                // CompletableFuture (from invokeSuspendFunctionAsync). Subscribe to it and
                // return COROUTINE_SUSPENDED so the calling workflow coroutine suspends
                // without blocking a thread. When the future completes, resume the caller's
                // Continuation on the executor.
                //
                // CRITICAL: Use whenCompleteAsync, NOT whenComplete. If the action completed
                // synchronously, the future is already done. whenComplete would call
                // continuation.resumeWith() BEFORE this handler returns COROUTINE_SUSPENDED,
                // re-entering the Kotlin state machine recursively. whenCompleteAsync
                // dispatches to an executor, guaranteeing resumeWith() runs AFTER
                // COROUTINE_SUSPENDED has been processed by the state machine.
                if (isSuspend && result is CompletableFuture<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<Any?>
                    result.whenCompleteAsync({ value, error ->
                        snapshot.runWithContext { resumeContinuation(continuation, value, error) }
                    }, executionContext.getExecutorService())
                    return@MethodHandler COROUTINE_SUSPENDED
                }

                // For non-suspend methods returning CompletableFuture (existing async pattern).
                // Skipper does NOT propagate context across user-managed CompletableFuture
                // chains returned from non-suspend actions. The thenApply here performs only
                // a type cast; activating a scope would close before any caller-chained
                // callbacks execute.
                if (result is CompletableFuture<*>) {
                    // returnType is a Kotlin annotation element (KClass<*>); use .javaObjectType for the
                    // BOXED java.lang.Class the pre-port Java annotation exposed. .java would give the
                    // primitive class for e.g. Int::class -> int.class, and int.class.cast(boxedInteger)
                    // throws — the workflow result value is always a boxed object.
                    val returnType = executeAnnotation?.returnType ?: compensateAnnotation?.returnType
                    val transformedResult =
                        result.thenApply { value ->
                            returnType?.javaObjectType?.cast(value)
                        }
                    return@MethodHandler transformedResult
                }
                result
            }
        val result = actionsClass.cast(
            proxyFactory.create(
                arrayOf(),
                arrayOf(),
                handler
            )
        )
        return result
    }

    private fun getRetryStrategy(
        actions: Actions,
        executeAnnotation: Execute?,
        declaringClass: Class<*>
    ): RetryStrategy {
        if (executeAnnotation != null && executeAnnotation.retryStrategy.isNotEmpty()) {
            val retryStrategyField = declaringClass.allDeclaredFields(Actions::class.java).find { it.name == executeAnnotation.retryStrategy }
            if (retryStrategyField != null) {
                // Action validation at workflow creation time ensures that the retryStrategy field is of type RetryStrategy
                // Update field visibility so that we can safely get its value in case its private
                retryStrategyField.isAccessible = true
                return retryStrategyField.get(actions) as RetryStrategy
            }
        }
        return actions.retryStrategyProvider()
    }

    private fun getExceptionClassifier(
        actions: Actions,
        executeAnnotation: Execute?,
        declaringClass: Class<*>
    ): ExceptionClassifier? {
        if (executeAnnotation != null && executeAnnotation.exceptionClassifier.isNotEmpty()) {
            val exceptionClassifierField = declaringClass.allDeclaredFields(
                Actions::class.java
            ).find { it.name == executeAnnotation.exceptionClassifier }
            if (exceptionClassifierField != null) {
                // Action validation at workflow creation time ensures that the exceptionClassifier field is of type ExceptionClassifier
                // Update field visibility so that we can safely get its value in case its private
                exceptionClassifierField.isAccessible = true
                return exceptionClassifierField.get(actions) as ExceptionClassifier
            }
        }
        return null // Return null to use the global exception classifier
    }

    /**
     * Creates a proxy instance of a specified actions class, enabling the execution of each method within the class as
     * a Skipper action. This method is ideal for use within Kotlin due to its inline and reified type parameter features
     * which simplify the creation of proxy instances without needing explicit class references. Use this method when
     * calling from Kotlin.
     *
     * @param T The type of the actions class, inferred from the call site in Kotlin.
     * @return A proxy instance of the provided class T, equipped to handle method calls according to their annotations.
     */
    protected final inline fun <reified T : Actions> actions(): T {
        return actions(T::class.java)
    }

    /**
     * Suspends the current workflow execution until the specified condition is met or a timeout occurs.
     *
     * ## Requirements
     * - The condition block should be a function of a `@StateParam` parameter.
     * - The surrounding workflow method must return a `CompletableFuture`. This won't be enforced at runtime at this
     * time so make sure to follow this requirement.
     *
     * ## Usage
     * ```kotlin
     * class MyWorkflow : Workflow() {
     *    @StateParam var shouldProceed = false
     *
     *    @WorkflowMethod
     *    fun greetWithWait(name: String): CompletableFuture<String> {
     *      val conditionWasMet = waitUntil({ shouldProceed }, Duration.ofHours(1))
     *      if (!conditionWasMet || !shouldProceed) {
     *          return CompletableFuture.completedFuture("Cannot proceed!")
     *      }
     *      return CompletableFuture.completedFuture("Hello, $name!")
     *   }
     *
     *   @SignalMethod
     *   fun updateShouldProceed(value: Boolean) {
     *      shouldProceed = value
     *   }
     * }
     * ```
     *
     * @param condition The condition to evaluate. The workflow will wait until this condition is true.
     * @param timeout The maximum duration to wait for the condition to be met.
     * @return `true` if the condition was satisfied before the timeout, false otherwise.
     */
    protected final fun waitUntil(
        condition: () -> Boolean,
        timeout: Duration
    ): Boolean {
        val timerId = executionContext.timerCounter.incrementAndGet().toString()
        return waitUntil(condition, timeout, timerId)
    }

    /**
     * Suspends the current workflow execution until the specified condition is met or a timeout occurs.
     *
     * ## Requirements
     * - The condition block should be a function of a `@StateParam` parameter.
     * - The surrounding workflow method must return a `CompletableFuture`. This won't be enforced at runtime at this
     * time so make sure to follow this requirement.
     *
     * ## Usage
     * ```kotlin
     * class MyWorkflow : Workflow() {
     *    @StateParam var shouldProceed = false
     *
     *    @WorkflowMethod
     *    fun greetWithWait(name: String): CompletableFuture<String> {
     *      val conditionWasMet = waitUntil({ shouldProceed }, Duration.ofHours(1), "wait-until-should-proceed")
     *      if (!conditionWasMet || !shouldProceed) {
     *          return CompletableFuture.completedFuture("Cannot proceed!")
     *      }
     *      return CompletableFuture.completedFuture("Hello, $name!")
     *   }
     *
     *   @SignalMethod
     *   fun updateShouldProceed(value: Boolean) {
     *      shouldProceed = value
     *   }
     * }
     * ```
     *
     * @param condition The condition to evaluate. The workflow will wait until this condition is true.
     * @param timeout The maximum duration to wait for the condition to be met.
     * @param timerId A unique identifier for the timer associated with this wait. This ID is used to track the timer's state across workflow
     * executions. Unlike `waitUntil` without a `timerId`, this method does not auto-generate a timer ID, so the caller must ensure its uniqueness.
     * @return `true` if the condition was satisfied before the timeout, false otherwise.
     */
    protected final fun waitUntil(
        condition: () -> Boolean,
        timeout: Duration,
        timerId: String
    ): Boolean {
        val timer = executionContext.timers.firstOrNull { it.id == timerId }
        if (timer != null && timer.status.isTerminal) {
            // The timer has already expired or has been cancelled. Cancelled means the condition was met
            // and the timer got cancelled before expiration.
            return timer.status == Timer.Status.CANCELLED
        }
        if (!condition()) {
            // The condition has not yet been met, but there is still time left.
            if (timer == null) {
                // Only create a timer if this is the first time and thus there is still no timer for this wait.
                val newTimer = Timer(
                    workflowId = executionContext.workflow.workflowId,
                    id = timerId,
                    duration = timeout,
                    expiresAt = executionContext.clock.instant().plus(timeout)
                )
                executionContext.addDirtyTimer(newTimer)
            }
            throw WaitSignal(timeout)
        }
        // The condition was met in time.
        if (timer != null) {
            val expiredTimer = timer.copy(status = Timer.Status.CANCELLED)
            executionContext.addDirtyTimer(expiredTimer)
        } else {
            // The condition is true, but there was no timer created. This means that the condition was met
            // even before the waitUntil was evaluated. We need to create a cancelled timer to mark this.
            val cancelledTimer = Timer(
                workflowId = executionContext.workflow.workflowId,
                id = timerId,
                duration = timeout,
                expiresAt = executionContext.clock.instant().plus(timeout),
                status = Timer.Status.CANCELLED
            )
            executionContext.addDirtyTimer(cancelledTimer)
        }
        return true
    }

    /**
     * Suspends the current workflow execution until the specified condition is met.
     * This method will wait indefinitely.
     *
     * ## Requirements
     * - The condition block should be a function of a `@StateParam` parameter.
     * - The surrounding workflow method must return a `CompletableFuture`. This won't be enforced at runtime at this
     * time so make sure to follow this requirement.
     *
     * ## Usage
     * ```kotlin
     * class MyWorkflow : Workflow() {
     *    @StateParam var shouldProceed = false
     *
     *    @WorkflowMethod
     *    fun greetWithWait(name: String): CompletableFuture<String> {
     *      waitUntil{ shouldProceed }
     *      return CompletableFuture.completedFuture("Hello, $name!")
     *   }
     *
     *   @SignalMethod
     *   fun updateShouldProceed(value: Boolean) {
     *      shouldProceed = value
     *   }
     * }
     * ```
     *
     * @param condition The condition to evaluate. The workflow will wait until this condition is true.
     */
    protected final fun waitUntil(condition: () -> Boolean): Boolean {
        return waitUntil(condition, Duration.ofDays(DEFAULT_WAIT_DURATION_DAYS))
    }

    /**
     * Suspends the current workflow execution for the specified duration.
     */
    protected final fun sleep(duration: Duration) {
        waitUntil({ false }, duration)
    }

    /**
     * Returns the result handle for the given workflow method if available.
     *
     * This is particularly useful when we are resuming execution after a signal is received and want to wait until the
     * workflow completes.
     *
     * ## Requirements
     * - The workflow method must have been previously resumed in the same process by calling a signal method.
     *
     * ## Usage
     * ```kotlin
     * // Workflow declaration:
     * class MyWorkflow : Workflow() {
     *    @StateParam var shouldProceed = false
     *
     *    @WorkflowMethod
     *    fun greetWithWait(name: String): CompletableFuture<String> {
     *      waitUntil { shouldProceed }
     *      return CompletableFuture.completedFuture("Hello, $name!")
     *   }
     *
     *   @SignalMethod
     *   fun updateShouldProceed(value: Boolean) {
     *      shouldProceed = value
     *   }
     * }
     *
     * // Client call site code.
     * // This assumes that the workflow has been previously started and the workflowId is known.
     * val workflow = factory<MyWorkflow>(workflowId)
     * workflow.updateShouldProceed(true) // This will trigger the workflow to resume execution.
     * val result = workflow.getResultFor(MyWorkflow::greetWithWait).get() // This will wait until the workflow completes.
     * ```
     *
     * @param function The workflow method for which the result handle is needed.
     */
    final fun <R> getResultFor(function: KCallable<R?>): CompletableFuture<R> {
        if (resumeExecutionMap.containsKey(function.name)) {
            val result = resumeExecutionMap[function.name]!!
            val isCompletableFuture = function.returnType.jvmErasure == CompletableFuture::class ||
                function.returnType.javaClass.isAssignableFrom(CompletableFuture::class.java)
            if (!isCompletableFuture) {
                return result.get() as CompletableFuture<R>
            }
            return result.thenCompose { value ->
                value as CompletableFuture<R>
            }
        }
        // TODO: if there is no live result, trigger a new workflow execution run.
        throw IllegalStateException("there is no result handle available for workflow method ${function.name}")
    }

    final fun <R> getResultFor(function: Method): CompletableFuture<R> {
        if (resumeExecutionMap.containsKey(function.name)) {
            val result = resumeExecutionMap[function.name]!!
            if (!function.returnType.isAssignableFrom(CompletableFuture::class.java)) {
                return result.get() as CompletableFuture<R>
            }
            return result.thenCompose { value -> value as CompletableFuture<R> }
        }
        // TODO: if there is no live result, trigger a new workflow execution run.
        throw IllegalStateException("there is no result handle available for workflow method ${function.name}")
    }

    /**
     * Returns a simplified view of the current workflow instance.
     */
    final fun getWorkflowInstanceView(): WorkflowInstanceView {
        return skipperEngine.getWorkflow(id)
            .map { it.toView({ skipperEngine.getActionCheckpoints(id).map { it.toView() } }) }
            .getOrElseThrow { IllegalArgumentException("workflow not found") }
    }

    /**
     * Cancels the current workflow instance with the specified reason.
     *
     * <p>This WILL NOT interrupt or kill a running workflow if it is currently actively being executed in-process. On the other hand, if the
     * workflow is on a waiting state (e.g. waiting for a signal or a timer to expire), OR if it is stuck on a retry cycle, this will cancel the
     * workflow and mark it as ERROR, effectively preventing it from making any further progress.
     *
     * @param reason The reason for cancelling the workflow.
     * @return A view of the workflow after being cancelled.
     */
    final fun cancelWorkflowInstance(reason: String): WorkflowInstanceView {
        return skipperEngine.cancelWorkflow(id, reason).toView({ skipperEngine.getActionCheckpoints(id).map { it.toView() } })
    }

    /**
     * Run the given lambda once and checkpoint it. The lambda will not be re-executed upon replay
     * of the workflow method.
     *
     * This is useful when you want to checkpoint some portion of the workflow code to prevent it
     * from being re-executed upon replay of the workflow method.
     *
     * For suspend workflow methods that need to call suspend functions inside the checkpoint
     * block, use [checkpointSuspend] instead.
     *
     * @param lambda The lambda to run and checkpoint.
     */
    protected final fun checkpoint(lambda: Runnable) {
        checkpointHelpers.checkpointRunnable(lambda)
    }

    /**
     * Named variant of [checkpoint]. Uses the checkpoint name as the identity instead of the
     * positional iteration, so adding, removing, or reordering checkpoint blocks in the workflow
     * method does not break replay for in-flight instances.
     *
     * Each named checkpoint must be unique within a workflow method; duplicates fail fast.
     *
     * @param name Unique checkpoint name within this workflow method.
     * @param lambda The lambda to run and checkpoint.
     */
    protected final fun checkpoint(
        name: String,
        lambda: Runnable
    ) {
        checkpointHelpers.named(name).checkpointRunnable(lambda)
    }

    /**
     * Run the given suspend lambda once and checkpoint it. The lambda will not be re-executed
     * upon replay of the workflow method.
     *
     * This is the suspend-compatible variant of [checkpoint]. The lambda may contain suspend
     * calls (e.g. coroutine primitives) or plain non-suspend code — both work.
     *
     * This method is named `checkpointSuspend` rather than `checkpoint` because Kotlin's
     * overload resolution cannot disambiguate between `() -> Unit` and `suspend () -> Unit`
     * parameter types — adding a `suspend fun checkpoint(suspend () -> Unit)` overload causes
     * overload resolution ambiguity for all existing `checkpoint { }` callers, even those in
     * non-suspend contexts.
     *
     * @param block The suspend lambda to run and checkpoint.
     */
    protected final suspend fun checkpointSuspend(block: suspend () -> Unit) {
        suspendCheckpointHelpers.checkpointSuspend(block)
    }

    /**
     * Named variant of [checkpointSuspend]. Uses the checkpoint name as the identity instead of
     * the positional iteration, so adding, removing, or reordering suspend checkpoint blocks in
     * the workflow method does not break replay for in-flight instances.
     *
     * Each named checkpoint must be unique within a workflow method; duplicates fail fast.
     *
     * @param name Unique checkpoint name within this workflow method.
     * @param block The suspend lambda to run and checkpoint.
     */
    protected final suspend fun checkpointSuspend(
        name: String,
        block: suspend () -> Unit
    ) {
        suspendCheckpointHelpers.named(name).checkpointSuspend(block)
    }

    /**
     * Returns the active version for [changeId], enabling version-gated branching of workflow
     * logic across deploys.
     *
     * Implemented as a named checkpoint with name `"version:{changeId}"`:
     * - On **first execution**, returns [maxVersion] and stores it as the cached result of the
     *   named checkpoint so the same value is returned on replay even if [maxVersion] is bumped
     *   in a future deploy.
     * - On **replay**, returns the persisted version associated with the named checkpoint.
     *
     * Migration lifecycle (see workflow_versioning_design.md):
     * 1. Introduce the gate at `maxVersion = 1` so in-flight instances persist a baseline.
     * 2. Bump `maxVersion` and add the new branch — new instances take the new path; in-flight
     *    instances replay with the persisted value and take the legacy path.
     * 3. After all old-version instances drain, raise `minVersion` and prune the old branch.
     * 4. Eventually remove the gate entirely; the orphaned named checkpoint is harmlessly
     *    ignored on replay.
     *
     * @param changeId stable identifier for this migration; persisted as the checkpoint name
     * @param minVersion the lowest version the current code is willing to handle (>= 1)
     * @param maxVersion the latest version known to the current code (>= [minVersion])
     * @return the active version: [maxVersion] on first execution, or the persisted value on replay
     * @throws IllegalArgumentException if `1 <= minVersion <= maxVersion` is violated
     */
    protected final fun version(
        changeId: String,
        minVersion: Int,
        maxVersion: Int
    ): Int {
        require(minVersion >= 1 && minVersion <= maxVersion) {
            "version($changeId): minVersion ($minVersion) must be >= 1 and <= maxVersion ($maxVersion)"
        }
        return versionGateHelpers
            .named("version:$changeId")
            .getVersion(changeId, minVersion, maxVersion)
    }

    /** Resumes a coroutine continuation, unwrapping [CompletionException] if present. */
    private fun resumeContinuation(
        continuation: Continuation<Any?>,
        value: Any?,
        error: Throwable?
    ) {
        if (error != null) {
            val cause = if (error is CompletionException) error.cause ?: error else error
            continuation.resumeWith(Result.failure(cause))
        } else {
            continuation.resumeWith(Result.success(value))
        }
    }
}
