package com.airbnb.skipper.integtest

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.IWorkflowFactory
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.ScheduleOutcome
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.testutils.trace.Tracing
import com.airbnb.skipper.util.ExtraRequestData
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.`when`

/**
 * A parent workflow starts its children on its own execution thread, so a store or scheduler failure
 * while starting one of them escapes the parent's workflow method. Started with
 * [com.airbnb.skipper.InvocationBuilder.asChildOf], a transient one (a timeout) retries the parent,
 * which then starts the remaining children and completes. Started without it, the same failure is
 * taken for a bug in the workflow code and fails the parent terminally. A request the
 * store rejects as invalid fails the parent either way, since retrying it cannot succeed.
 *
 * Each test fails the first store or scheduler call for the second of three children.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ChildWorkflowStartFailureTest {
    /** Throws [error] from the first call that targets [workflowId]; every other call passes through. */
    class Fault(
        private val workflowId: String,
        private val error: () -> Exception,
    ) {
        val hits = AtomicInteger()

        fun maybeThrow(id: String) {
            if (id == workflowId && hits.incrementAndGet() == 1) {
                throw error()
            }
        }
    }

    class FaultyStore(
        private val delegate: WorkflowStore,
        private val fault: Fault?,
    ) : WorkflowStore by delegate {
        override fun createWorkflow(request: WorkflowCreationRequest): WorkflowInstance {
            fault?.maybeThrow(request.workflowId)
            return delegate.createWorkflow(request)
        }
    }

    class FaultyScheduler(
        private val delegate: Scheduler,
        private val fault: Fault?,
    ) : Scheduler by delegate {
        override fun <T> schedule(request: ScheduleRequest<T>): Task<T> {
            if (request.type == Task.Type.WORKFLOW) {
                fault?.maybeThrow(request.id)
            }
            return delegate.schedule(request)
        }

        override fun <T> scheduleWithOutcome(request: ScheduleRequest<T>): ScheduleOutcome<T> {
            if (request.type == Task.Type.WORKFLOW) {
                fault?.maybeThrow(request.id)
            }
            return delegate.scheduleWithOutcome(request)
        }
    }

    open class ChildWorkflow : Workflow() {
        @WorkflowMethod
        open fun run() {}
    }

    open class FanOutWorkflow : Workflow() {
        @Inject lateinit var workflowFactory: IWorkflowFactory

        @WorkflowMethod
        open fun run(): String {
            for (i in 1..CHILDREN) {
                workflowFactory.builder(ChildWorkflow::class.java, childId(id, i)).runAsync().detached().asChildOf(this).build().run()
            }
            return "started $CHILDREN children"
        }
    }

    open class PlainFanOutWorkflow : Workflow() {
        @Inject lateinit var workflowFactory: IWorkflowFactory

        @WorkflowMethod
        open fun run(): String {
            for (i in 1..CHILDREN) {
                workflowFactory.builder(ChildWorkflow::class.java, childId(id, i)).runAsync().detached().build().run()
            }
            return "started $CHILDREN children"
        }
    }

    private lateinit var engine: SkipperEngine
    private lateinit var scheduler: Scheduler
    private lateinit var manager: SkipperSchedulerManager

    @AfterEach
    fun tearDown() {
        if (::manager.isInitialized) manager.forceStop()
    }

    private fun start(
        storeFault: Fault? = null,
        schedulerFault: Fault? = null,
        createExistingWorkflowIsNoop: Boolean = false,
    ) {
        val deps = TestRuntime()
        `when`(deps.featureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP)).thenReturn(createExistingWorkflowIsNoop)
        deps.config.taskUnexpectedErrorRetryDelay = Duration.ofMillis(200)
        deps.config.schedulerTaskLeaseDuration = Duration.ofSeconds(1)
        // Trace the stock backends beneath the faults (when tracing is on), so trace validation checks
        // what reached the store, the recovery of a stranded child included.
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        Tracing.installIfRequested(deps.config)
        val store = deps.config.workflowStore
        val stockScheduler = deps.config.scheduler
        deps.config.workflowStore = ComponentFactory { FaultyStore(store.create(it), storeFault) }
        deps.config.scheduler = ComponentFactory { FaultyScheduler(stockScheduler.create(it), schedulerFault) }
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        scheduler = runtime.scheduler.get()
        manager = runtime.skipperSchedulerManager.get()
        manager.start()
    }

    private fun startParent(
        parentId: String,
        parentClass: Class<out Workflow> = FanOutWorkflow::class.java,
    ) {
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(parentId)
                .workflowClass(parentClass)
                .workflowMethod("run")
                .requestContext(TestRequestContext.builder().build())
                .extraRequestData(ExtraRequestData())
                .runAsync(true) // hand it to the scheduler so the task handler retries it like any other task
                .build(),
        )
    }

    private fun awaitTerminal(workflowId: String): WorkflowInstance =
        TestHelper(engine, scheduler, workflowId)
            .waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED, WorkflowInstance.Status.ERROR))

    private fun assertParentRetriedAndCompleted(
        parentId: String,
        fault: Fault,
    ) {
        val parent = awaitTerminal(parentId)
        assertEquals(WorkflowInstance.Status.COMPLETED, parent.status, "a transient child-start failure must not fail the parent")
        assertEquals("started $CHILDREN children", parent.result.get())
        assertEquals(2, fault.hits.get(), "the parent is retried and starts the failed child again")
        for (i in 1..CHILDREN) {
            val child = awaitTerminal(childId(parentId, i))
            assertEquals(WorkflowInstance.Status.COMPLETED, child.status)
            assertEquals(parentId, child.parentWorkflowId)
        }
    }

    @Test
    fun schedulerTimeoutWhileSchedulingAChildRetriesTheParent() {
        val parentId = "fan-out-${System.nanoTime()}"
        // The child's instance commits, then inserting its task times out. The retried parent finds the
        // instance already there and schedules it again.
        val fault = Fault(childId(parentId, 2)) { TimeoutException("injected timeout inserting the child's task") }
        start(schedulerFault = fault)
        startParent(parentId)
        assertParentRetriedAndCompleted(parentId, fault)
    }

    @Test
    fun schedulerTimeoutWhileSchedulingAChildRunsTheChildWhenCreatingAnExistingWorkflowIsNoop() {
        val parentId = "fan-out-${System.nanoTime()}"
        // The gate's default. The retried start finds the child RUNNING with no task, so it schedules the
        // child, due after the lease, instead of returning it as it is.
        val fault = Fault(childId(parentId, 2)) { TimeoutException("injected timeout inserting the child's task") }
        start(schedulerFault = fault, createExistingWorkflowIsNoop = true)
        startParent(parentId)
        assertParentRetriedAndCompleted(parentId, fault)
    }

    @Test
    fun storeTimeoutWhileCreatingAChildRetriesTheParent() {
        val parentId = "fan-out-${System.nanoTime()}"
        val fault = Fault(childId(parentId, 2)) { TimeoutException("injected timeout creating the child's instance") }
        start(storeFault = fault)
        startParent(parentId)
        assertParentRetriedAndCompleted(parentId, fault)
    }

    @Test
    fun retryableErrorWhileCreatingAChildRetriesTheParent() {
        val parentId = "fan-out-${System.nanoTime()}"
        // Already a RetryableError, so it is rethrown unchanged and the executor retries the parent on it.
        val fault = Fault(childId(parentId, 2)) { OptimisticLockingError("injected conflict creating the child's instance") }
        start(storeFault = fault)
        startParent(parentId)
        assertParentRetriedAndCompleted(parentId, fault)
    }

    @Test
    fun invalidChildRequestStillFailsTheParent() {
        val parentId = "fan-out-${System.nanoTime()}"
        val fault = Fault(childId(parentId, 2)) { IllegalArgumentException("injected invalid child request") }
        start(storeFault = fault)
        startParent(parentId)

        val parent = awaitTerminal(parentId)
        assertEquals(WorkflowInstance.Status.ERROR, parent.status)
        assertEquals(1, fault.hits.get(), "an invalid request is not retried")
    }

    @Test
    fun invocationWithoutAParentStillFailsTheParentOnATransientStartFailure() {
        val parentId = "fan-out-${System.nanoTime()}"
        val fault = Fault(childId(parentId, 2)) { TimeoutException("injected timeout creating the child's instance") }
        start(storeFault = fault)
        startParent(parentId, PlainFanOutWorkflow::class.java)

        val parent = awaitTerminal(parentId)
        assertEquals(WorkflowInstance.Status.ERROR, parent.status, "only a child invocation retries the parent")
        assertEquals(1, fault.hits.get())
    }

    private companion object {
        const val CHILDREN = 3

        fun childId(
            parentId: String,
            i: Int
        ) = "$parentId-child-$i"
    }
}
