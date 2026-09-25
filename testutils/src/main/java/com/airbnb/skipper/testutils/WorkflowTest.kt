package com.airbnb.skipper.testutils

import com.airbnb.skipper.IWorkflowFactory
import com.airbnb.skipper.InvocationBuilder
import com.airbnb.skipper.SimpleInjector
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.factory.SkipperRuntime
import com.airbnb.skipper.testutils.trace.Tracing
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for workflow tests. Extend it, write a test, and Skipper is running.
 *
 * ```kotlin
 * class OrderWorkflowTest : WorkflowTest() {
 *   @Bind val payments: PaymentService = mock()
 *
 *   @Test
 *   fun happyPath() {
 *     val workflow = workflowBuilder<OrderWorkflow>().build()
 *     workflow.processOrder(request)
 *     helper.waitForWorkflowToComplete()
 *     assertEquals("completed", workflow.processOrder(request).status)
 *   }
 * }
 * ```
 *
 * Each test gets its own [SkipperRuntime] on the embedded **in-memory SQLite** store, so there is
 * no database to provision and no state shared between tests. Time is a [MutableClock] over real
 * time that the test can jump ahead: short retry delays elapse on their own, while a long `sleep`,
 * a `waitUntil` deadline or a compensation backoff is reached with `clock.fastForward(...)` or
 * `helper.fastForwardUntilWorkflowCompletes()`. Override [createClock] for a clock fixed at the
 * epoch, which only moves when the test says so. Every workflow started through [workflowBuilder]
 * or [workflow] uses [workflowId], unique per test, and [helper] waits on that instance.
 *
 * Collaborators your actions `@Inject` are supplied through fields annotated with [Bind].
 * Override [configure] to change anything else on the [SkipperConfig] before the runtime starts.
 *
 * Works from Java as well: `public class MyTest extends WorkflowTest`, then
 * `workflowBuilder(MyWorkflow.class)` and the `helper` field.
 */
abstract class WorkflowTest {
    /** The clock Skipper runs on, from [createClock]; jump it ahead with `fastForward`. */
    protected lateinit var clock: MutableClock

    // lateinit properties expose their backing field to Java with the property's visibility, so
    // these read as plain protected fields from both languages once setUpWorkflowTest has run.

    /** The id of the workflow instance this test drives; unique per test. */
    protected lateinit var workflowId: String

    protected lateinit var config: SkipperConfig

    protected lateinit var runtime: SkipperRuntime

    protected lateinit var workflowFactory: IWorkflowFactory

    /** Wait-and-assert helpers bound to [workflowId]. */
    protected lateinit var helper: WorkflowTestHelper

    /** Hook to adjust the config (retry strategy, checkpoint mode, ...) before the runtime is built. */
    protected open fun configure(config: SkipperConfig) {}

    /**
     * The clock the runtime runs on. Ticks with real time by default, so retry delays of
     * milliseconds pass without help; return `MutableClock()` for time fixed at the epoch that moves
     * only on `fastForward`.
     */
    protected open fun createClock(): MutableClock = MutableClock(Clock.systemUTC())

    @BeforeEach
    fun setUpWorkflowTest() {
        workflowId = "wf-" + UUID.randomUUID()
        clock = createClock()
        val config = SkipperConfig.forService("workflow-test")
        config.utcClock = clock
        config.gracefulShutdownTimeout = Duration.ofSeconds(1)
        config.injector = injectorFromBoundFields()
        configure(config)
        Tracing.installIfRequested(config)
        this.config = config
        runtime = SkipperRuntime(config)
        workflowFactory = runtime.workflowFactory.get()
        helper = WorkflowTestHelper(runtime, workflowId, clock = clock)
        runtime.skipperSchedulerManager.get().start()
    }

    @AfterEach
    fun tearDownWorkflowTest() {
        if (::runtime.isInitialized) runtime.skipperSchedulerManager.get().forceStop()
    }

    /** An [InvocationBuilder] for [workflowClass] bound to this test's [workflowId]. */
    fun <T : Workflow> workflowBuilder(workflowClass: Class<T>): InvocationBuilder<T> = workflowFactory.builder(workflowClass, workflowId)

    /** A handle on this test's workflow instance, for signals, queries, or reading the result. */
    fun <T : Workflow> workflow(workflowClass: Class<T>): T = workflowFactory.invoke(workflowClass, workflowId)

    /** Prints the instance's status, state and action checkpoints; handy when a wait times out. */
    fun printHistory() {
        val view = helper.currentView()
        println(
            "workflow ${view.id} (${view.workflowClass}.${view.workflowMethod}) status=${view.status} state=${view.state}",
        )
        view.actionCheckpoints.forEach { println("  checkpoint: $it") }
    }

    /**
     * Builds the injector Skipper instantiates workflows, actions and callback handlers with: every
     * `@Bind` field on this test (and its superclasses) becomes a binding for `@Inject` fields of the
     * same type. Anything not bound is created through its no-arg constructor, as usual.
     */
    private fun injectorFromBoundFields(): SkipperInjector {
        val builder = SimpleInjector.builder()
        var clazz: Class<*>? = javaClass
        while (clazz != null && clazz != WorkflowTest::class.java) {
            for (field in clazz.declaredFields) {
                val bind = field.getAnnotation(Bind::class.java) ?: continue
                check(!Modifier.isStatic(field.modifiers)) { "@Bind field ${field.name} must not be static" }
                field.isAccessible = true
                val value =
                    checkNotNull(field.get(this)) {
                        "@Bind field ${field.name} is null; initialise it where it is declared"
                    }
                @Suppress("UNCHECKED_CAST")
                val type = (if (bind.to == Void::class) field.type else bind.to.java) as Class<Any>
                if (bind.qualifier.isEmpty()) builder.bind(type, value) else builder.bind(type, bind.qualifier, value)
            }
            clazz = clazz.superclass
        }
        return builder.build()
    }
}
