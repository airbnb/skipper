package com.example.trips;

import java.util.ArrayList;
import java.util.List;

/**
 * What the planner proposed, what the validator refused and why, and what sold out. A named type rather than
 * a bare list because Skipper does not persist a top-level generic as workflow state.
 */
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
