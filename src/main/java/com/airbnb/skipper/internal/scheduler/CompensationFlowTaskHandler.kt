package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.SkipperAnnotationNames.UNEXPECTED_ERROR_RETRY_DELAY
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.CompensationExecutor
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.google.common.collect.ImmutableMap
import io.vavr.collection.List
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
 * Handles the processing of compensation flow tasks.
 *
 * This handler is responsible for processing tasks of type COMPENSATION and implementing the
 * compensation flow logic for workflows that need to compensate for previously executed actions.
 */
class CompensationFlowTaskHandler
    @Inject
    constructor(
        private val workflowStore: WorkflowStore,
        private val compensationExecutor: CompensationExecutor,
        @Named(UNEXPECTED_ERROR_RETRY_DELAY) private val unexpectedErrorRetryDelay: Duration,
        @Named(UTC_CLOCK) private val clock: Clock,
        private val metrics: Metrics,
        private val eventPublisher: EventPublisher,
        private val callbackHandlerInjector: SkipperInjector,
        private val middleware: RawRequestContextMiddleware,
    ) : TaskHandler {
        /**
         * Installs the workflow's own request context around every callback notification this
         * handler fires, the same way the compensation actions themselves already run.
         */
        private val callbackRequestContext =
            CallbackRequestContextBracket(middleware, metrics, METRICS_COMPONENT)

        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            log.info("handling compensation flow task with ID: {}", task.id)
            return try {
                val workflowId = extractWorkflowId(task)
                val workflow = retrieveWorkflowInstance(workflowId, task.id)
                // Publish compensation start event
                eventPublisher.publishEvent(
                    Event.create(
                        Event.Type.COMPENSATION_STARTED,
                        workflowId,
                        String.format("retries: %d", task.retryCount),
                    ),
                )
                val validationResult = validateWorkflowStatus(workflow.status)
                executeCompensation(validationResult, workflow, executorService)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error("unexpected error processing compensation task {}", task.id, e)
                metrics.counter(METRICS_COMPONENT, "unexpectedError").inc()
                CompletableFuture.completedFuture(
                    Option.of(clock.instant().plus(unexpectedErrorRetryDelay)),
                )
            }
        }

        private fun extractWorkflowId(task: Task<*>): String {
            val workflowId = task.payload as String?
            if (workflowId.isNullOrEmpty()) {
                log.error("compensation task {} has null or empty workflow ID", task.id)
                metrics.counter(METRICS_COMPONENT, "invalidWorkflowId").inc()
                throw IllegalArgumentException("Task has null or empty workflow ID")
            }
            return workflowId
        }

        private fun retrieveWorkflowInstance(
            workflowId: String,
            taskId: String
        ): WorkflowInstance {
            val workflowOpt = workflowStore.getWorkflow(workflowId)
            if (workflowOpt.isEmpty) {
                log.error(
                    "workflow instance with ID {} not found for compensation task {}",
                    workflowId,
                    taskId,
                )
                metrics.counter(METRICS_COMPONENT, "workflowNotFound").inc()
                throw IllegalStateException("Workflow not found")
            }
            log.info(
                "processing compensation for workflow {} with status {}",
                workflowId,
                workflowOpt.get().status,
            )
            return workflowOpt.get()
        }

        private fun executeCompensation(
            result: StatusValidationResult,
            workflow: WorkflowInstance,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            val workflowId = workflow.workflowId
            val status = workflow.status
            when (result) {
                StatusValidationResult.PROCEED -> {
                    log.info(
                        "workflow {} with status {} is ready for compensation processing",
                        workflowId,
                        status,
                    )
                    metrics.counter(METRICS_COMPONENT, "workflowReady").inc()
                    return executeCompensationFlow(workflow, executorService)
                }
                StatusValidationResult.RETRY -> {
                    log.info(
                        "workflow {} is in non-terminal state {}, retrying compensation task in {} " +
                            "seconds",
                        workflowId,
                        status,
                        WORKFLOW_NOT_READY_RETRY_DELAY.seconds,
                    )
                    metrics.counter(METRICS_COMPONENT, "workflowNotReady").inc()
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.COMPENSATION_CANNOT_START, workflowId),
                    )
                    return CompletableFuture.completedFuture(
                        Option.of(clock.instant().plus(WORKFLOW_NOT_READY_RETRY_DELAY)),
                    )
                }
                StatusValidationResult.NO_OP -> {
                    log.info("workflow {} is in status {}, no compensation needed", workflowId, status)
                    metrics.counter(METRICS_COMPONENT, "workflowNoOp").inc()
                    eventPublisher.publishEvent(Event.create(Event.Type.COMPENSATION_NOOP, workflowId))
                    // If status is COMPENSATION_COMPLETED, this likely means the callback previously
                    // failed so we should retry notifying the callback handler
                    if (workflow.status == WorkflowInstance.Status.COMPENSATION_COMPLETED) {
                        log.info(
                            "retrying callback notification for completed compensation on workflow {}",
                            workflowId,
                        )
                        return CompletableFuture.completedFuture(retryCallbackNotification(workflow))
                    }
                    return CompletableFuture.completedFuture(Option.none())
                }
            }
        }

        private fun validateWorkflowStatus(status: WorkflowInstance.Status): StatusValidationResult {
            // Proceed with compensation if workflow is in compensable states
            if (status == WorkflowInstance.Status.ERROR || status.isCompensationInProgress()) {
                return StatusValidationResult.PROCEED
            }
            if (status.isTerminal()) {
                return StatusValidationResult.NO_OP
            }
            return StatusValidationResult.RETRY
        }

        private fun executeCompensationFlow(
            workflow: WorkflowInstance,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            val workflowId = workflow.workflowId
            return try {
                // Create execution context
                val executionContext =
                    ExecutionContext.builder()
                        .workflow(workflow)
                        .clock(clock)
                        .actionCheckpoints(workflowStore.getActionCheckpoints(workflowId))
                        .executorService(executorService)
                        .build()
                log.info("executing compensation flow for workflow {}", workflowId)
                compensationExecutor
                    .executeCompensationFlow(workflow, executorService, executionContext)
                    .thenApply { result ->
                        processCompensationResult(workflow, executionContext, result)
                    }
                    .exceptionally { ex ->
                        val cause = if (ex is CompletionException) ex.cause else ex
                        if (cause is OptimisticLockingError) {
                            // Same expected contention as WorkflowExecutionTaskHandler: another
                            // worker advanced the instance first, so warn and count rather than
                            // error. The retry below is unchanged.
                            log.warn(
                                "lost optimistic locking race while persisting compensation flow" +
                                    " result for workflow {}: {}",
                                workflowId,
                                cause.message,
                            )
                            metrics
                                .counter(
                                    ImmutableMap.of("source", "persistCompensationResult"),
                                    METRICS_COMPONENT,
                                    "optimisticLockingError",
                                )
                                .inc()
                        } else {
                            log.error(
                                "unexpected error while trying to persist compensation flow result {}",
                                workflowId,
                                ex,
                            )
                            metrics.counter(METRICS_COMPONENT, "compensationProcessingError").inc()
                        }
                        Option.of(clock.instant().plus(unexpectedErrorRetryDelay))
                    }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error("failed to execute compensation flow for workflow {}", workflowId, e)
                metrics.counter(METRICS_COMPONENT, "compensationError").inc()
                CompletableFuture.completedFuture(
                    Option.of(clock.instant().plus(unexpectedErrorRetryDelay)),
                )
            }
        }

        private fun processCompensationResult(
            workflow: WorkflowInstance,
            context: ExecutionContext,
            result: CompensationExecutor.CompensationResult,
        ): Option<Instant> {
            val newStatus = result.newStatus
            // Update workflow status and persist checkpoints
            val updateRequest =
                WorkflowUpdateRequest.builder()
                    .workflowInstance(workflow)
                    .newStatus(newStatus)
                    .build()
            workflowStore.updateWorkflowAndStoreCheckpointsAndTimers(
                updateRequest,
                context.dirtyCheckpoints,
                List.empty(),
            )
            // Handle different result statuses
            return handleCompensationStatus(newStatus, workflow, result)
        }

        private fun handleCompensationStatus(
            status: WorkflowInstance.Status,
            workflow: WorkflowInstance,
            result: CompensationExecutor.CompensationResult,
        ): Option<Instant> {
            val workflowId = workflow.workflowId
            when (status) {
                WorkflowInstance.Status.COMPENSATION_COMPLETED -> {
                    log.info("compensation flow completed successfully for workflow {}", workflowId)
                    metrics.counter(METRICS_COMPONENT, "compensationCompleted").inc()
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.COMPENSATION_COMPLETED, workflowId),
                    )
                    notifyCompensationCompleteCallback(workflow)
                    return Option.none()
                }
                WorkflowInstance.Status.COMPENSATION_ERROR -> {
                    if (result.error.isDefined) {
                        log.error(
                            "compensation flow failed for workflow {}: {}",
                            workflowId,
                            result.error.get(),
                        )
                    } else {
                        log.error("compensation flow failed for workflow {}", workflowId)
                    }
                    metrics.counter(METRICS_COMPONENT, "compensationFailed").inc()
                    eventPublisher.publishEvent(Event.create(Event.Type.COMPENSATION_ERROR, workflowId))
                    notifyCompensationErrorCallback(workflow, result.error)
                    return Option.none()
                }
                WorkflowInstance.Status.COMPENSATION_IN_PROGRESS -> {
                    if (result.error.isDefined) {
                        log.info(
                            "compensation flow encountered retryable error for workflow {} ({}), " +
                                "will retry",
                            workflowId,
                            result.error.get().javaClass.simpleName,
                        )
                    } else {
                        log.info(
                            "compensation flow encountered retryable error for workflow {}, will retry",
                            workflowId,
                        )
                    }
                    metrics.counter(METRICS_COMPONENT, "compensationRetry").inc()
                    eventPublisher.publishEvent(
                        Event.create(Event.Type.COMPENSATION_RETRYABLE_ERROR, workflowId),
                    )
                    return Option.of(clock.instant().plus(unexpectedErrorRetryDelay))
                }
                else -> {
                    log.warn(
                        "unexpected status {} from compensation execution for workflow {}",
                        status,
                        workflowId,
                    )
                    return Option.of(clock.instant().plus(unexpectedErrorRetryDelay))
                }
            }
        }

        private fun retryCallbackNotification(workflow: WorkflowInstance): Option<Instant> {
            return try {
                notifyCompensationCompleteCallback(workflow)
                Option.none() // Success, no retry needed
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error(
                    "failed to notify callback for completed compensation on workflow {}",
                    workflow.workflowId,
                    e,
                )
                metrics.counter(METRICS_COMPONENT, "callbackNotificationRetryFailed").inc()
                Option.of(clock.instant().plus(unexpectedErrorRetryDelay))
            }
        }

        private fun notifyCompensationCompleteCallback(workflow: WorkflowInstance) {
            val workflowId = workflow.workflowId
            val callbackHandlerOpt = getCallbackHandler(workflow)
            if (callbackHandlerOpt.isEmpty) {
                log.debug("no callback handler configured for workflow {}", workflowId)
                return
            }
            val callbackHandler = callbackHandlerOpt.get()
            try {
                log.info(
                    "notifying callback handler for completed compensation on workflow {}",
                    workflowId,
                )
                callbackRequestContext.around(workflow, "onCompensationCompleted") {
                    callbackHandler.onCompensationCompleted(
                        workflow.toView(Supplier { List.empty() }),
                    )
                }
                metrics.counter(METRICS_COMPONENT, "callbackNotificationSuccess").inc()
                log.info(
                    "successfully notified callback handler for completed compensation on workflow {}",
                    workflowId,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error(
                    "callback handler failed for completed compensation on workflow {}",
                    workflowId,
                    e,
                )
                metrics.counter(METRICS_COMPONENT, "callbackNotificationFailed").inc()
                // Re-throw the exception to trigger retry logic
                throw CallbackNotificationException("Compensation callback notification failed", e)
            }
        }

        private fun notifyCompensationErrorCallback(
            workflow: WorkflowInstance,
            error: Option<SkipperError>,
        ) {
            val workflowId = workflow.workflowId
            val callbackHandlerOpt = getCallbackHandler(workflow)
            if (callbackHandlerOpt.isEmpty) {
                log.debug("no callback handler configured for workflow {}", workflowId)
                return
            }
            val callbackHandler = callbackHandlerOpt.get()
            try {
                log.info("notifying callback handler for compensation error on workflow {}", workflowId)
                callbackRequestContext.around(workflow, "onCompensationError") {
                    callbackHandler.onCompensationError(
                        workflow.toView(Supplier { List.empty() }),
                        error.getOrElse(null as SkipperError?),
                    )
                }
                metrics.counter(METRICS_COMPONENT, "errorCallbackNotificationSuccess").inc()
                log.info(
                    "successfully notified callback handler for compensation error on workflow {}",
                    workflowId,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error(
                    "callback handler failed for compensation error on workflow {}",
                    workflowId,
                    e,
                )
                metrics.counter(METRICS_COMPONENT, "errorCallbackNotificationFailed").inc()
            }
            // Note: We don't re-throw here as compensation error callbacks are best-effort
        }

        private fun getCallbackHandler(workflowInstance: WorkflowInstance): Option<WorkflowCallbackHandler> {
            if (workflowInstance.callbackHandler != null) {
                return Option.of(callbackHandlerInjector.getInstance(workflowInstance.callbackHandler))
            }
            return Option.none()
        }

        private enum class StatusValidationResult {
            PROCEED,
            RETRY,
            NO_OP,
        }

        /** Exception thrown when callback notification fails and needs to be retried. */
        private class CallbackNotificationException(message: String, cause: Throwable) :
            RuntimeException(message, cause)

        companion object {
            private val log = LoggerFactory.getLogger(CompensationFlowTaskHandler::class.java)
            private const val METRICS_COMPONENT = "compensationFlowTaskHandler"
            private val WORKFLOW_NOT_READY_RETRY_DELAY: Duration = Duration.ofSeconds(1)
        }
    }
