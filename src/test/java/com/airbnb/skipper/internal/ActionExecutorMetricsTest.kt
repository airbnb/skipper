package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.metrics.SkipperTimer
import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.Tracer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * [ActionExecutor] reports the same two metrics for every action, whichever way it returns: one
 * `actionExecutionResult` count and one `actionExecutionTime` observation per execution. For an action
 * that returns a future, which includes every Kotlin suspend action, both are reported when the future
 * completes, not when the method returns.
 */
class ActionExecutorMetricsTest {
    private val metrics = TaggedMetrics()
    private lateinit var mockClock: Clock
    private lateinit var actionExecutor: ActionExecutor

    @BeforeEach
    fun setup() {
        mockClock = mock(Clock::class.java)
        `when`(mockClock.instant()).thenReturn(Instant.EPOCH)
        val collector = mock(ExecutionMetricsCollector::class.java)
        val tracer = mock(Tracer::class.java)
        `when`(collector.createActionSpan(org.mockito.ArgumentMatchers.any())).thenReturn(mock(Span::class.java))
        `when`(tracer.activateSpan(org.mockito.ArgumentMatchers.any())).thenReturn(mock(Scope::class.java))
        actionExecutor =
            ActionExecutor(
                mock(WorkflowStore::class.java),
                CheckpointMode.EVENTUAL_CHECKPOINT,
                metrics,
                ActionErrorMapper(DefaultExceptionClassifier()),
                mock(EventPublisher::class.java),
                collector,
                tracer,
                ContextPropagator.NOOP,
                null,
                InFlightActions(),
            )
    }

    open class DemoActions : Actions() {
        val pending = CompletableFuture<String>()

        @Execute
        fun greet(name: String): String = "Hello $name"

        @Execute
        fun refuse(name: String): String = throw IllegalStateException("no $name")

        @Execute
        suspend fun greetSuspending(name: String): String = "Hello $name"

        @Execute
        suspend fun refuseSuspending(name: String): String = throw NonRetryableError("no $name")

        @Execute
        fun greetLater(name: String): CompletableFuture<String> = pending
    }

    @Test
    fun `a synchronous action is counted and timed once when it returns`() {
        execute("greet")

        assertThat(metrics.results())
            .containsExactly(mapOf("actionClass" to "DemoActions", "actionMethod" to "greet", "result" to "success"))
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    @Test
    fun `a synchronous action that throws is counted as an error with what it threw`() {
        assertThatThrownBy { execute("refuse") }.isInstanceOf(Exception::class.java)

        val tags = metrics.results().single()
        assertThat(tags).containsEntry("result", "error").containsEntry("cause", "IllegalStateException")
        assertThat(tags).containsKeys("actionClass", "actionMethod", "error")
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    @Test
    fun `a suspend action is counted and timed once`() {
        (execute("greetSuspending") as CompletableFuture<*>).get(5, TimeUnit.SECONDS)

        assertThat(metrics.results())
            .containsExactly(
                mapOf("actionClass" to "DemoActions", "actionMethod" to "greetSuspending", "result" to "success")
            )
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    @Test
    fun `a suspend action that throws is counted as an error with what it threw`() {
        val future = execute("refuseSuspending") as CompletableFuture<*>
        assertThatThrownBy { future.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(NonRetryableError::class.java)

        assertThat(metrics.results())
            .containsExactly(
                mapOf(
                    "actionClass" to "DemoActions",
                    "actionMethod" to "refuseSuspending",
                    "result" to "error",
                    "error" to "NonRetryableError",
                    "cause" to "NonRetryableError",
                )
            )
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    @Test
    fun `an action returning a future is counted and timed when the future completes`() {
        val actions = DemoActions()
        val future = execute("greetLater", actions) as CompletableFuture<*>

        // The method has returned but the work behind the future has not finished.
        assertThat(metrics.results()).isEmpty()
        assertThat(metrics.timerStops()).isEqualTo(0)

        actions.pending.complete("Hello World")
        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("Hello World")

        assertThat(metrics.results().single()).containsEntry("result", "success")
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    @Test
    fun `an action whose future fails is counted as an error with what the future failed with`() {
        val actions = DemoActions()
        val future = execute("greetLater", actions) as CompletableFuture<*>

        actions.pending.completeExceptionally(IllegalStateException("backend down"))
        assertThatThrownBy { future.get(5, TimeUnit.SECONDS) }.isInstanceOf(Exception::class.java)

        val tags = metrics.results().single()
        assertThat(tags).containsEntry("result", "error").containsEntry("cause", "IllegalStateException")
        assertThat(tags).containsKey("error")
        assertThat(metrics.timerStops()).isEqualTo(1)
    }

    private fun execute(
        methodName: String,
        actions: DemoActions = DemoActions()
    ): Any? {
        val method = DemoActions::class.java.declaredMethods.first { it.name == methodName }
        return actionExecutor.executeAction(
            ActionExecutor.ExecuteActionRequest
                .builder()
                .actionObject(actions)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any>("World"))
                .executionContext(
                    ExecutionContext
                        .builder()
                        .workflow(TestUtils.getWorkflowInstance())
                        .clock(mockClock)
                        .executorService(Executors.newSingleThreadExecutor())
                        .build()
                )
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        )
    }

    /** Records the tags of every `actionExecutionResult` increment and every stop of an `actionExecutionTime` timer. */
    private class TaggedMetrics : NoOpMetrics() {
        private val resultTags = CopyOnWriteArrayList<Map<String, String>>()
        private val stops = CopyOnWriteArrayList<String>()

        fun results(): List<Map<String, String>> = resultTags.toList()

        fun timerStops(): Int = stops.size

        override fun counter(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperCounter {
            if (names.last() != "actionExecutionResult") return super.counter(tags, *names)
            return object : SkipperCounter {
                override fun inc() {
                    resultTags.add(tags.toMap())
                }

                override fun inc(n: Long) = inc()
            }
        }

        override fun timer(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperTimer {
            if (names.last() != "actionExecutionTime") return super.timer(tags, *names)
            return object : SkipperTimer {
                override fun update(duration: Duration) = Unit

                override fun update(
                    duration: Long,
                    unit: TimeUnit
                ) = Unit

                override fun <T> time(event: Callable<T>): T = event.call()

                override fun time(): SkipperTimer.Context =
                    object : SkipperTimer.Context {
                        override fun stop(): Long {
                            stops.add(names.last())
                            return 0
                        }

                        override fun close() {
                            stop()
                        }
                    }
            }
        }
    }
}
