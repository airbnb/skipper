package com.airbnb.skipper.pitest;

import java.util.Collection;
import java.util.stream.Collectors;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Drops every mutant outside the changed lines. A pre-scan filter, so the dropped mutants are never
 * planned, let alone run: in the engine's core classes a surviving mutant reruns hundreds of
 * end-to-end tests, and a change touches a small part of each class it edits.
 */
final class ChangedLinesFilter implements MutationInterceptor {

  private final ChangedLines changed;

  ChangedLinesFilter(ChangedLines changed) {
    this.changed = changed;
  }

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
        .filter(
            m ->
                changed.contains(
                    m.getClassName().getPackage().asInternalName(),
                    m.getFilename(),
                    m.getLineNumber()))
        .collect(Collectors.toList());
  }

  @Override
  public void end() {}
}
