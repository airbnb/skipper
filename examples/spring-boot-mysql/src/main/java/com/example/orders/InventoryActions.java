package com.example.orders;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Actions are where side effects live. Skipper creates each Actions class as a recording proxy through its
 * no-arg constructor and then asks the injector to fill its members, so collaborators arrive through field
 * injection, not the constructor. Under Spring that means {@code @Autowired} fields (Spring 6+ no longer
 * recognises {@code javax.inject.Inject}, which Skipper's own SimpleInjector uses).
 */
public class InventoryActions extends Actions {
  @Autowired private InventoryService inventory;

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
