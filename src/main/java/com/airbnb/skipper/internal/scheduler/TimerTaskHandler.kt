package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.SkipperAnnotationNames.SCHEDULER_LEASE_DURATION
import com.airbnb.skipper.SkipperAnnotationNames.UNEXPECTED_ERROR_RETRY_DELAY
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.Timer
import com.airbnb.skipper.internal.storage.WorkflowStore
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

class TimerTaskHandler
    @Inject
    constructor(
        private val workflowStore: WorkflowStore,
        private val scheduler: Scheduler,
        @Named(UNEXPECTED_ERROR_RETRY_DELAY) private val unexpectedErrorDelay: Duration,
        @Named(UTC_CLOCK) private val clock: Clock,
        private val metrics: Metrics,
        @Named(SCHEDULER_LEASE_DURATION) private val leaseDuration: Duration,
    ) : TaskHandler {
        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            log.info("handling timer task with ID: {}", task.id)
            try {
                val timer = task.payload as Timer
                if (timer.expiresAt.isAfter(clock.instant())) {
                    log.warn(
                        "timer with ID: {} is going to be executed before expiration date. " +
                            "timer={}, now={}",
                        timer.id,
                        timer,
                        clock.instant(),
                    )
                }
                if (workflowHasActiveLease(timer.workflowId)) {
                    log.warn(
                        "timer with ID {} can't be expired because workflow {} has active lease. " +
                            "retrying in {}",
                        timer.getUniqueId(),
                        timer.workflowId,
                        LEASED_WORKFLOW_RETRY_DURATION,
                    )
                    return CompletableFuture.completedFuture(
                        Option.of(clock.instant().plus(LEASED_WORKFLOW_RETRY_DURATION)),
                    )
                }
                if (!workflowStore.expireTimer(timer.workflowId, timer.id)) {
                    log.warn("unable to expire timer with ID: {}", timer.id)
                    metrics.counter(METRICS_COMPONENT, "expireTimerFailed").inc()
                } else {
                    // Now that the timer is expired, schedule a workflow execution
                    scheduler.schedule(
                        ScheduleRequest.builder<Any?>()
                            .id(timer.workflowId)
                            .dedupToken(timer.workflowId)
                            .payload(null)
                            .type(Task.Type.WORKFLOW)
                            .honorActiveLeaseWhenOverwriting(true)
                            .build(),
                    )
                    log.info(
                        "expired timer with ID: {}. timer={}. currentTime={}",
                        timer.id,
                        timer,
                        clock.instant(),
                    )
                    metrics.counter(METRICS_COMPONENT, "expireTimerSuccess").inc()
                }
            } catch (e: IllegalArgumentException) {
                log.error("unable to expire timer with invalid ID: {}", task.id, e)
                metrics.counter(METRICS_COMPONENT, "expireTimerInvalidId").inc()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error("unexpected error while trying to expire timer with ID: {}", task.id, e)
                metrics.counter(METRICS_COMPONENT, "expireTimerError").inc()
                return CompletableFuture.completedFuture(
                    Option.of(clock.instant().plus(unexpectedErrorDelay)),
                )
            }
            return CompletableFuture.completedFuture(Option.none())
        }

        private fun workflowHasActiveLease(workflowId: String): Boolean {
            // We know that the workflow execution task always has the ID of the workflow.
            val workflowTask: Option<Task<Any>> = scheduler.getTask(workflowId)
            if (workflowTask.isEmpty) {
                return false
            }
            return workflowTask.get().hasActiveLease(clock.instant(), leaseDuration)
        }

        companion object {
            private val log = LoggerFactory.getLogger(TimerTaskHandler::class.java)
            private const val METRICS_COMPONENT = "timerTaskHandler"
            private val LEASED_WORKFLOW_RETRY_DURATION: Duration = Duration.ofSeconds(5)
        }
    }
