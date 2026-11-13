package com.example.trips;

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
 * The playground: a page for starting trips and answering their proposals, and the small JSON API behind it.
 * Everything here is what any client of a Skipper workflow does. It starts an instance without waiting for
 * it, reads its progress through {@code @QueryMethod}s, and delivers the traveler's decision as a signal.
 *
 * <p>It also exposes the fake provider's switches, so an offer can be sold out between the proposal and the
 * approval to watch the agent replan.
 */
@Path("/")
public class TripResource {
  private final IWorkflowFactory factory;
  private final InMemoryTravelProvider provider;
  private final String plannerName;

  public TripResource(IWorkflowFactory factory, InMemoryTravelProvider provider, String plannerName) {
    this.factory = factory;
    this.provider = provider;
    this.plannerName = plannerName;
  }

  public record NewTrip(String traveler, String request, long budgetCents) {}

  public record Decision(boolean approve) {}

  public record SoldOut(boolean soldOut) {}

  public record TripView(
      String id, String status, String stage, TripRequest request, Itinerary proposal, List<String> journal, TripResult result) {}

  public record OfferView(Offer offer, boolean soldOut) {}

  public record ProviderView(String planner, List<OfferView> offers, List<String> ledger) {}

  @GET
  @Produces(MediaType.TEXT_HTML)
  public InputStream page() {
    return TripResource.class.getResourceAsStream("playground.html");
  }

  @POST
  @Path("api/trips")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public TripView start(NewTrip trip) {
    if (trip == null || trip.request() == null || trip.request().isBlank() || trip.budgetCents() <= 0) {
      throw new WebApplicationException("a trip needs a request and a positive budget", Response.Status.BAD_REQUEST);
    }
    String id = "trip-" + UUID.randomUUID().toString().substring(0, 8);
    String traveler = trip.traveler() == null || trip.traveler().isBlank() ? "guest" : trip.traveler();
    // The returned future is dropped on purpose: the instance parks for approval, and a parked instance
    // does not complete it. The page reads progress through the queries below instead.
    factory.builder(TripBookingWorkflow.class, id).build()
        .bookTrip(new TripRequest(id, traveler, trip.request().trim(), trip.budgetCents()));
    return new TripView(id, "CREATED", "NEW", null, null, List.of(), null);
  }

  @GET
  @Path("api/trips/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  public TripView trip(@PathParam("id") String id) {
    TripBookingWorkflow trip = find(id);
    return new TripView(
        id,
        trip.getWorkflowInstanceView().getStatus().name(),
        trip.stage(),
        trip.request(),
        trip.proposal(),
        trip.journal().lines(),
        trip.result());
  }

  @POST
  @Path("api/trips/{id}/decision")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response decide(@PathParam("id") String id, Decision decision) {
    TripBookingWorkflow trip = find(id);
    if (!"AWAITING_APPROVAL".equals(trip.stage())) {
      throw new WebApplicationException(id + " is not waiting for a decision", Response.Status.CONFLICT);
    }
    trip.decide(decision.approve());
    return Response.noContent().build();
  }

  @GET
  @Path("api/provider")
  @Produces(MediaType.APPLICATION_JSON)
  public ProviderView provider() {
    List<OfferView> offers = new ArrayList<>();
    for (Offer offer : provider.catalog()) {
      offers.add(new OfferView(offer, provider.isSoldOut(offer.id())));
    }
    return new ProviderView(plannerName, offers, provider.ledger());
  }

  @POST
  @Path("api/provider/offers/{offerId}/sold-out")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response sellOut(@PathParam("offerId") String offerId, SoldOut body) {
    if (body.soldOut()) {
      provider.sellOut(offerId);
    } else {
      provider.restock(offerId);
    }
    return Response.noContent().build();
  }

  private TripBookingWorkflow find(String id) {
    try {
      TripBookingWorkflow trip = factory.invoke(TripBookingWorkflow.class, id);
      trip.getWorkflowInstanceView();
      return trip;
    } catch (RuntimeException e) {
      throw new WebApplicationException("no trip " + id, Response.Status.NOT_FOUND);
    }
  }
}
