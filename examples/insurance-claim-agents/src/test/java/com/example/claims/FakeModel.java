package com.example.claims;

import java.util.EnumSet;
import java.util.Set;

/** The scripted agents, plus the ways a model misbehaves: outages, and a specialist that never answers. */
class FakeModel implements ClaimsModel {
  private final ScriptedModel scripted = new ScriptedModel();
  private final Set<Specialist> silent = EnumSet.noneOf(Specialist.class);
  private int calls;
  private int failuresLeft;

  synchronized void failNext(int times) {
    failuresLeft = times;
  }

  synchronized void silence(Specialist role) {
    silent.add(role);
  }

  synchronized int calls() {
    return calls;
  }

  @Override
  public synchronized IntakeReport intake(String description) throws ModelUnavailableException {
    countOrFail();
    return scripted.intake(description);
  }

  @Override
  public synchronized Opinion assess(Specialist role, CaseFile file, Evidence evidence) throws ModelUnavailableException {
    if (silent.contains(role)) {
      throw new ModelUnavailableException("529 overloaded", null);
    }
    countOrFail();
    return scripted.assess(role, file, evidence);
  }

  @Override
  public synchronized Decision adjudicate(AdjudicationInput input) throws ModelUnavailableException {
    countOrFail();
    return scripted.adjudicate(input);
  }

  private void countOrFail() throws ModelUnavailableException {
    if (failuresLeft > 0) {
      failuresLeft--;
      throw new ModelUnavailableException("529 overloaded", null);
    }
    calls++;
  }
}
