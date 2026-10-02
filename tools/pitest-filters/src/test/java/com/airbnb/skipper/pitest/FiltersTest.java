package com.airbnb.skipper.pitest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.plugin.FeatureSetting;
import org.pitest.plugin.ToggleStatus;

class FiltersTest {

  @TempDir Path dir;

  @Test
  void changedLinesKeepsOnlyListedLinesOfListedFiles() throws IOException {
    MutationInterceptor filter =
        changedLinesFilter("com/airbnb/skipper/internal WorkflowExecutor.kt 41 42\n");

    MutationDetails onChangedLine =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 41);
    MutationDetails onNestedClass =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor$Run", "WorkflowExecutor.kt", 42);
    MutationDetails otherLine =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 43);
    MutationDetails otherFile =
        mutant("com.airbnb.skipper.internal.ActionExecutor", "ActionExecutor.kt", 41);
    MutationDetails otherPackage =
        mutant("com.airbnb.skipper.WorkflowExecutor", "WorkflowExecutor.kt", 41);

    assertThat(filter.type()).isEqualTo(InterceptorType.PRE_SCAN_FILTER);
    assertThat(intercept(filter, onChangedLine, onNestedClass, otherLine, otherFile, otherPackage))
        .containsExactly(onChangedLine, onNestedClass);
  }

  @Test
  void changedLinesSkipsRowsWithoutLineNumbers() throws IOException {
    MutationInterceptor filter =
        changedLinesFilter("com/airbnb/skipper Foo.kt\n\ncom/airbnb/skipper Bar.kt 7\n");

    MutationDetails foo = mutant("com.airbnb.skipper.Foo", "Foo.kt", 7);
    MutationDetails bar = mutant("com.airbnb.skipper.Bar", "Bar.kt", 7);

    assertThat(intercept(filter, foo, bar)).containsExactly(bar);
  }

  @Test
  void changedLinesFeatureIsOffUntilNamed() {
    ChangedLinesFilterFactory factory = new ChangedLinesFilterFactory();

    assertThat(factory.provides().name()).isEqualToIgnoringCase("CHANGED_LINES");
    assertThat(factory.provides().isOnByDefault()).isFalse();
    assertThat(factory.description()).isNotBlank();
    // Unnamed, the factory hands PIT an interceptor that keeps every mutant.
    MutationDetails anyMutant = mutant("com.airbnb.skipper.Foo", "Foo.kt", 7);
    assertThat(intercept(factory.createInterceptor(params(null)), anyMutant))
        .containsExactly(anyMutant);
  }

  @Test
  void changedLinesFeatureFailsOnAMissingFile() {
    FeatureSetting setting =
        new FeatureSetting(
            "CHANGED_LINES",
            ToggleStatus.ACTIVATE,
            Map.of("file", List.of(dir.resolve("missing").toString())));

    assertThatThrownBy(() -> new ChangedLinesFilterFactory().createInterceptor(params(setting)))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void kotlinNullCheckFilterDropsOnlyIntrinsicsCalls() {
    MutationInterceptor filter = new KotlinNullCheckFilterFactory().createInterceptor(params(null));

    MutationDetails nullCheck =
        mutant("com.airbnb.skipper.Foo", "Foo.kt", 3)
            .withDescription(
                "removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue");
    MutationDetails realCall =
        mutant("com.airbnb.skipper.Foo", "Foo.kt", 3)
            .withDescription("removed call to com/airbnb/skipper/Store::save");

    assertThat(filter.type()).isEqualTo(InterceptorType.PRE_SCAN_FILTER);
    // MutationDetails equality is by mutant id alone, and these two share one: compare what each
    // mutant does instead.
    assertThat(intercept(filter, nullCheck, realCall))
        .extracting(MutationDetails::getDescription)
        .containsExactly("removed call to com/airbnb/skipper/Store::save");
  }

  @Test
  void kotlinNullCheckFeatureIsOnByDefault() {
    KotlinNullCheckFilterFactory factory = new KotlinNullCheckFilterFactory();

    assertThat(factory.provides().name()).isEqualToIgnoringCase("KOTLIN_NULL_CHECKS");
    assertThat(factory.provides().isOnByDefault()).isTrue();
    assertThat(factory.description()).isNotBlank();
  }

  private MutationInterceptor changedLinesFilter(String contents) throws IOException {
    Path file = dir.resolve("changed-lines");
    Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
    FeatureSetting setting =
        new FeatureSetting(
            "CHANGED_LINES", ToggleStatus.ACTIVATE, Map.of("file", List.of(file.toString())));
    return new ChangedLinesFilterFactory().createInterceptor(params(setting));
  }

  private static InterceptorParameters params(FeatureSetting setting) {
    return new InterceptorParameters(setting, null, null, null, null, null, null);
  }

  private static Collection<MutationDetails> intercept(
      MutationInterceptor filter, MutationDetails... mutants) {
    filter.begin(null);
    Collection<MutationDetails> kept = filter.intercept(Arrays.asList(mutants), null);
    filter.end();
    return kept;
  }

  private static MutationDetails mutant(String className, String file, int line) {
    Location location = Location.location(ClassName.fromString(className), "run", "()V");
    return new MutationDetails(
        new MutationIdentifier(location, 0, "mutator"),
        file,
        "a mutant",
        line,
        Collections.emptyList());
  }
}
