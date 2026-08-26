package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.internal.storage.TimerCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class TimerTaskHandlerTest {
    private lateinit var timerTaskHandler: TimerTaskHandler
    private lateinit var workflowStore: WorkflowStore
    private lateinit var scheduler: Scheduler

    private val clock: Clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(clock)
        val runtime = deps.runtime
        timerTaskHandler = runtime.timerTaskHandler.get()
        workflowStore = deps.workflowStore
        scheduler = deps.scheduler
    }

    @Test
    fun testHandleHappyPath() {
        createInstance()
        val timer =
            workflowStore.createTimer(
                TimerCreationRequest.builder()
                    .workflowId(WORKFLOW_ID)
                    .timerId("test-timer-id")
                    .duration(Duration.ofSeconds(10))
                    .expiresAt(Instant.EPOCH.plus(Duration.ofSeconds(10)))
                    .build()
            )
        val task =
            Task.builder<Timer>()
                .id("test-task-token")
                .type(Task.Type.TIMER)
                .payload(timer)
                .dedupToken("test-dedup-token")
                .createdAt(Instant.EPOCH)
                .runAfter(Instant.EPOCH)
                .executionTimeout(Duration.ofSeconds(30))
                .build()
        val result = timerTaskHandler.handle(task, mock()).join()
        assertTrue(result.isEmpty)
        // Verify that the scheduler task has been created
        val executionTask = scheduler.getTask<Any>(WORKFLOW_ID)
        assertTrue(executionTask.isDefined)
        // Verify that the timer is expired
        val storedTimer = workflowStore.getTimer(WORKFLOW_ID, "test-timer-id")
        assertTrue(storedTimer.isDefined)
        assertEquals(Timer.Status.EXPIRED, storedTimer.get().status)
    }

    @Test
    fun testHandleWhenInvalidId() {
        val timer =
            Timer(
                "invalid-workflow-id",
                "test-timer-id",
                null,
                Duration.ofSeconds(10),
                Instant.EPOCH.plus(Duration.ofSeconds(10)),
                Timer.Status.ACTIVE,
                0
            )
        val task =
            Task.builder<Timer>()
                .id("test-task-token")
                .type(Task.Type.TIMER)
                .payload(timer)
                .dedupToken("test-dedup-token")
                .createdAt(Instant.EPOCH)
                .runAfter(Instant.EPOCH)
                .executionTimeout(Duration.ofSeconds(30))
                .build()
        val result = timerTaskHandler.handle(task, mock()).join()
        assertTrue(result.isEmpty)
        assert(scheduler.getTask<Any>("invalid-workflow-id").isEmpty)
    }

    @Test
    fun testHandleWhenWorkflowHasActiveLease() {
        createInstance()
        createTask(clock.instant().minusSeconds(10))
        val timer =
            workflowStore.createTimer(
                TimerCreationRequest.builder()
                    .workflowId(WORKFLOW_ID)
                    .timerId("test-timer-id")
                    .duration(Duration.ofSeconds(10))
                    .expiresAt(Instant.EPOCH.plus(Duration.ofSeconds(10)))
                    .build()
            )
        val task =
            Task.builder<Timer>()
                .id("test-task-token")
                .type(Task.Type.TIMER)
                .payload(timer)
                .dedupToken("test-dedup-token")
                .createdAt(Instant.EPOCH)
                .runAfter(Instant.EPOCH)
                .executionTimeout(Duration.ofSeconds(30))
                .build()
        val result = timerTaskHandler.handle(task, mock()).join()
        assertTrue(result.isDefined)
        assertEquals(clock.instant().plusSeconds(5), result.get())
        // Verify that the timer is expired
        val storedTimer = workflowStore.getTimer(WORKFLOW_ID, "test-timer-id")
        assertTrue(storedTimer.isDefined)
        assertEquals(Timer.Status.ACTIVE, storedTimer.get().status)
    }

    @Test
    fun testHandleWhenWorkflowTaskExistsButNoActiveLease() {
        createInstance()
        createTask(clock.instant().plus(Duration.ofHours(1)))
        val timer =
            workflowStore.createTimer(
                TimerCreationRequest.builder()
                    .workflowId(WORKFLOW_ID)
                    .timerId("test-timer-id")
                    .duration(Duration.ofSeconds(10))
                    .expiresAt(Instant.EPOCH.plus(Duration.ofSeconds(10)))
                    .build()
            )
        val task =
            Task.builder<Timer>()
                .id("test-task-token")
                .type(Task.Type.TIMER)
                .payload(timer)
                .dedupToken("test-dedup-token")
                .createdAt(Instant.EPOCH)
                .runAfter(Instant.EPOCH)
                .executionTimeout(Duration.ofSeconds(30))
                .build()
        val result = timerTaskHandler.handle(task, mock()).join()
        assertTrue(result.isEmpty)
        // Verify that the scheduler task has been created
        val executionTask = scheduler.getTask<Any>(WORKFLOW_ID)
        assertTrue(executionTask.isDefined)
        // Verify that the timer is expired
        val storedTimer = workflowStore.getTimer(WORKFLOW_ID, "test-timer-id")
        assertTrue(storedTimer.isDefined)
        assertEquals(Timer.Status.EXPIRED, storedTimer.get().status)
    }

    private fun createInstance(): WorkflowInstance {
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId(WORKFLOW_ID)
                .workflowMethod("test-method")
                .input("test")
                .requestContext(TestUtils.REQUEST_CONTEXT)
                .extraRequestData(TestUtils.EXTRA_REQUEST_DATA)
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(null)
                .build()
        return workflowStore.createWorkflow(request)
    }

    private fun createTask(`when`: Instant) {
        scheduler.schedule(
            ScheduleRequest.builder<Any>()
                .id(WORKFLOW_ID)
                .dedupToken(WORKFLOW_ID)
                .payload(null)
                .type(Task.Type.WORKFLOW)
                .honorActiveLeaseWhenOverwriting(false)
                .runAfter(`when`)
                .build()
        )
    }

    companion object {
        private const val WORKFLOW_ID = "test-workflow-id"
    }
}
