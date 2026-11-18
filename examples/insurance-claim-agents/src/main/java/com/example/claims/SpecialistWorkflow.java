package com.example.claims;

import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;

/**
 * One specialist agent, run as a child of the claim it reviews. It looks up what it needs, forms an opinion,
 * and reports back. Being its own workflow, it has its own retries and its own place in the timeline, and a
 * failure or a slow model here does not stop the other two specialists.
 */
public class SpecialistWorkflow extends Workflow {
  private final RecordsActions records = actions(RecordsActions.class);
  private final AgentActions agent = actions(AgentActions.class);
  private final ReportActions report = actions(ReportActions.class);

  @WorkflowMethod
  public void review(SpecialistTask task) {
    Evidence evidence = records.gather(task);
    Opinion opinion = agent.assess(new Assessment(task, evidence));
    report.deliver(new OpinionReport(task.claimId(), opinion));
  }
}
