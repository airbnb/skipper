package com.example.orders.skipper;

import com.airbnb.skipper.ContextPropagator;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.SkipperAnnotationNames;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.ActionExecutor;
import com.airbnb.skipper.internal.SkipperEngine;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Named;

/**
 * Bindings Guice needs before it can build a Workflow or fill an Actions instance.
 *
 * <p>Skipper's Workflow and Actions base classes declare {@code @Inject} fields for a few engine components.
 * Skipper's own SimpleInjector ignores fields it has no binding for and the engine fills them itself right after
 * injection. Guice is stricter: an {@code @Inject} field with no binding is an error, and with no binding it will
 * even try to construct the internal type from its constructor, which fails with a long list of missing engine
 * bindings. So hand Guice the runtime's own instances. Everything here is lazy through the SkipperRuntime
 * provider, which is what makes the cycle (runtime needs the injector, the injector needs the runtime) resolve.
 */
public final class SkipperInternalsModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(ContextPropagator.class).toInstance(ContextPropagator.NOOP);
  }

  @Provides
  ActionExecutor actionExecutor(SkipperRuntime runtime) {
    return runtime.getActionExecutor().get();
  }

  @Provides
  SkipperEngine skipperEngine(SkipperRuntime runtime) {
    return runtime.getSkipperEngine().get();
  }

  @Provides
  @Named(SkipperAnnotationNames.DEFAULT_RETRY_STRATEGY)
  RetryStrategy defaultRetryStrategy(SkipperRuntime runtime) {
    return runtime.getConfig().getDefaultRetryStrategy();
  }
}
