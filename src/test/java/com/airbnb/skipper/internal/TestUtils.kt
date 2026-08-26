package com.airbnb.skipper.internal

import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.util.ExtraRequestData
import com.google.common.collect.ImmutableMap
import io.vavr.collection.HashMap
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

object TestUtils {
    @JvmField var REQUEST_CONTEXT: TestRequestContext = TestRequestContext.builder().build()

    @JvmField
    var EXTRA_REQUEST_DATA: ExtraRequestData = ExtraRequestData(ImmutableMap.of("key", "value"))

    @JvmStatic
    fun getWorkflowInstance(): WorkflowInstance =
        WorkflowInstance.builder()
            .workflowId("test-workflow-id")
            .input("test")
            .requestContext(REQUEST_CONTEXT)
            .extraRequestData(EXTRA_REQUEST_DATA)
            .result(CompletableFuture<Any?>())
            .state(HashMap.empty<String, Any?>())
            .version(1)
            .workflowClass(Workflow::class.java)
            .workflowMethod("test-method")
            .createdAt(Instant.EPOCH)
            .build()

    @JvmStatic
    fun getTestTask(workflowInstance: WorkflowInstance): Task<WorkflowInstance> =
        Task.builder<WorkflowInstance>()
            .id("test-task-token")
            .type(Task.Type.WORKFLOW)
            .payload(workflowInstance)
            .dedupToken("test-dedup-token")
            .createdAt(Instant.now())
            .runAfter(Instant.EPOCH)
            .executionTimeout(Duration.ofSeconds(30))
            .build()

    @JvmStatic
    fun getTestTimerTask(
        id: String,
        timer: Timer
    ): Task<Timer> =
        Task.builder<Timer>()
            .id(id)
            .type(Task.Type.TIMER)
            .payload(timer)
            .dedupToken("test-dedup-token")
            .createdAt(Instant.now())
            .runAfter(Instant.EPOCH)
            .executionTimeout(Duration.ofSeconds(30))
            .build()

    @JvmStatic
    fun getTestTimer(): Timer =
        Timer(
            "test-workflow-id",
            "test-timer-id",
            null,
            Duration.ofSeconds(30),
            Instant.now().plus(Duration.ofSeconds(30)),
            Timer.Status.ACTIVE,
            1,
        )

    class NoRetry : RetryStrategy {
        override fun nextRetryDelay(retryCount: Int): Optional<Duration> = Optional.empty()
    }
}
