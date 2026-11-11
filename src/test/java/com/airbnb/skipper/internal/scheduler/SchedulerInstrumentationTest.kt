package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** A runtime builds its in-memory queue and task handlers through its [SchedulerInstrumentation]. */
class SchedulerInstrumentationTest {
    @Test
    fun testRuntimeBuildsItsQueueAndHandlersThroughTheInstrumentation() {
        val observing =
            object : SchedulerInstrumentation {
                var queue: SchedulerExecutionQueue? = null
                val handlers = ArrayList<TaskHandler>()
                val managers = ArrayList<SkipperSchedulerManager>()

                override fun executionQueue(
                    scheduler: Scheduler,
                    clock: Clock,
                    leaseDuration: Duration,
                ): SchedulerExecutionQueue = SchedulerExecutionQueue(scheduler, clock, leaseDuration).also { queue = it }

                override fun taskHandler(handler: TaskHandler): TaskHandler = handler.also { handlers.add(it) }

                override fun schedulerManager(manager: SkipperSchedulerManager): SkipperSchedulerManager = manager.also { managers.add(it) }
            }
        val deps = TestRuntime()
        deps.config.schedulerInstrumentation = observing
        val runtime = deps.runtime

        val manager = runtime.skipperSchedulerManager.get()

        assertSame(observing.queue, runtime.schedulerExecutionQueue.get())
        assertEquals(Task.Type.values().size, observing.handlers.size, "one handler per task type")
        assertEquals(listOf(manager), observing.managers)
    }

    @Test
    fun testByDefaultTheQueueAndHandlersAreBuiltAsTheyAre() {
        val scheduler = TestRuntime().scheduler
        val handler =
            object : TaskHandler {
                override fun handle(
                    task: Task<*>,
                    executorService: ExecutorService,
                ): CompletableFuture<Option<Instant>> = throw UnsupportedOperationException()
            }

        val queue = SchedulerInstrumentation.None.executionQueue(scheduler, Clock.systemUTC(), Duration.ofMinutes(8))

        assertEquals(SchedulerExecutionQueue::class.java, queue.javaClass)
        assertEquals(Duration.ofMinutes(8), queue.leaseDuration)
        assertSame(handler, SchedulerInstrumentation.None.taskHandler(handler))
        val manager = TestRuntime().schedulerManager
        assertSame(manager, SchedulerInstrumentation.None.schedulerManager(manager))
    }
}
