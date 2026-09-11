package com.example.orders;

public interface PaymentGateway {
  String charge(String customerId, long amountCents);

  void refund(String paymentId);
}
