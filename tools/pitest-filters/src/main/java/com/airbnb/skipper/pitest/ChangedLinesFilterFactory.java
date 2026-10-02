package com.airbnb.skipper.pitest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import org.pitest.mutationtest.build.CompoundMutationInterceptor;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;
import org.pitest.plugin.FeatureParameter;

/**
 * {@code +CHANGED_LINES(file[<path>])}: keeps only the mutants on the lines listed in the file (see
 * {@link ChangedLines} for its format). Off unless named, so a plain {@code ./gradlew pitest} still
 * mutates every line.
 */
public final class ChangedLinesFilterFactory implements MutationInterceptorFactory {

  private static final FeatureParameter FILE =
      FeatureParameter.named("file").withDescription("Path of the changed-lines file");

  @Override
  public String description() {
    return "Changed lines filter";
  }

  @Override
  public Feature provides() {
    return Feature.named("CHANGED_LINES")
        .withOnByDefault(false)
        .withDescription("Mutates only the lines a change touches")
        .withParameter(FILE);
  }

  @Override
  public MutationInterceptor createInterceptor(InterceptorParameters params) {
    return params
        .getString(FILE)
        .<MutationInterceptor>map(
            file -> {
              try {
                return new ChangedLinesFilter(ChangedLines.read(Paths.get(file)));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            })
        .orElseGet(CompoundMutationInterceptor::nullInterceptor);
  }
}
