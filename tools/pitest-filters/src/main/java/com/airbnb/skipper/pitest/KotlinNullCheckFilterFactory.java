package com.airbnb.skipper.pitest;

import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;

/** {@code KOTLIN_NULL_CHECKS}, on by default: see {@link KotlinNullCheckFilter}. */
public final class KotlinNullCheckFilterFactory implements MutationInterceptorFactory {

  @Override
  public String description() {
    return "Kotlin null-check filter";
  }

  @Override
  public Feature provides() {
    return Feature.named("KOTLIN_NULL_CHECKS")
        .withOnByDefault(true)
        .withDescription("Filters mutants that remove null checks the Kotlin compiler inserted");
  }

  @Override
  public MutationInterceptor createInterceptor(InterceptorParameters params) {
    return new KotlinNullCheckFilter();
  }
}
