package com.example.claims;

import java.util.ArrayList;
import java.util.List;

/** A list of plain-language lines. A named type because Skipper does not persist a bare list as workflow state. */
public record Journal(List<String> lines) {
  public static Journal empty() {
    return new Journal(List.of());
  }

  public Journal with(String line) {
    List<String> extended = new ArrayList<>(lines);
    extended.add(line);
    return new Journal(extended);
  }
}
