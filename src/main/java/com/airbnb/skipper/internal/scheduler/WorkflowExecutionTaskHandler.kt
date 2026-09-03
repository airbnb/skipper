package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.ExecutionTimeout
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetriesExhaustedError
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperAnnotationNames.UNEXPECTED_ERROR_RETRY_DELAY
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Timer
import com.airbnb.skipper.TransientError
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowCancelledException
import com.airbnb.skipper.WorkflowInstance
import java.util.concurrent.CancellationException
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.api.ActionCheckpointView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.WorkflowExecutor
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.google.common.collect.ImmutableMap
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.function.Supplier
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

/**
 * Handles the processing of a workflow execution request.
 *
 * A workflow execution request is a task that is created when a workflow is scheduled to be
 * executed. This handler is responsible for executing the workflow and handling the results of the
 * execution, including success, failure, and retry logic.
 */
class WorkflowExecutionTaskHandler
    @Inject
    constructor(
        private val workflowExecutor: WorkflowExecutor,
        @Named(UTC_CLOCK) clock: Clock,
        private val workflowStore: WorkflowStore,
        @Named(UNEXPECTED_ERROR_RETRY_DELAY) private val unexpectedErrorRetryDelay: Duration,
        private val callbackHandlerInjector: SkipperInjector,
        private val metrics: Metrics,
        private val scheduler: Scheduler,
        private val eventPublisher: EventPublisher,
        private val skipperEngine: SkipperEngine,
        private val middleware: RawRequestContextMiddleware,
    ) : TaskHandler {
        private val now: Clock = clock

        /**
         * Installs the workflow's own request context around every callback notification this
         * handler fires, the same way the action and signal paths do.
         */
        private val callbackRequestContext =
            CallbackRequestContextBracket(middleware, metrics, METRICS_COMPONENT)

        /**
         * Handle the processing of a workflow execution request.
         *
         * This is the **single point of entry for all workflow executions**. If a workflow is ever
         * executed, succeeded, failed, or retried, it will be through this method, therefore this is
         * the appropriate place to handle all the logic related to workflow execution such as callback
         * notifications, error handling, and retry strategies.
         *
         * @param task The task to be processed.
         * @param executorService The executor service to be used for executing the workflow.
         */
        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            log.debug("handling workflow execution task={}", task)
            val workflowInstance: WorkflowInstance
            if (!task.hasPayload()) {
                // If task has no payload, we must hydrate the workflow instance from storage.
                workflowInstance =
                    workflowStore
                        .getWorkflow(task.id)
                        .getOrElseThrow(
                            Supplier {
                                NonRetryableError(
                                    "workflow instance with id= not found in persistent storage" +
                                        task.id,
                                )
                            },
                        )
            } else {
                val tmpInstance = task.payload as WorkflowInstance
                workflowInstance =
                    if (task.isShouldRefreshPayload) {
                        workflowStore
                            .getWorkflow(tmpInstance.workflowId)
                            .getOrElse(
                                Supplier {
                                    log.warn(
                                        "unable to refresh workflow instance with id={}. Using the " +
                                            "stale instance",
                                        tmpInstance.workflowId,
                                    )
                                    tmpInstance
                                },
                            )
                    } else {
                        tmpInstance
                    }
            }
            eventPublisher.publishEvent(
                Event.create(
                    Event.Type.WORKFLOW_EXECUTION_STARTED,
                    workflowInstance.workflowId,
                    String.format("retries: %s", task.retryCount),
                ),
            )
            if (workflowInstance.isTimeoutEligible(now.instant())) {
                // If the workflow is eligible for a timeout, we will handle it here and return
                // immediately. Only workflows that are in a non-terminal state OR have timed out will
                // be considered for timeout handling.
                // Why do we consider workflow is TIMEOUT state to be eligible for timeout?
                // because those cases are the ones where we attempted to time out the workflow, but we
                // failed to either persist the state or notify the caller.
                if (!workflowInstance.status.isTerminal() ||
                    workflowInstance.status == WorkflowInstance.Status.TIMEOUT
                ) {
                    val timeoutResult = handleWorkflowTimeout(workflowInstance)
                    return CompletableFuture.completedFuture(timeoutResult)
                }
            }
            if (workflowInstance.status == WorkflowInstance.Status.TIMEOUT) {
                // If the workflow is already in a timeout state, we won't attempt to execute it.
                // Based on how the TIMEOUT logic works (see above), if we were able to update the
                // workflow to TIMEOUT status, it means we were able to successfully notify the callback
                // handler, so at this point there is nothing to do.
                log.warn(
                    "workflow instance with id={} is already in a timeout state. skipping execution",
                    workflowInstance.workflowId,
                )
                return CompletableFuture.completedFuture(Option.none())
            }
            val checkpoints: List<ActionCheckpoint> =
                workflowStore.getActionCheckpoints(workflowInstance.workflowId)
            val timers: List<Timer> = workflowStore.getTimers(workflowInstance.workflowId)
            val executionContext =
                ExecutionContext.builder()
                    .workflow(workflowInstance)
                    .clock(now)
                    .actionCheckpoints(checkpoints)
                    .timers(timers)
                    .executorService(executorService)
                    .build()
            return (
                // In case of a persistence error, we will retry the execution
                workflowExecutor
                    .executeWorkflowMethod(workflowInstance, executorService, executionContext)
                    .exceptionally { e -> handleWorkflowExecutionError(e, workflowInstance, task) }
                    .thenApply { result ->
                        persistExecutionResult(result, workflowInstance, executionContext)
                    }
                    .exceptionally { e -> handleWorkflowExecutionError(e, workflowInstance, task) }
                    .thenApply { result -> notifyCaller(result, workflowInstance) }
                    .exceptionally { e -> handleNotificationError(e, workflowInstance) }
            )
        }

        private fun handleWorkflowTimeout(workflowInstance: WorkflowInstance): Option<Instant> {
            log.info("workflow instance with id={} has timed out", workflowInstance.workflowId)
            return try {
                notifyWorkflowTimeout(workflowInstance)
                metrics
                    .counter(
                        ImmutableMap.of(
                            "workflowClass",
                            workflowInstance.workflowClass.simpleName,
                            "workflowMethod",
                            workflowInstance.workflowMethod,
                        ),
                        METRICS_COMPONENT,
                        "workflowTimeouts",
                    )
                    .inc()
                val updatedInstance =
                    workflowStore.updateWorkflow(
                        // Current workflow result has been completed with ExecutionTimeout exception
                        // but that exception is not supposed to be stored as the workflow result, so
                        // let's clear it.
                        WorkflowUpdateRequest.builder()
                            .workflowInstance(workflowInstance)
                            .newStatus(WorkflowInstance.Status.TIMEOUT)
                            .clearResult(true)
                            .build(),
                    )
                log.info(
                    "workflow instance with id={} has been updated to status {}",
                    updatedInstance.workflowId,
                    updatedInstance.status,
                )
                eventPublisher.publishEvent(
                    Event.create(Event.Type.WORKFLOW_TIMEOUT, workflowInstance.workflowId),
                )
                Option.none()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                handleNotificationError(e, workflowInstance)
            }
        }

        private fun handleWorkflowExecutionError(
            e: Throwable,
            workflowInstance: WorkflowInstance,
            task: Task<*>,
        ): WorkflowExecutor.ExecutionResult {
            val cause = if (e is CompletionException) e.cause else e
            if (cause is NonRetryableError) {
                log.error(
                    "unexpected non-retryable error while executing workflowId={}. setting workflow " +
                        "state to ERROR",
                    workflowInstance.workflowId,
                    e,
                )
                return WorkflowExecutor.ExecutionResult.builder()
                    .newStatus(WorkflowInstance.Status.ERROR)
                    .result(Either.left(cause))
                    .build()
            }
            if (cause is OptimisticLockingError) {
                // Losing the optimistic-locking race is expected contention rather than a fault:
                // another worker already advanced this instance, so this worker's versioned write
                // was correctly rejected. Warn (and count, matching how the schedulers report a
                // lost lease race) instead of erroring, so high-concurrency services do not flood
                // their ERROR dashboards. Retry handling below is unchanged. The message carries
                // the workflow token and both versions, so the stack trace adds nothing.
                log.warn(
                    "lost optimistic locking race while executing workflowId={}. retrying in {}: {}",
                    workflowInstance.workflowId,
                    unexpectedErrorRetryDelay,
                    cause.message,
                )
                metrics
                    .counter(
                        ImmutableMap.of("source", "handleWorkflowExecutionError"),
                        METRICS_COMPONENT,
                        "optimisticLockingError",
                    )
                    .inc()
                return WorkflowExecutor.ExecutionResult.builder()
                    .retryDelay(unexpectedErrorRetryDelay)
                    .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                    .build()
            }
            // All errors that happen during the execution at this stage are to be considered unexpected
            // and therefore retryable.
            log.error(
                "unexpected error while executing workflowId={}. retrying in {}",
                workflowInstance.workflowId,
                unexpectedErrorRetryDelay,
                e,
            )
            metrics
                .counter(
                    ImmutableMap.of(
                        "error",
                        e.javaClass.simpleName,
                        "workflowClass",
                        workflowInstance.workflowClass.simpleName,
                        "workflowMethod",
                        workflowInstance.workflowMethod,
                    ),
                    METRICS_COMPONENT,
                    "handleExecutionUnexpectedError",
                )
                .inc()
            // All other unexpected errors will be retried
            return WorkflowExecutor.ExecutionResult.builder()
                .retryDelay(unexpectedErrorRetryDelay)
                .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                .build()
        }

        private fun handleNotificationError(
            e: Throwable,
            workflowInstance: WorkflowInstance,
        ): Option<Instant> {
            log.error(
                "unexpected error while notifying caller of workflowId={}",
                workflowInstance.workflowId,
                e,
            )
            metrics
                .counter(
                    ImmutableMap.of(
                        "error",
                        e.javaClass.simpleName,
                        "source",
                        if (workflowInstance.callbackHandler != null) {
                            workflowInstance.callbackHandler.simpleName
                        } else {
                            "none"
                        },
                    ),
                    METRICS_COMPONENT,
                    "notificationErrors",
                )
                .inc()
            return Option.some(now.instant().plus(unexpectedErrorRetryDelay))
        }

        /**
         * The cancellation cancelWorkflow recorded for this workflow, as loaded from the store. Falls
         * back to a WorkflowCancelledException should the stored result not carry an error.
         */
        private fun storedCancellation(stored: WorkflowInstance): SkipperError {
            if (stored.result.isCompletedExceptionally) {
                try {
                    stored.result.join()
                } catch (e: CompletionException) {
                    (e.cause as? SkipperError)?.let { return it }
                } catch (e: CancellationException) {
                    // fall through to the default below
                }
            }
            return WorkflowCancelledException(
                "workflow instance with id=${stored.workflowId} was cancelled while executing",
            )
        }

        private fun notifyCaller(
            result: WorkflowExecutor.ExecutionResult,
            workflowInstance: WorkflowInstance,
        ): Option<Instant> {
            var wf = workflowInstance
            if (wf.result.isDone) {
                // This means the workflow was in a waiting state, which would have the WaitSignal
                // as the result, therefore we cannot re-complete the future with the new result.
                // In these cases, the caller is no longer actively waiting on the future to
                // complete, so we will just replace the whole thing.
                wf = wf.toBuilder().result(CompletableFuture<Any?>()).build()
            }
            // Notify handlers
            val checkpointGetter: Supplier<List<ActionCheckpointView>> =
                Supplier {
                    workflowStore
                        .getActionCheckpoints(workflowInstance.workflowId)
                        .map { it.toView() }
                }
            val view = wf.toView(checkpointGetter)
            when (result.newStatus) {
                WorkflowInstance.Status.COMPLETED -> {
                    log.debug(
                        "workflow instance with id={} has completed successfully",
                        workflowInstance.workflowId,
                    )
                    notifyWorkflowCompletion(wf, result.wrappedSuccessfulResult, view)
                }
                WorkflowInstance.Status.WAITING -> {
                    log.debug(
                        "workflow instance with id={} is waiting for a signal",
                        workflowInstance.workflowId,
                    )
                    notifyWorkflowWait(wf, result.waitDuration!!, view)
                }
                WorkflowInstance.Status.RETRIES_EXHAUSTED -> {
                    notifyRetriesExhausted(wf, result.result!!.left, view)
                }
                WorkflowInstance.Status.ERROR -> {
                    log.warn(
                        "workflow instance with id={} has failed with error={}",
                        workflowInstance.workflowId,
                        result.result!!.left.message,
                    )
                    notifyNonRetryableError(wf, result.result!!.left, view)
                }
                WorkflowInstance.Status.TRANSIENT_ERROR -> {
                    val error =
                        TransientError(
                            String.format(
                                "unexpected error while processing workflow instance id=%s. The " +
                                    "execution will be retried by the engine but this future can no " +
                                    "longer be used to receive result notifications. Please consider " +
                                    "using callback handler or polling for the workflow in order to " +
                                    "receive status updates",
                                workflowInstance.workflowId,
                            ),
                        )
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.WORKFLOW_RETRIABLE_ERROR, view.id),
                    )
                    try {
                        // TODO: consider changing the branch that allows result from being null.
                        val resultError: Throwable? =
                            if (result.result != null) result.result.left else null
                        notifyRetryableError(wf, resultError, view)
                    } finally {
                        workflowInstance.result.completeExceptionally(error)
                    }
                }
                WorkflowInstance.Status.CANCELLED -> {
                    // Cancelled while executing. cancelWorkflow already persisted the status, invoked
                    // the callback handler's onCancelled and published the event, so all that is left
                    // is to release a caller still blocked on this execution's future with the stored
                    // cancellation -- otherwise a synchronous invocation would wait forever.
                    log.info(
                        "workflow instance with id={} was cancelled while executing",
                        workflowInstance.workflowId,
                    )
                    val error: Throwable =
                        result.result?.left
                            ?: WorkflowCancelledException(
                                "workflow instance with id=${workflowInstance.workflowId} was cancelled",
                            )
                    workflowInstance.result.completeExceptionally(error)
                }
                else -> {}
            }
            return if (result.retryDelay == null) {
                Option.none()
            } else {
                Option.some(now.instant().plus(result.retryDelay))
            }
        }

        private fun persistExecutionResult(
            result: WorkflowExecutor.ExecutionResult,
            workflowInstance: WorkflowInstance,
            executionContext: ExecutionContext,
        ): WorkflowExecutor.ExecutionResult {
            // TODO: skip persisting if there was no change in the workflow instance
            var currentWorkflowInstance = workflowInstance
            var context = executionContext
            // If the workflow was cancelled while this execution ran, the execution's own outcome is
            // moot. cancelWorkflow has already persisted CANCELLED (bumping the version) and removed
            // the scheduler task, so any status write from here would lose the optimistic lock,
            // surface as TRANSIENT_ERROR and reschedule a re-execution of a cancelled workflow. The
            // execution may also have ended in an unrelated error -- e.g. the lease renewal
            // interrupting the worker once the task was gone -- which is why this does not key on
            // the result type. Keep the checkpoints of the actions that completed (for any later
            // compensation), skip the status write and this execution's timers, and settle as
            // CANCELLED carrying the stored cancellation so the caller observes the same
            // CancelledWorkflow any other observer of the workflow would.
            val stored: Option<WorkflowInstance>? = workflowStore.getWorkflow(workflowInstance.workflowId)
            if (stored != null && stored.isDefined && stored.get().status == WorkflowInstance.Status.CANCELLED) {
                if (!context.dirtyCheckpoints.isEmpty) {
                    workflowStore.storeActionCheckpoints(
                        workflowInstance.workflowId,
                        context.dirtyCheckpoints,
                    )
                }
                log.info(
                    "workflow instance with id={} was cancelled while executing (execution ended " +
                        "as {}); settled without a status write",
                    workflowInstance.workflowId,
                    result.newStatus,
                )
                return WorkflowExecutor.ExecutionResult.builder()
                    .newStatus(WorkflowInstance.Status.CANCELLED)
                    .result(Either.left(storedCancellation(stored.get())))
                    .build()
            }
            if (context.shouldReloadWorkflowInstance.get()) {
                log.info(
                    "reloading workflow instance with id={} to avoid stale errors",
                    workflowInstance.workflowId,
                )
                currentWorkflowInstance = workflowStore.getWorkflow(workflowInstance.workflowId).get()
                // We perform a reload of the workflow instance because action checkpoints added at
                // workflow runtime bump the version of the workflow instance (in the backing store) so if we don't
                // reload we'll get a stale error when trying to update the workflow instance.
                // BUT we need to be careful when reloading, because if the persisted workflow instance
                // version is different from the one we have in memory, we would lose the changes and
                // that is not what we want.
                if (!currentWorkflowInstance.executionStatusIsTheSame(workflowInstance)) {
                    throw RetryableError(
                        String.format(
                            "attempted to persist a stale workflow instance. currentVersion=%s, " +
                                "inMemoryVersion=%s",
                            currentWorkflowInstance,
                            workflowInstance,
                        ),
                    )
                }
            }
            // Update workflow instance
            val workflowUpdateRequest =
                WorkflowUpdateRequest.builder()
                    .workflowInstance(currentWorkflowInstance)
                    .newState(result.newState)
                    .newStatus(
                        if (result.newStatus == null) {
                            currentWorkflowInstance.status
                        } else {
                            result.newStatus
                        },
                    )
                    .result(
                        if (result.newStatus != null && result.newStatus.isTerminal()) {
                            result.result
                        } else {
                            null
                        },
                    )
                    .resultIsAsync(result.isResultIsAsync)
                    .build()
            // We'll create or update timers if they were added or modified during the execution
            val timers: List<Timer> = List.ofAll(context.dirtyTimers)
            if (!timers.isEmpty) {
                log.debug(
                    "workflow instance with id={} has dirty timers. timers={}",
                    workflowInstance.workflowId,
                    timers,
                )
            }
            // Check if we're transitioning to ERROR status and need compensation
            if (result.newStatus == WorkflowInstance.Status.ERROR) {
                // We need to reload the action checkpoints because action checkpoints might have been
                // created due to CheckpointMode.IMMEDIATE, which would cause the checkpoint to be
                // created at the ActionExecutor layer and wouldn't be present in the execution context.
                // We need all the latest checkpoints in order to verify if compensation is needed.
                val checkpoints: List<ActionCheckpoint> =
                    workflowStore.getActionCheckpoints(workflowInstance.workflowId)
                context = context.toBuilder().actionCheckpoints(checkpoints).build()
                if (context.hasCompensationFlow()) {
                    // Schedule compensation task BEFORE persisting ERROR status
                    skipperEngine.scheduleCompensationTask(currentWorkflowInstance)
                }
            }
            val updateResult: io.vavr.Tuple3<WorkflowInstance, List<ActionCheckpoint>, List<Timer>> =
                workflowStore.updateWorkflowAndStoreCheckpointsAndTimers(
                    workflowUpdateRequest,
                    context.dirtyCheckpoints,
                    timers,
                )
            log.info(
                "workflow instance with id={} has been updated after execution. newVersion={}",
                updateResult._1.workflowId,
                updateResult._1,
            )
            if (!updateResult._3.isEmpty) {
                for (newTimer in updateResult._3) {
                    if (!newTimer.status.isTerminal) {
                        // If a timer was created, we'll schedule it for execution
                        val scheduledTask =
                            scheduler.schedule(
                                ScheduleRequest.builder<Timer>()
                                    .id(newTimer.getUniqueId())
                                    .dedupToken(newTimer.getUniqueId())
                                    .payload(newTimer)
                                    .type(Task.Type.TIMER)
                                    .runAfter(newTimer.expiresAt)
                                    .build(),
                            )
                        log.debug("scheduled timer task {}", scheduledTask)
                    }
                }
            }
            return result
        }

        private fun notifyNonRetryableError(
            workflowInstance: WorkflowInstance,
            error: Throwable?,
            workflowView: WorkflowInstanceView,
        ) {
            try {
                val handler = getCallbackHandler(workflowInstance)
                if (handler.isDefined) {
                    log.info(
                        "notifying callback handler of non-retryable error for workflowId={}",
                        workflowInstance.workflowId,
                    )
                    callbackRequestContext.around(workflowInstance, "onNonRetryableError") {
                        handler.get().onNonRetryableError(workflowView, error)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                log.error(
                    "error while notifying callback handler of non retryable error for workflowId={}",
                    workflowInstance.workflowId,
                    e,
                )
                metrics
                    .counter(
                        ImmutableMap.of(
                            "source",
                            "onNonRetryableError",
                            "handler",
                            if (workflowInstance.callbackHandler != null) {
                                workflowInstance.callbackHandler.simpleName
                            } else {
                                "none"
                            },
                        ),
                        METRICS_COMPONENT,
                        "callbackHandlerErrors",
                    )
                    .inc()
                throw e
            } finally {
                eventPublisher.publishEvent(Event.create(Event.Type.WORKFLOW_ERROR, workflowView.id))
                workflowInstance.result.completeExceptionally(error)
            }
        }

        private fun notifyRetriesExhausted(
            workflowInstance: WorkflowInstance,
            error: Throwable?,
            workflowView: WorkflowInstanceView,
        ) {
            val apiError = RetriesExhaustedError(error)
            try {
                val handler = getCallbackHandler(workflowInstance)
                if (handler.isDefined) {
                    log.info(
                        "notifying callback handler of retries exhausted error for workflowId={}",
                        workflowInstance.workflowId,
                    )
                    callbackRequestContext.around(workflowInstance, "onRetriesExhausted") {
                        handler.get().onRetriesExhausted(workflowView, error)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                log.error(
                    "error while notifying callback handler of retries exhausted for workflowId={}",
                    workflowInstance.workflowId,
                    e,
                )
                metrics
                    .counter(
                        ImmutableMap.of(
                            "source",
                            "onRetriesExhausted",
                            "handler",
                            if (workflowInstance.callbackHandler != null) {
                                workflowInstance.callbackHandler.simpleName
                            } else {
                                "none"
                            },
                        ),
                        METRICS_COMPONENT,
                        "callbackHandlerErrors",
                    )
                    .inc()
                throw e
            } finally {
                eventPublisher.publishEvent(
                    Event.create(Event.Type.WORKFLOW_RETRIES_EXHAUSTED, workflowView.id),
                )
                workflowInstance.result.completeExceptionally(apiError)
            }
        }

        private fun notifyWorkflowWait(
            workflowInstance: WorkflowInstance,
            waitDuration: Duration,
            workflowView: WorkflowInstanceView,
        ) {
            try {
                val handler = getCallbackHandler(workflowInstance)
                if (handler.isDefined) {
                    log.info(
                        "notifying callback handler of waiting status for workflowId={}",
                        workflowInstance.workflowId,
                    )
                    callbackRequestContext.around(workflowInstance, "onWorkflowInWaitingStatus") {
                        handler.get().onWorkflowInWaitingStatus(workflowView)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                log.error(
                    "error while notifying callback handler of waiting status for workflowId={}",
                    workflowInstance.workflowId,
                    e,
                )
                metrics
                    .counter(
                        ImmutableMap.of(
                            "source",
                            "onWorkflowInWaitingStatus",
                            "handler",
                            if (workflowInstance.callbackHandler != null) {
                                workflowInstance.callbackHandler.simpleName
                            } else {
                                "none"
                            },
                        ),
                        METRICS_COMPONENT,
                        "callbackHandlerErrors",
                    )
                    .inc()
                throw e
            } finally {
                eventPublisher.publishEvent(Event.create(Event.Type.WORKFLOW_WAITING, workflowView.id))
                workflowInstance.result.completeExceptionally(WaitSignal(waitDuration))
            }
        }

        private fun notifyWorkflowCompletion(
            workflowInstance: WorkflowInstance,
            result: Any?,
            workflowView: WorkflowInstanceView,
        ) {
            try {
                val handler = getCallbackHandler(workflowInstance)
                if (handler.isDefined) {
                    log.info(
                        "notifying callback handler of successful completion for workflowId={}",
                        workflowInstance.workflowId,
                    )
                    callbackRequestContext.around(workflowInstance, "onSuccess") {
                        handler.get().onSuccess(workflowView)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                log.error(
                    "error while notifying callback handler of successful completion for " +
                        "workflowId={}",
                    workflowInstance.workflowId,
                    e,
                )
                metrics
                    .counter(
                        ImmutableMap.of(
                            "source",
                            "onSuccess",
                            "handler",
                            if (workflowInstance.callbackHandler != null) {
                                workflowInstance.callbackHandler.simpleName
                            } else {
                                "none"
                            },
                        ),
                        METRICS_COMPONENT,
                        "callbackHandlerErrors",
                    )
                    .inc()
                throw e
            } finally {
                eventPublisher.publishEvent(
                    Event.create(Event.Type.WORKFLOW_COMPLETED, workflowView.id),
                )
                workflowInstance.result.complete(result)
            }
        }

        private fun notifyWorkflowTimeout(workflowInstance: WorkflowInstance) {
            try {
                val handler = getCallbackHandler(workflowInstance)
                if (handler.isDefined) {
                    log.info(
                        "notifying callback handler of timeout for workflowId={}",
                        workflowInstance.workflowId,
                    )
                    callbackRequestContext.around(workflowInstance, "onWorkflowTimeout") {
                        handler
                            .get()
                            .onWorkflowTimeout(
                                workflowInstance.toView(
                                    Supplier {
                                        workflowStore
                                            .getActionCheckpoints(workflowInstance.workflowId)
                                            .map { it.toView() }
                                    },
                                ),
                            )
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                log.error(
                    "error while notifying callback handler of workflow timeout for workflowId={}",
                    workflowInstance.workflowId,
                    e,
                )
                metrics
                    .counter(
                        ImmutableMap.of(
                            "source",
                            "onWorkflowTimeout",
                            "handler",
                            if (workflowInstance.callbackHandler != null) {
                                workflowInstance.callbackHandler.simpleName
                            } else {
                                "none"
                            },
                        ),
                        METRICS_COMPONENT,
                        "callbackHandlerErrors",
                    )
                    .inc()
                throw e
            } finally {
                workflowInstance.result.completeExceptionally(ExecutionTimeout())
            }
        }

        private fun notifyRetryableError(
            workflowInstance: WorkflowInstance,
            error: Throwable?,
            workflowView: WorkflowInstanceView,
        ) {
            val handler = getCallbackHandler(workflowInstance)
            if (handler.isDefined) {
                log.info(
                    "notifying callback handler of retryable error for workflowId={}",
                    workflowInstance.workflowId,
                )
                try {
                    callbackRequestContext.around(workflowInstance, "onRetryableError") {
                        handler.get().onRetryableError(workflowView, error)
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    log.error(
                        "error while notifying callback handler of retryable error for workflowId={}",
                        workflowInstance.workflowId,
                        e,
                    )
                    metrics
                        .counter(
                            ImmutableMap.of(
                                "source",
                                "onRetryableError",
                                "handler",
                                if (workflowInstance.callbackHandler != null) {
                                    workflowInstance.callbackHandler.simpleName
                                } else {
                                    "none"
                                },
                            ),
                            METRICS_COMPONENT,
                            "callbackHandlerErrors",
                        )
                        .inc()
                    throw e
                }
            }
        }

        private fun getCallbackHandler(workflowInstance: WorkflowInstance): Option<WorkflowCallbackHandler> {
            if (workflowInstance.callbackHandler != null) {
                return Option.of(callbackHandlerInjector.getInstance(workflowInstance.callbackHandler))
            }
            return Option.none()
        }

        companion object {
            private val log = LoggerFactory.getLogger(WorkflowExecutionTaskHandler::class.java)
            private val DEFAULT_RETRY_STRATEGY: RetryStrategy =
                FixedRetryStrategy(Duration.ofSeconds(1), 5)
            private const val METRICS_COMPONENT = "workflowExecutionTaskHandler"
        }
    }
