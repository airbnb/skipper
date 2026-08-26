package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.internal.SkipperEngine
import io.vavr.control.Option
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

/**
 * This simple handler will schedule a workflow execution when a workflow instance has expired. The
 * actual expiration of the workflow is done at the workflow execution flow, this handler will only
 * take care of scheduling the execution.
 */
class ExecutionTimeoutHandler
    @Inject
    constructor(
        private val scheduler: Scheduler,
        private val skipperEngine: SkipperEngine,
        @Named(UTC_CLOCK) private val clock: Clock,
    ) : TaskHandler {
        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            log.info("handling execution timeout for task: {}", task)
            val workflowId = task.dedupToken
            val workflow = skipperEngine.getWorkflow(workflowId)
            if (workflow.isEmpty) {
                log.warn("unable to find workflow instance for workflow expiration: {}", workflowId)
                return CompletableFuture.completedFuture(Option.none())
            }
            val timeoutTime = workflow.get().timeoutTime
            if (timeoutTime == null) {
                log.warn("workflow instance does not have a timeout time: {}", workflowId)
                return CompletableFuture.completedFuture(Option.none())
            }
            if (timeoutTime.isAfter(clock.instant())) {
                log.warn(
                    "workflow instance has not expired yet: {}. delaying expiration until {}",
                    workflowId,
                    timeoutTime,
                )
                return CompletableFuture.completedFuture(Option.of(timeoutTime))
            }
            scheduler.schedule(
                ScheduleRequest.builder<Any?>()
                    .id(workflowId)
                    .dedupToken(workflowId)
                    .payload(null)
                    .type(Task.Type.WORKFLOW)
                    .honorActiveLeaseWhenOverwriting(true)
                    .build(),
            )
            log.info("scheduled workflow execution for expired workflow: {}", workflowId)
            return CompletableFuture.completedFuture(Option.none())
        }

        companion object {
            private val log = LoggerFactory.getLogger(ExecutionTimeoutHandler::class.java)
        }
    }
