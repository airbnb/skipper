package com.airbnb.skipper.internal

import com.airbnb.skipper.CancelledWorkflow
import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.ResultUnavailable
import com.airbnb.skipper.SkipperAnnotationNames.SKIPPER_MAIN_THREAD_POOL
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.TerminalWorkflowError
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.util.ExtraRequestData
import com.google.common.base.Throwables
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Clock
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

/**
 * The SkipperEngine is the main entry point for the workflow engine. It is responsible for starting
 * new workflows, executing actions, and managing the state of the workflows.
 */
// `open` (not final) restores the Java baseline's non-final class semantics: the original
// `public class SkipperEngine` was subclassable, and the existing immutable
// WorkflowExecutionTaskHandlerTest Mockito-mocks this class. Kotlin classes are final by default;
// without `open` Mockito cannot create the mock subclass. The public instance methods are likewise
// `open` for the same reason.
open class SkipperEngine
    @Inject
    constructor(
        private val workflowStore: WorkflowStore,
        private val scheduler: SchedulerExecutionQueue,
        private val workflowExecutor: WorkflowExecutor,
        private val persistentScheduler: Scheduler,
        @param:Named(SKIPPER_MAIN_THREAD_POOL)
        @field:Named(SKIPPER_MAIN_THREAD_POOL)
        private val executorService: ExecutorService,
        @param:Named(UTC_CLOCK) @field:Named(UTC_CLOCK) private val now: Clock,
        private val featureGate: FeatureGate,
        private val eventPublisher: EventPublisher,
        private val metrics: Metrics,
        private val callbackHandlerInjector: SkipperInjector,
        private val inFlightActions: InFlightActions,
    ) {
        /**
         * Creates and starts the execution of a new workflow instance.
         *
         * <p>The actual execution will be performed asynchronously. Depending on the specific workflow
         * implementation, the actual execution might take a long time to complete, and while Skipper will
         * ensure eventual termination of the workflow in spite of failures, the caller should be prepared
         * to handle scenarios where their process crashes or is killed before the future is completed,
         * therefore requiring other mechanisms to be notified about the completion of the workflow.
         *
         * @param request The request to run or start a new workflow
         * @return A future that will be completed when the workflow is successfully started. Note that
         *     this does NOT mean the workflow has completed. The caller should inspect {@link
         *     WorkflowInstance#getResult()}, which is the future that will actually contain the result
         *     which will be completed when the workflow has reached a terminal state.
         */
        open fun startWorkflow(request: RunRequest): WorkflowInstance {
            log.info("starting workflow. request={}", request)
            val tags = java.util.HashMap<String, String>()
            try {
                val workflowCreationRequest =
                    WorkflowCreationRequest.builder()
                        .workflowId(request.workflowId)
                        .workflowClass(request.workflowClass)
                        .input(request.input)
                        .requestContext(request.requestContext)
                        .extraRequestData(request.extraRequestData)
                        .workflowMethod(request.workflowMethod)
                        .callbackHandler(request.callbackHandler)
                        .parentWorkflowId(request.parentWorkflowId)
                        .timeoutTime(
                            if (request.executionTimeout != null) {
                                now.instant().plus(request.executionTimeout)
                            } else {
                                null
                            }
                        )
                        .build()
                var workflowInstance: WorkflowInstance
                try {
                    workflowInstance = workflowStore.createWorkflow(workflowCreationRequest)
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.WORKFLOW_CREATED, request.workflowId)
                    )
                } catch (e: EntityAlreadyExists) {
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.WORKFLOW_DUPLICATE_ID, request.workflowId)
                    )
                    if (request.failOnDuplicate) {
                        tags[METRIC_OUTCOME] = "duplicateError"
                        throw IllegalArgumentException("workflow with id %s already exists")
                    }
                    workflowInstance =
                        workflowStore
                            .getWorkflow(request.workflowId)
                            .getOrElseThrow {
                                IllegalStateException(
                                    String.format(
                                        "unable to get workflow instance for id: %s",
                                        request.workflowId
                                    )
                                )
                            }
                    // If the workflow already exists, and its already completed, we will just return the
                    // existing workflow instance with its result, no need to schedule execution.
                    val noopExistingWorkflow =
                        featureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP)
                    var shouldByPassExecution =
                        workflowInstance.status.isTerminal() ||
                            workflowInstance.status.isCompensationInProgress() ||
                            noopExistingWorkflow
                    if (workflowInstance.status == WorkflowInstance.Status.RETRIES_EXHAUSTED) {
                        // If the workflow is waiting for a manual retry, then force the execution regardless.
                        shouldByPassExecution = false
                    }
                    if (shouldByPassExecution) {
                        tags[METRIC_OUTCOME] = "existingNoOp"
                        return workflowInstance
                    }
                }
                val inProcessExecution = !request.isRunAsync
                scheduleExecution(workflowInstance, inProcessExecution)
                if (request.executionTimeout != null) {
                    val timeoutTaskId = String.format("%s:timeout", workflowInstance.workflowId)
                    val executionTimeout = now.instant().plus(request.executionTimeout)
                    scheduler.schedule(
                        ScheduleRequest.builder<String>()
                            .id(timeoutTaskId)
                            .dedupToken(workflowInstance.workflowId)
                            .payload(workflowInstance.workflowId)
                            .type(Task.Type.EXECUTION_TIMEOUT)
                            .runAfter(executionTimeout)
                            .build()
                    )
                    log.info(
                        "scheduled execution timeout task for workflowId={} on {}",
                        workflowInstance.workflowId,
                        executionTimeout
                    )
                }
                tags[METRIC_OUTCOME] = "created"
                if (request.isRunAsync) {
                    workflowInstance
                        .result
                        .completeExceptionally(
                            ResultUnavailable(
                                "this future cannot be used to wait on the workflow result because it is" +
                                    " running async"
                            )
                        )
                }
                return workflowInstance
            } catch (e: Exception) {
                tags[METRIC_OUTCOME] = "error"
                throw e
            } finally {
                metrics.counter(tags, METRICS_COMPONENT, "startWorkflow").inc()
            }
        }

        open fun scheduleExecution(
            workflowInstance: WorkflowInstance,
            inMemoryExecution: Boolean
        ) {
            scheduler.schedule(
                ScheduleRequest.builder<WorkflowInstance>()
                    .id(workflowInstance.workflowId)
                    .dedupToken(workflowInstance.workflowId)
                    .payload(workflowInstance)
                    .type(Task.Type.WORKFLOW)
                    .honorActiveLeaseWhenOverwriting(true)
                    .bumpVersionWhenHonoringLease(
                        featureGate.isEnabled(FeatureGate.Keys.BUMP_TASK_VERSION_ON_HONORED_LEASE)
                    )
                    .inMemoryExecutionEnabled(inMemoryExecution)
                    .build()
            )
        }

        /**
         * Clones a workflow instance.
         *
         * <p>This will create a new workflow instance with the same input and start it. The original
         * workflow instance will not be affected and will be completely unrelated to the new workflow
         * instance being created.
         *
         * <p>This method won't wait until the workflow execution completes, it will retur
         *
         * @param workflowIdToClone The id of the workflow to clone
         * @param newWorkflowId The id of the new workflow to create
         * @param requestContext The request context to use for the new workflow
         */
        open fun cloneWorkflowInstance(
            workflowIdToClone: String,
            newWorkflowId: String,
            requestContext: Any?
        ) {
            val workflowInstance =
                workflowStore
                    .getWorkflow(workflowIdToClone)
                    .getOrElseThrow { IllegalArgumentException("workflow with id %s does not exist") }
            val runRequest =
                RunRequest.builder()
                    .workflowId(newWorkflowId)
                    .workflowClass(workflowInstance.workflowClass)
                    .workflowMethod(workflowInstance.workflowMethod)
                    .input(workflowInstance.input)
                    .requestContext(requestContext)
                    .extraRequestData(workflowInstance.extraRequestData)
                    .callbackHandler(workflowInstance.callbackHandler)
                    .failOnDuplicate(true)
                    .build()
            startWorkflow(runRequest)
        }

        /**
         * Gets the workflow instance for a given workflow id.
         *
         * @param workflowId The id of the workflow to get
         * @return An {@link Option} with the workflow instance details or an empty option if there is no
         *     workflow with the given id.
         */
        open fun getWorkflow(workflowId: String): Option<WorkflowInstance> {
            return workflowStore.getWorkflow(workflowId)
        }

        /**
         * Invokes a query method on a workflow instance. If the workflow instance does not exist (if the
         * workflow has never run), throws an {@link IllegalStateException} unless the request allows
         * queries on non-existent workflows, in which case the query method will be invoked on the
         * initial state of the workflow.
         *
         * <p>Unlike workflow method and signal method execution, executing a query method happens in the
         * same thread as the caller. Since there is no mutation involved, there is no need to schedule
         * the execution.
         *
         * @param request The request to run a query method on a workflow
         * @return The result of the query method execution. Skipper will not perform any processing of the
         *     result and just get bach the raw response from the query method.
         */
        open fun invokeQueryMethod(request: RunRequest): Any? {
            val workflowInstance = workflowStore.getWorkflow(request.workflowId)
            if (workflowInstance.isEmpty && !request.allowQueryOnNonExistentWorkflow) {
                throw IllegalStateException(
                    "workflow method needs to be executed before query methods can be called"
                )
            }
            return workflowExecutor.executeQueryMethod(
                request.workflowClass,
                request.workflowId,
                workflowInstance.map { it.state }.getOrElse(HashMap.empty()),
                request.workflowMethod,
                request.input
            )
        }

        /**
         * Sends a signal to a running workflow instance.
         *
         * @param request The request to send a signal to a running workflow
         * @return A future that will be completed with the updated workflow instance after the signal has
         *     been processed and the workflow gets to a terminal state.
         */
        open fun sendSignal(request: RunRequest): SendSignalResult {
            return runSignal(request, null)
        }

        /**
         * Replays a previously persisted signal.
         *
         * <p>Loads the persisted signal identified by {@code signalId}, reconstructs the original signal
         * invocation (method name, input and request context) and re-executes it against the live
         * workflow instance. The existing persisted-signal record is reused — its status is updated to
         * {@link PersistedSignal.Status#EXECUTED} on success or {@link PersistedSignal.Status#FAILED} on
         * error — so replaying does not create duplicate audit records.
         *
         * <p>Replaying re-applies the signal's side effects. Signal idempotency remains the
         * responsibility of the workflow author.
         *
         * @param workflowId The id of the workflow the signal targets.
         * @param signalId The id of the persisted signal to replay.
         * @return The result of the replayed signal execution.
         */
        open fun replaySignal(
            workflowId: String,
            signalId: Long
        ): SendSignalResult {
            val workflowInstance =
                workflowStore
                    .getWorkflow(workflowId)
                    .getOrElseThrow {
                        IllegalArgumentException(
                            String.format("workflow with id %s does not exist", workflowId)
                        )
                    }
            val signal =
                workflowStore
                    .getPersistedSignal(workflowId, signalId)
                    .getOrElseThrow {
                        IllegalArgumentException(
                            String.format(
                                "persisted signal %d for workflow %s does not exist",
                                signalId,
                                workflowId
                            )
                        )
                    }
            val request =
                RunRequest.builder()
                    .workflowId(workflowId)
                    .workflowClass(workflowInstance.workflowClass)
                    .workflowMethod(signal.signalMethod)
                    .input(signal.input)
                    .requestContext(signal.requestContext)
                    .extraRequestData(ExtraRequestData())
                    .build()
            log.info("replaying persisted signal. workflowId={}, signalId={}", workflowId, signalId)
            return runSignal(request, signalId)
        }

        /**
         * Shared signal execution path used by both {@link #sendSignal} and {@link #replaySignal}.
         *
         * @param request The signal run request.
         * @param replaySignalId The id of an already-persisted signal record to reuse. This is non-null
         *     only on the replay path ({@link #replaySignal}); reusing the existing record means a replay
         *     updates that row's status (to EXECUTED/FAILED) rather than inserting a new one. It is not a
         *     mere "should persist" flag — it carries the row identity. On the normal send path it is
         *     null, and whether a new record is persisted is decided from the signal method's annotation.
         */
        private fun runSignal(
            request: RunRequest,
            replaySignalId: Long?
        ): SendSignalResult {
            val tags = java.util.HashMap<String, String>()
            var persistedSignalId: Long? = replaySignalId
            try {
                var workflowInstance =
                    workflowStore
                        .getWorkflow(request.workflowId)
                        .getOrElseThrow {
                            IllegalArgumentException(
                                String.format(
                                    "workflow with id %s does not exist",
                                    request.workflowId
                                )
                            )
                        }
                eventPublisher.publishEvent(
                    Event.create(
                        Event.Type.SIGNAL_RECEIVED,
                        request.workflowId,
                        String.format(
                            "method: %s, input: %s",
                            request.workflowMethod,
                            request.input
                        )
                    )
                )
                if (
                    workflowInstance.status.isTerminal() ||
                    workflowInstance.status.isCompensationInProgress()
                ) {
                    eventPublisher.publishEvent(
                        Event.create(
                            Event.Type.SIGNAL_FAILED,
                            request.workflowId,
                            "workflow is already in a terminal state"
                        )
                    )
                    throw TerminalWorkflowError(
                        String.format(
                            "signals can only be sent non terminal workflows. workflowId=%s, result=%s",
                            workflowInstance.workflowId,
                            workflowInstance.flattenResult()
                        )
                    )
                }
                // If the signal carries a non-null request context, override the workflow's stored one
                // for this invocation. Skipper itself treats the payload as opaque; host middleware will
                // see the override when it next runs.
                if (request.requestContext != null) {
                    // TODO: we should make this contract explicit by making requestContext an optional, then
                    // if it is present, we always override the context, otherwise we just use the existing one.
                    // If the request context passed in on the request is not empty, then we'll use that
                    // and override the existing request context.
                    log.info(
                        "overriding workflow request context for workflowId={}. Previous request context={}." +
                            " New request context={}",
                        workflowInstance.workflowId,
                        workflowInstance.requestContext,
                        request.requestContext
                    )
                    workflowInstance =
                        workflowInstance.toBuilder().requestContext(request.requestContext).build()
                }
                // When the target signal method opts into persistence, durably record the signal before
                // executing it so it can be replayed if execution fails or the process crashes. On replay,
                // the existing record is reused instead of persisting a new one.
                if (persistedSignalId == null) {
                    persistedSignalId = maybePersistSignal(workflowInstance, request)
                }
                val checkpoints = workflowStore.getActionCheckpoints(workflowInstance.workflowId)
                // TODO: validate that the request.input is of the correct type for the signal method.
                // This should already be guaranteed since the request will come through the proxy which would
                // guarantee compile time type safety.
                val executionContext =
                    ExecutionContext.builder()
                        .workflow(workflowInstance)
                        .clock(now)
                        .actionCheckpoints(checkpoints)
                        .executorService(executorService)
                        .build()
                val result =
                    workflowExecutor
                        .executeSignalMethod(
                            workflowInstance,
                            executorService,
                            executionContext,
                            request.workflowMethod,
                            request.input
                        )
                        .join()
                // A signal handler that throws is captured by executeSignalMethod as an error result (an
                // Either.left) rather than a thrown exception, so it does not reach the catch block below.
                // When the signal opted into persistence, record it as FAILED and DO NOT advance the workflow
                // state, so the workflow stays exactly as it was and the signal can be replayed cleanly. The
                // error is still surfaced to the caller (the proxy unwraps the left and rethrows).
                val signalResult: Either<SkipperError, Any?>? = result.result
                if (persistedSignalId != null && signalResult != null && signalResult.isLeft) {
                    log.warn(
                        "persisted signal execution failed; marking signal {} as FAILED for workflowId={}",
                        persistedSignalId,
                        workflowInstance.workflowId
                    )
                    workflowStore.updateSignalStatus(
                        request.workflowId,
                        persistedSignalId,
                        PersistedSignal.Status.FAILED,
                        Throwables.getStackTraceAsString(signalResult.left)
                    )
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.SIGNAL_FAILED, request.workflowId)
                    )
                    return SendSignalResult(workflowInstance, signalResult)
                }
                log.info(
                    "signal executed successfully. workflowId={}, result={}",
                    workflowInstance.workflowId,
                    result
                )
                val updateRequest =
                    WorkflowUpdateRequest.builder()
                        .newState(result.newState)
                        .workflowInstance(workflowInstance)
                        .newStatus(WorkflowInstance.Status.RUNNING)
                        .clearResult(true)
                        .build()
                // When the signal was persisted, advance the workflow state and mark the signal EXECUTED
                // atomically so the two cannot diverge.
                val updatedWorkflowInstance: WorkflowInstance
                if (persistedSignalId != null) {
                    updatedWorkflowInstance =
                        workflowStore.updateWorkflowAndMarkSignal(
                            updateRequest,
                            persistedSignalId,
                            PersistedSignal.Status.EXECUTED
                        )
                            ._1
                } else {
                    updatedWorkflowInstance = workflowStore.updateWorkflow(updateRequest)
                }
                log.info(
                    "workflow instance with id={} updated after signal execution. newVersion={}",
                    updatedWorkflowInstance.workflowId,
                    updatedWorkflowInstance
                )
                val inMemoryExecutionEnabled =
                    !featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)
                log.info(
                    "scheduling workflow execution after signal execution. " +
                        "workflowId={}, inMemoryExecutionEnabled={}",
                    updatedWorkflowInstance.workflowId,
                    inMemoryExecutionEnabled
                )
                scheduler.schedule(
                    // We don't want this execution to happen immediately in the same process, instead, we
                    // will force this to go through the scheduler and be executed as soon as possible.
                    ScheduleRequest.builder<WorkflowInstance>()
                        .id(workflowInstance.workflowId)
                        .dedupToken(workflowInstance.workflowId)
                        .payload(updatedWorkflowInstance)
                        .type(Task.Type.WORKFLOW)
                        .inMemoryExecutionEnabled(inMemoryExecutionEnabled)
                        .honorActiveLeaseWhenOverwriting(true)
                        .bumpVersionWhenHonoringLease(
                            featureGate.isEnabled(FeatureGate.Keys.BUMP_TASK_VERSION_ON_HONORED_LEASE)
                        )
                        .build()
                )
                eventPublisher.publishEvent(
                    Event.create(Event.Type.SIGNAL_COMPLETED, request.workflowId)
                )
                // result.result is null when the signal handler issued a conditional wait (the WAITING
                // result carries no Either; see WorkflowExecutor.handleExecutionError). Java returned a
                // SendSignalResult with a null signalResponse on that path, so pass it through unchanged
                // instead of asserting non-null: fire-and-forget callers (e.g. the state-machine admin
                // endpoint) ignore the response and must not 500. The synchronous WorkflowFactory proxy
                // still dereferences it (NPE iff null, matching Java).
                return SendSignalResult(updatedWorkflowInstance, result.result)
            } catch (e: Exception) {
                tags[METRIC_OUTCOME] = "error"
                // Record the failure on the persisted signal (if any) so operators can find and replay it.
                // Best-effort: even if this update fails, the record remains in its prior (PENDING) state
                // and is still replayable.
                if (persistedSignalId != null) {
                    try {
                        workflowStore.updateSignalStatus(
                            request.workflowId,
                            persistedSignalId,
                            PersistedSignal.Status.FAILED,
                            Throwables.getStackTraceAsString(e)
                        )
                    } catch (markFailure: Exception) {
                        log.warn(
                            "failed to mark persisted signal {} as FAILED for workflow {}; it remains replayable",
                            persistedSignalId,
                            request.workflowId,
                            markFailure
                        )
                    }
                }
                throw e
            } finally {
                metrics.counter(tags, METRICS_COMPONENT, "sendSignal").inc()
            }
        }

        /**
         * Persists the signal described by {@code request} when its target signal method opts into
         * persistence via {@code @SignalMethod(persist = true)} and persistence is not globally disabled.
         *
         * @return The id of the persisted record, or null if the signal was not persisted.
         */
        private fun maybePersistSignal(
            workflowInstance: WorkflowInstance,
            request: RunRequest
        ): Long? {
            if (featureGate.isEnabled(FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE)) {
                return null
            }
            if (
                !WorkflowInspector.isSignalMethodPersistable(
                    workflowInstance.workflowClass,
                    request.workflowMethod
                )
            ) {
                return null
            }
            // Persisting the signal is best-effort: it must never fail the signal itself. If the write
            // fails we log, emit a counter, and proceed without persistence — the signal then executes as a
            // normal (non-persisted) signal.
            return try {
                val persisted =
                    workflowStore.persistSignal(
                        PersistedSignal(
                            workflowInstance.workflowId,
                            request.workflowMethod,
                            PersistedSignal.Status.PENDING,
                            request.input,
                            // Capture the effective request context (after any override) so a replay can
                            // reconstruct the original invocation faithfully.
                            workflowInstance.requestContext
                        )
                    )
                log.info(
                    "persisted signal before execution. workflowId={}, signalId={}, method={}",
                    workflowInstance.workflowId,
                    persisted.id,
                    request.workflowMethod
                )
                persisted.id
            } catch (e: Exception) {
                log.error(
                    "failed to persist signal before execution; proceeding without persistence." +
                        " workflowId={}, method={}",
                    workflowInstance.workflowId,
                    request.workflowMethod,
                    e
                )
                metrics.counter(METRICS_COMPONENT, "persistSignalError").inc()
                null
            }
        }

        open fun getActionCheckpoints(workflowId: String): List<ActionCheckpoint> {
            return workflowStore.getActionCheckpoints(workflowId)
        }

        /**
         * Cancels a running workflow instance.
         *
         * <p>This will set the workflow status to CANCELLED and remove it from the scheduler - no further
         * actions will be scheduled or executed. The callback handler's onCancelled method will be
         * invoked if one is registered.
         *
         * @param workflowId The id of the workflow to cancel
         * @param reason The reason for cancelling the workflow
         * @return The updated workflow instance after the workflow has been cancelled
         */
        @Suppress("TooGenericExceptionCaught")
        open fun cancelWorkflow(
            workflowId: String,
            reason: String
        ): WorkflowInstance {
            val instance =
                workflowStore.getWorkflow(workflowId).getOrElseThrow {
                    IllegalArgumentException("Workflow not found")
                }
            if (instance.status.isTerminal() || instance.status.isCompensationInProgress()) {
                throw IllegalArgumentException("Workflow is already in a terminal state")
            }
            val request =
                WorkflowUpdateRequest.builder()
                    .workflowInstance(instance)
                    .newStatus(WorkflowInstance.Status.CANCELLED)
                    .result(Either.left(CancelledWorkflow(reason)))
                    .resultIsAsync(false)
                    .build()
            val updatedInstance = workflowStore.updateWorkflow(request)
            val task = persistentScheduler.getTask<Any>(instance.workflowId)
            if (task.isDefined) {
                persistentScheduler.remove(task.get())
            }
            // Inert without the boundary check: that is what settles the interrupted execution
            // without a stale-version write, and what stops an action starting after this scan.
            if (featureGate.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_INTERRUPT) &&
                featureGate.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS)
            ) {
                val interrupted = inFlightActions.interrupt(workflowId)
                metrics
                    .counter(mapOf("interrupted" to interrupted.toString()), METRICS_COMPONENT, "cancelInterrupts")
                    .inc()
                if (interrupted) {
                    log.info("interrupted the action running for cancelled workflowId={}", workflowId)
                }
            }
            try {
                if (instance.callbackHandler != null) {
                    val handler = callbackHandlerInjector.getInstance(instance.callbackHandler)
                    handler.onCancelled(
                        updatedInstance.toView {
                            workflowStore.getActionCheckpoints(workflowId).map { it.toView() }
                        },
                        reason
                    )
                }
            } catch (e: Throwable) {
                log.error(
                    "error while notifying callback handler of workflow cancellation for workflowId={}",
                    workflowId,
                    e
                )
            }
            eventPublisher.publishEvent(Event.create(Event.Type.WORKFLOW_CANCELLED, workflowId))
            return updatedInstance
        }

        /**
         * Force-deletes a workflow instance. If the workflow is not in a terminal state, it will be
         * cancelled first. Then the workflow and all its data will be permanently removed from the
         * database. This operation is irreversible.
         *
         * @param workflowId The id of the workflow to delete
         */
        open fun forceDeleteWorkflow(workflowId: String) {
            val instance =
                workflowStore.getWorkflow(workflowId).getOrElseThrow {
                    IllegalArgumentException("Workflow not found: $workflowId")
                }
            // Cancel if not terminal
            if (!instance.status.isTerminal()) {
                cancelWorkflow(workflowId, "Force-deleted by admin")
            }
            // Remove from scheduler (if still present)
            val task = persistentScheduler.getTask<Any>(workflowId)
            if (task.isDefined) {
                persistentScheduler.remove(task.get())
            }
            // Delete from database
            workflowStore.deleteWorkflow(workflowId)
            log.info("Force-deleted workflow instance: {}", workflowId)
            eventPublisher.publishEvent(Event.create(Event.Type.WORKFLOW_DELETED, workflowId))
        }

        /**
         * Schedules a compensation task for the given workflow instance.
         *
         * <p>This method creates and schedules a compensation task that will execute compensation actions
         * for successfully completed compensable actions in the workflow. The compensation task will be
         * executed immediately and will process actions in reverse order of their execution.
         *
         * @param workflowInstance the workflow instance that requires compensation
         */
        open fun scheduleCompensationTask(workflowInstance: WorkflowInstance) {
            val taskId = workflowInstance.workflowId + "-compensation"
            val compensationRequest = // Send this to the persistent scheduler
                ScheduleRequest.builder<String>()
                    .id(taskId)
                    .dedupToken(taskId)
                    .payload(workflowInstance.workflowId)
                    .type(Task.Type.COMPENSATION)
                    .runAfter(now.instant())
                    .inMemoryExecutionEnabled(false)
                    .build()
            val task: Task<*> = persistentScheduler.schedule(compensationRequest)
            log.info(
                "scheduled compensation task {} for workflowId={}",
                task,
                workflowInstance.workflowId
            )
        }

        // Externally referenced (Java `SkipperEngineTest` reads via getters; Kotlin `WorkflowFactory`
        // reads the raw `.workflowInstance`/`.signalResponse` fields). To reproduce the Java type's
        // simultaneous `public final` FIELDS *and* getters, the properties are `@JvmField` (public
        // field, no synthetic getter) AND explicit getWorkflowInstance()/getSignalResponse() are
        // declared. `signalResponse` is nullable: a signal handler that issues a conditional wait
        // produces a WAITING result carrying no Either (see WorkflowExecutor.handleExecutionError), and
        // the original Java stored that null into the field. WorkflowFactory.kt still dereferences it
        // on the synchronous proxy path (NPE iff null, matching Java); fire-and-forget callers ignore it.
        class SendSignalResult(
            @JvmField val workflowInstance: WorkflowInstance,
            @JvmField val signalResponse: Either<SkipperError, Any?>?,
        ) {
            fun getWorkflowInstance(): WorkflowInstance {
                return this.workflowInstance
            }

            fun getSignalResponse(): Either<SkipperError, Any?>? {
                return this.signalResponse
            }

            override fun equals(o: Any?): Boolean {
                if (o === this) return true
                if (o !is SendSignalResult) return false
                val other: SendSignalResult = o
                if (this.workflowInstance != other.workflowInstance) return false
                if (this.signalResponse != other.signalResponse) return false
                return true
            }

            override fun hashCode(): Int {
                val prime = 59
                var result = 1
                result = result * prime + this.workflowInstance.hashCode()
                result = result * prime + (this.signalResponse?.hashCode() ?: 0)
                return result
            }

            override fun toString(): String {
                return "SkipperEngine.SendSignalResult(workflowInstance=" +
                    this.workflowInstance +
                    ", signalResponse=" +
                    this.signalResponse +
                    ")"
            }
        }

        companion object {
            private val log = LoggerFactory.getLogger(SkipperEngine::class.java)

            private const val METRICS_COMPONENT =
                "tempoEngine" // Legacy name kept for metric continuity
            private const val METRIC_OUTCOME = "outcome"
        }
    }
