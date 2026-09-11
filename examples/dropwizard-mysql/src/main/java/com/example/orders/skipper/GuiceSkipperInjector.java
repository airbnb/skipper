package com.example.orders.skipper;

import com.airbnb.skipper.SkipperInjector;
import com.google.inject.Injector;

/**
 * Bridges Guice to Skipper's two-method injector interface, so workflows, actions and callback handlers can
 * depend on Guice-managed objects.
 *
 * <p>Skipper asks for a fresh instance of a Workflow or callback-handler class per invocation; Guice's
 * just-in-time bindings construct any concrete class, constructor injection included. Skipper separately asks
 * to inject the members of Actions instances it created itself as recording proxies; {@code injectMembers}
 * fills their {@code @Inject} fields.
 */
public final class GuiceSkipperInjector implements SkipperInjector {
  private final Injector guice;

  public GuiceSkipperInjector(Injector guice) {
    this.guice = guice;
  }

  @Override
  public <T> T getInstance(Class<T> clazz) {
    return guice.getInstance(clazz);
  }

  @Override
  public void injectMembers(Object instance) {
    guice.injectMembers(instance);
  }
}
