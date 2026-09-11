package com.example.orders;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;

public class ShippingActions extends Actions {
  @Autowired private Carrier carrier;

  /** Named on the annotation below; Skipper looks the strategy up by field name on this class. */
  RetryStrategy carrierOutage = new FixedRetryStrategy(Duration.ofMinutes(1), 3);

  @Execute(retryStrategy = "carrierOutage")
  public String ship(OrderRequest order) {
    try {
      return carrier.ship(order.getOrderId(), order.getSku());
    } catch (CarrierUnavailableException e) {
      throw new RetryableError("carrier unavailable, will retry", e);
    }
  }
}
