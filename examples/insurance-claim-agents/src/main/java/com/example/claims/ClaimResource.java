package com.example.claims;

import com.airbnb.skipper.IWorkflowFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * The claims page and the small JSON API behind it. Everything here is what any client of a Skipper workflow
 * does: it starts an instance without waiting for it, reads its progress through a {@code @QueryMethod}, and
 * delivers the claimant's reply and the adjuster's decision as signals.
 */
@Path("/")
public class ClaimResource {
  private final IWorkflowFactory factory;
  private final InMemoryClaimRecords records;
  private final String agents;

  public ClaimResource(IWorkflowFactory factory, InMemoryClaimRecords records, String agents) {
    this.factory = factory;
    this.records = records;
    this.agents = agents;
  }

  public record NewClaim(String policyNumber, String description, long claimedCents) {}

  public record Reply(String text) {}

  public record Started(String id) {}

  public record ClaimView(String id, String status, ClaimSnapshot claim) {}

  public record PolicyHolder(String policyNumber, String name) {}

  public record Setup(String agents, List<PolicyHolder> holders, List<Samples.Sample> samples) {}

  @GET
  @Produces(MediaType.TEXT_HTML)
  public InputStream page() {
    return ClaimResource.class.getResourceAsStream("claims.html");
  }

  @GET
  @Path("api/setup")
  @Produces(MediaType.APPLICATION_JSON)
  public Setup setup() {
    List<PolicyHolder> holders = new ArrayList<>();
    for (Policy policy : records.policies()) {
      holders.add(new PolicyHolder(policy.number(), policy.holder()));
    }
    return new Setup(agents, holders, Samples.ALL);
  }

  @POST
  @Path("api/claims")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Started start(NewClaim claim) {
    if (claim == null || claim.description() == null || claim.description().isBlank() || claim.claimedCents() <= 0) {
      throw refuse(Response.Status.BAD_REQUEST, "Please describe what happened and give an amount.");
    }
    String id = "claim-" + UUID.randomUUID().toString().substring(0, 8);
    // The returned future is dropped on purpose: a claim can park for days, and a parked instance does not
    // complete it. The page reads progress through the query below instead.
    factory.builder(ClaimWorkflow.class, id).build()
        .handle(new ClaimRequest(id, claim.policyNumber(), claim.description().trim(), claim.claimedCents()));
    return new Started(id);
  }

  @GET
  @Path("api/claims/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public ClaimView claim(@PathParam("id") String id) {
    ClaimWorkflow claim = find(id);
    return new ClaimView(id, claim.getWorkflowInstanceView().getStatus().name(), claim.snapshot());
  }

  @POST
  @Path("api/claims/{id}/info")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response reply(@PathParam("id") String id, Reply reply) {
    ClaimWorkflow claim = find(id);
    if (!"NEEDS_INFO".equals(claim.snapshot().stage())) {
      throw refuse(Response.Status.CONFLICT, "This claim is not waiting for more information.");
    }
    if (reply == null || reply.text() == null || reply.text().isBlank()) {
      throw refuse(Response.Status.BAD_REQUEST, "Please type an answer.");
    }
    claim.provideInfo(reply.text().trim());
    return Response.noContent().build();
  }

  @POST
  @Path("api/claims/{id}/adjuster")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response adjuster(@PathParam("id") String id, AdjusterDecision decision) {
    ClaimWorkflow claim = find(id);
    // Asked first so the adjuster is told why; the workflow applies the same check when the signal arrives.
    List<String> problems = claim.adjusterCheck(decision).lines();
    if (!problems.isEmpty()) {
      throw refuse(Response.Status.CONFLICT, "That decision cannot be accepted: " + String.join("; ", problems) + ".");
    }
    claim.adjusterDecides(decision);
    return Response.noContent().build();
  }

  private ClaimWorkflow find(String id) {
    try {
      ClaimWorkflow claim = factory.invoke(ClaimWorkflow.class, id);
      claim.getWorkflowInstanceView();
      return claim;
    } catch (RuntimeException e) {
      throw refuse(Response.Status.NOT_FOUND, "There is no claim " + id + ".");
    }
  }

  private static WebApplicationException refuse(Response.Status status, String message) {
    return new WebApplicationException(Response.status(status).entity(message).type(MediaType.TEXT_PLAIN).build());
  }
}
