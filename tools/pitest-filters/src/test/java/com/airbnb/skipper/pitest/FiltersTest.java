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
  void changedLinesKeepsOnlyAddedOrModifiedLinesOfChangedFiles() throws IOException {
    MutationInterceptor filter =
        changedLinesFilter(
            "diff --git a/src/main/java/com/airbnb/skipper/internal/WorkflowExecutor.kt"
                + " b/src/main/java/com/airbnb/skipper/internal/WorkflowExecutor.kt\n"
                + "--- a/src/main/java/com/airbnb/skipper/internal/WorkflowExecutor.kt\n"
                + "+++ b/src/main/java/com/airbnb/skipper/internal/WorkflowExecutor.kt\n"
                + "@@ -40,0 +41,2 @@ class WorkflowExecutor\n"
                + "+        val a = 1\n"
                + "+        val b = 2\n"
                + "@@ -90 +97 @@\n"
                + "-        old()\n"
                + "+        new()\n"
                + "@@ -120,3 +126,0 @@\n"
                + "-        gone()\n");

    MutationDetails added =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 41);
    MutationDetails lastAdded =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor$Run", "WorkflowExecutor.kt", 42);
    MutationDetails modified =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 97);
    MutationDetails pastTheHunk =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 43);
    MutationDetails atADeletion =
        mutant("com.airbnb.skipper.internal.WorkflowExecutor", "WorkflowExecutor.kt", 126);
    MutationDetails otherFile =
        mutant("com.airbnb.skipper.internal.ActionExecutor", "ActionExecutor.kt", 41);
    MutationDetails otherPackage =
        mutant("com.airbnb.skipper.WorkflowExecutor", "WorkflowExecutor.kt", 41);

    assertThat(filter.type()).isEqualTo(InterceptorType.PRE_SCAN_FILTER);
    assertThat(
            intercept(
                filter,
                added,
                lastAdded,
                modified,
                pastTheHunk,
                atADeletion,
                otherFile,
                otherPackage))
        .containsExactly(added, lastAdded, modified);
  }

  @Test
  void changedLinesIgnoresDeletedFiles() throws IOException {
    MutationInterceptor filter =
        changedLinesFilter(
            "--- a/src/main/java/com/airbnb/skipper/Foo.kt\n"
                + "+++ /dev/null\n"
                + "@@ -1,3 +0,0 @@\n"
                + "--- a/src/main/java/com/airbnb/skipper/Bar.kt\n"
                + "+++ b/src/main/java/com/airbnb/skipper/Bar.kt\n"
                + "@@ -6,0 +7 @@\n");

    MutationDetails foo = mutant("com.airbnb.skipper.Foo", "Foo.kt", 1);
    MutationDetails bar = mutant("com.airbnb.skipper.Bar", "Bar.kt", 7);

    assertThat(intercept(filter, foo, bar)).containsExactly(bar);
  }

  @Test
  void changedLinesMatchesTheDefaultPackage() throws IOException {
    MutationInterceptor filter = changedLinesFilter("+++ b/Top.java\n@@ -1 +1 @@\n");

    MutationDetails top = mutant("Top", "Top.java", 1);

    assertThat(intercept(filter, top)).containsExactly(top);
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
            Map.of("diff", List.of(dir.resolve("missing").toString())));

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

  private MutationInterceptor changedLinesFilter(String diff) throws IOException {
    Path file = dir.resolve("changes.diff");
    Files.write(file, diff.getBytes(StandardCharsets.UTF_8));
    FeatureSetting setting =
        new FeatureSetting(
            "CHANGED_LINES", ToggleStatus.ACTIVATE, Map.of("diff", List.of(file.toString())));
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
