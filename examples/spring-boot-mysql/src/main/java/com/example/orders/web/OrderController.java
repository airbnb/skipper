package com.example.orders.web;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.api.WorkflowInstanceView;
import com.example.orders.OrderCallbackHandler;
import com.example.orders.OrderRequest;
import com.example.orders.OrderResult;
import com.example.orders.OrderWorkflow;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP face of the workflow. Placing an order starts a workflow instance keyed by the order id and returns
 * at once; the instance's status and outcome are read back by id; approval is a signal.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {
  private final IWorkflowFactory workflows;

  public OrderController(IWorkflowFactory workflows) {
    this.workflows = workflows;
  }

  @PostMapping
  public ResponseEntity<OrderStatus> place(@RequestBody OrderRequest order) {
    // runAsync hands the instance to the persistent scheduler (any replica may run it); detached returns as
    // soon as it is persisted instead of waiting for a result the request thread should not hold out for.
    workflows.builder(OrderWorkflow.class, order.getOrderId())
        .callbackHandler(OrderCallbackHandler.class)
        .runAsync()
        .detached()
        .build()
        .placeOrder(order);
    return ResponseEntity.accepted()
        .location(URI.create("/orders/" + order.getOrderId()))
        .body(status(order.getOrderId()));
  }

  @GetMapping("/{orderId}")
  public ResponseEntity<OrderStatus> get(@PathVariable String orderId) {
    try {
      return ResponseEntity.ok(status(orderId));
    } catch (IllegalArgumentException unknownInstance) {
      return ResponseEntity.notFound().build();
    }
  }

  @PostMapping("/{orderId}/approve")
  public OrderStatus approve(@PathVariable String orderId, @RequestParam boolean decision) {
    workflows.invoke(OrderWorkflow.class, orderId).approve(decision);
    return status(orderId);
  }

  private OrderStatus status(String orderId) {
    OrderWorkflow workflow = workflows.invoke(OrderWorkflow.class, orderId);
    WorkflowInstanceView view = workflow.getWorkflowInstanceView();
    return new OrderStatus(orderId, view.getStatus().name(), workflow.stage(), workflow.result());
  }

  /** What callers see: Skipper's instance status, the workflow's own stage, and the outcome once there is one. */
  public record OrderStatus(String orderId, String instanceStatus, String stage, OrderResult result) {}
}
