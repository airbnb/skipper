package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.factory.SkipperRuntime
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

                override fun executionQueue(
                    scheduler: Scheduler,
                    clock: Clock,
                    leaseDuration: Duration,
                ): SchedulerExecutionQueue = SchedulerExecutionQueue(scheduler, clock, leaseDuration).also { queue = it }

                override fun taskHandler(handler: TaskHandler): TaskHandler = handler.also { handlers.add(it) }
            }
        val config = TestRuntime().config
        val runtime = SkipperRuntime(config, config.injector, observing)

        runtime.skipperSchedulerManager.get()

        assertSame(observing.queue, runtime.schedulerExecutionQueue.get())
        assertEquals(Task.Type.values().size, observing.handlers.size, "one handler per task type")
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
    }
}
