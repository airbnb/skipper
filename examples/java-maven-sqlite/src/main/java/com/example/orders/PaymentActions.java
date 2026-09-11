package com.example.orders;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import javax.inject.Inject;

public class PaymentActions extends Actions {
  @Inject PaymentGateway payments;

  @Execute
  public String charge(OrderRequest order) {
    return payments.charge(order.getCustomerId(), order.getAmountCents());
  }

  @Compensate(forExecute = "charge")
  public void refund(OrderRequest order, String paymentId) {
    payments.refund(paymentId);
  }
}
