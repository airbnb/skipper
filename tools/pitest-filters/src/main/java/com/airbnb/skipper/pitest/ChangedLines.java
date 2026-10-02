package com.airbnb.skipper.pitest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The lines a change touches, per source file. A source file is named the way PIT names it: its
 * package in internal form plus its file name, so a Kotlin file whose directory does not match its
 * package still lines up.
 *
 * <p>The file scripts/diff-quality.py writes has one source file per line, fields separated by
 * spaces: {@code com/airbnb/skipper/internal WorkflowExecutor.kt 41 42 97}.
 */
final class ChangedLines {

  private final Map<String, Set<Integer>> lines;

  private ChangedLines(Map<String, Set<Integer>> lines) {
    this.lines = lines;
  }

  static ChangedLines read(Path file) throws IOException {
    Map<String, Set<Integer>> lines = new HashMap<>();
    for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String[] fields = row.trim().split("\\s+");
      if (fields.length < 3) {
        continue;
      }
      Set<Integer> numbers = lines.computeIfAbsent(key(fields[0], fields[1]), k -> new HashSet<>());
      for (int i = 2; i < fields.length; i++) {
        numbers.add(Integer.parseInt(fields[i]));
      }
    }
    return new ChangedLines(lines);
  }

  boolean contains(String packageName, String fileName, int line) {
    Set<Integer> numbers = lines.get(key(packageName, fileName));
    return numbers != null && numbers.contains(line);
  }

  private static String key(String packageName, String fileName) {
    return packageName + "/" + fileName;
  }
}
