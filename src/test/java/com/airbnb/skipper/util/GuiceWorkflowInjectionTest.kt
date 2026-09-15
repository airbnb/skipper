package com.airbnb.skipper.util

import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.InMemoryFeatureGate
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.SkipperAnnotationNames
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.internal.ActionExecutor
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.VersionGateEnforcer
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.name.Names
import javax.inject.Provider
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * A Guice injector that binds only the engine types adopters bind today (see `SkipperInternalsModule`
 * in examples/dropwizard-mysql) can still inject a workflow. New internal dependencies must come from
 * [SkipperInternalDeps], not from a binding the adopter would have to add.
 */
class GuiceWorkflowInjectionTest {
    class SampleWorkflow : Workflow()

    @Test
    fun `workflow injects with only the engine bindings adopters already have`() {
        val actionExecutor = mock<ActionExecutor>()
        val skipperEngine = mock<SkipperEngine>()
        val retryStrategy = mock<RetryStrategy>()
        val guice =
            Guice.createInjector(
                object : AbstractModule() {
                    override fun configure() {
                        bind(ContextPropagator::class.java).toInstance(ContextPropagator.NOOP)
                        bind(ActionExecutor::class.java).toInstance(actionExecutor)
                        bind(SkipperEngine::class.java).toInstance(skipperEngine)
                        bind(RetryStrategy::class.java)
                            .annotatedWith(Names.named(SkipperAnnotationNames.DEFAULT_RETRY_STRATEGY))
                            .toInstance(retryStrategy)
                    }
                },
            )
        val injector =
            object : SkipperInjector {
                override fun <T> getInstance(clazz: Class<T>): T = guice.getInstance(clazz)

                override fun injectMembers(instance: Any) = guice.injectMembers(instance)
            }
        val internalDeps =
            SkipperInternalDeps(
                actionExecutor = actionExecutor,
                skipperEngineProvider = Provider { skipperEngine },
                contextPropagator = ContextPropagator.NOOP,
                defaultRetryStrategy = retryStrategy,
                versionGateEnforcer = VersionGateEnforcer(InMemoryFeatureGate(), NoOpMetrics()),
            )

        injector.injectWorkflowMembers(SampleWorkflow(), internalDeps)
    }
}
