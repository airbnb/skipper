package com.airbnb.skipper.pitest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines a change adds or modifies, read from {@code git diff --unified=0}: each hunk header
 * {@code @@ -a,b +c,d @@} names the new file's lines c to c+d-1.
 *
 * <p>PIT names a mutant's source by package and file name, not path, so a path matches when it ends
 * in {@code <package directories>/<file name>}. That holds as long as each source file sits in the
 * directory its package names, which every main source here does (diff-cover, in the coverage job,
 * relies on the same thing). A file that broke it would have its mutants dropped rather than
 * misattributed.
 */
final class ChangedLines {

  private static final Pattern HUNK =
      Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

  /** File name -> (path -> changed line numbers). */
  private final Map<String, Map<String, Set<Integer>>> byFileName;

  private ChangedLines(Map<String, Map<String, Set<Integer>>> byFileName) {
    this.byFileName = byFileName;
  }

  static ChangedLines read(Path diff) throws IOException {
    Map<String, Map<String, Set<Integer>>> byFileName = new HashMap<>();
    Set<Integer> current = null;
    for (String line : Files.readAllLines(diff, StandardCharsets.UTF_8)) {
      if (line.startsWith("+++ ")) {
        // "+++ /dev/null" is a deleted file: its hunks add nothing.
        String path = line.startsWith("+++ b/") ? line.substring("+++ b/".length()) : null;
        current =
            path == null
                ? null
                : byFileName
                    .computeIfAbsent(fileName(path), k -> new HashMap<>())
                    .computeIfAbsent(path, k -> new HashSet<>());
        continue;
      }
      Matcher hunk = HUNK.matcher(line);
      if (current != null && hunk.find()) {
        int first = Integer.parseInt(hunk.group(1));
        int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
        for (int n = first; n < first + count; n++) {
          current.add(n);
        }
      }
    }
    return new ChangedLines(byFileName);
  }

  /**
   * @param packageName the package in internal form, {@code com/airbnb/skipper}
   */
  boolean contains(String packageName, String fileName, int line) {
    String suffix = packageName.isEmpty() ? fileName : packageName + "/" + fileName;
    for (Map.Entry<String, Set<Integer>> file :
        byFileName.getOrDefault(fileName, Map.of()).entrySet()) {
      String path = file.getKey();
      if ((path.equals(suffix) || path.endsWith("/" + suffix)) && file.getValue().contains(line)) {
        return true;
      }
    }
    return false;
  }

  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
