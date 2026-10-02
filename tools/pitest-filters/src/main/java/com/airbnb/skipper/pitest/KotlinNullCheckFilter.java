package com.airbnb.skipper.pitest;

import java.util.Collection;
import java.util.stream.Collectors;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Drops mutants that remove a call into {@code kotlin.jvm.internal.Intrinsics}: the null checks the
 * Kotlin compiler inserts on parameters and on values from Java. Removing one changes nothing a
 * Kotlin caller can observe, so the mutant survives every test and says nothing about them. PIT
 * itself only knows to skip Kotlin's line-0 synthetics; the rest is in the commercial Arcmutate
 * plugin.
 */
final class KotlinNullCheckFilter implements MutationInterceptor {

  private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics::";

  @Override
  public InterceptorType type() {
    return InterceptorType.PRE_SCAN_FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {}

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater mutater) {
    return mutations.stream()
        .filter(m -> !m.getDescription().contains(INTRINSICS))
        .collect(Collectors.toList());
  }

  @Override
  public void end() {}
}
