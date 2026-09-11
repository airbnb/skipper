package com.example.orders.skipper;

import com.airbnb.skipper.SkipperInjector;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

/**
 * Bridges Spring's container to Skipper's two-method injector interface, so workflows, actions and callback
 * handlers can depend on Spring beans.
 *
 * <p>Skipper asks for a fresh instance of a Workflow or callback-handler class per invocation, and separately
 * asks to inject the members of Actions instances it has already created as recording proxies. Spring's
 * {@code createBean} covers the first (constructor injection included); {@code autowireBean} covers the second,
 * filling {@code @Autowired} fields on an object Spring did not construct.
 */
public final class SpringSkipperInjector implements SkipperInjector {
  private final AutowireCapableBeanFactory beans;

  public SpringSkipperInjector(AutowireCapableBeanFactory beans) {
    this.beans = beans;
  }

  @Override
  public <T> T getInstance(Class<T> clazz) {
    return beans.createBean(clazz);
  }

  @Override
  public void injectMembers(Object instance) {
    beans.autowireBean(instance);
  }
}
