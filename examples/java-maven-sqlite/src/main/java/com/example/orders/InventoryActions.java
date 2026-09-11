package com.example.orders;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import javax.inject.Inject;

/**
 * Actions are where side effects live. Skipper subclasses this class to record each call as a checkpoint,
 * so it must have a no-arg constructor; collaborators arrive through field injection from the configured
 * SkipperInjector, not through the constructor.
 */
public class InventoryActions extends Actions {
  @Inject InventoryService inventory;

  @Execute
  public String reserve(OrderRequest order) {
    return inventory.reserve(order.getSku(), order.getQuantity());
  }

  /** Undo for {@link #reserve}: receives the original input and the recorded result. */
  @Compensate(forExecute = "reserve")
  public void release(OrderRequest order, String reservationId) {
    inventory.release(reservationId);
  }
}
