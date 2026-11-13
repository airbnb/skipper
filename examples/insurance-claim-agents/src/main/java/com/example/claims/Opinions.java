package com.example.claims;

import java.util.ArrayList;
import java.util.List;

/** The opinions received so far. A named type because Skipper does not persist a bare list as workflow state. */
public record Opinions(List<Opinion> items) {
  public static Opinions none() {
    return new Opinions(List.of());
  }

  /** At most one opinion per specialist: a repeated delivery of the same report changes nothing. */
  public Opinions with(Opinion opinion) {
    if (of(opinion.role()) != null) {
      return this;
    }
    List<Opinion> extended = new ArrayList<>(items);
    extended.add(opinion);
    return new Opinions(extended);
  }

  /** The same opinions in a fixed order, so that what later steps see does not depend on who answered first. */
  public Opinions inRoleOrder() {
    List<Opinion> ordered = new ArrayList<>();
    for (Specialist role : Specialist.values()) {
      Opinion opinion = of(role);
      if (opinion != null) ordered.add(opinion);
    }
    return new Opinions(ordered);
  }

  public Opinion of(Specialist role) {
    return items.stream().filter(o -> o.role() == role).findFirst().orElse(null);
  }
}
