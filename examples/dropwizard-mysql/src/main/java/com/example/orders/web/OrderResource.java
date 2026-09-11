package com.example.orders.web;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.api.WorkflowInstanceView;
import com.example.orders.OrderCallbackHandler;
import com.example.orders.OrderRequest;
import com.example.orders.OrderResult;
import com.example.orders.OrderWorkflow;
import java.net.URI;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * The HTTP face of the workflow. Placing an order starts a workflow instance keyed by the order id and returns
 * at once; the instance's status and outcome are read back by id; approval is a signal.
 */
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {
  private final IWorkflowFactory workflows;

  @Inject
  public OrderResource(IWorkflowFactory workflows) {
    this.workflows = workflows;
  }

  @POST
  public Response place(OrderRequest order) {
    // runAsync hands the instance to the persistent scheduler (any replica may run it); detached returns as
    // soon as it is persisted instead of waiting for a result the request thread should not hold out for.
    workflows.builder(OrderWorkflow.class, order.getOrderId())
        .callbackHandler(OrderCallbackHandler.class)
        .runAsync()
        .detached()
        .build()
        .placeOrder(order);
    return Response.accepted(status(order.getOrderId()))
        .location(URI.create("/orders/" + order.getOrderId()))
        .build();
  }

  @GET
  @Path("/{orderId}")
  public OrderStatus get(@PathParam("orderId") String orderId) {
    try {
      return status(orderId);
    } catch (IllegalArgumentException unknownInstance) {
      throw new NotFoundException("no order " + orderId);
    }
  }

  @POST
  @Path("/{orderId}/approve")
  public OrderStatus approve(@PathParam("orderId") String orderId, @QueryParam("decision") boolean decision) {
    workflows.invoke(OrderWorkflow.class, orderId).approve(decision);
    return status(orderId);
  }

  private OrderStatus status(String orderId) {
    OrderWorkflow workflow = workflows.invoke(OrderWorkflow.class, orderId);
    WorkflowInstanceView view = workflow.getWorkflowInstanceView();
    return new OrderStatus(orderId, view.getStatus().name(), workflow.stage(), workflow.result());
  }

  /** What callers see: Skipper's instance status, the workflow's own stage, and the outcome once there is one. */
  public static class OrderStatus {
    public final String orderId;
    public final String instanceStatus;
    public final String stage;
    public final OrderResult result;

    OrderStatus(String orderId, String instanceStatus, String stage, OrderResult result) {
      this.orderId = orderId;
      this.instanceStatus = instanceStatus;
      this.stage = stage;
      this.result = result;
    }
  }
}
