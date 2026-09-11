package com.example.orders;

import com.google.inject.AbstractModule;

/** The service's own collaborators. The Actions classes @Inject these interfaces. */
public final class OrdersModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(InventoryService.class).to(InMemoryInventory.class).asEagerSingleton();
    bind(PaymentGateway.class).to(InMemoryPayments.class).asEagerSingleton();
    bind(Carrier.class).to(FlakyCarrier.class).asEagerSingleton();
  }
}
