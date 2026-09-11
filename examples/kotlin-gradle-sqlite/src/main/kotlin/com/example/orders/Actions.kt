package com.example.orders

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import java.time.Duration
import javax.inject.Inject

/**
 * Actions are where side effects live. Skipper subclasses each Actions class to record every call as a
 * checkpoint, so it needs a no-arg constructor; collaborators arrive through field injection from the
 * configured SkipperInjector. `lateinit var` is the Kotlin shape of an injected field.
 */
class InventoryActions : Actions() {
    @Inject lateinit var inventory: InventoryService

    @Execute
    suspend fun reserve(order: OrderRequest): String = inventory.reserve(order.sku, order.quantity)

    /** Undo for [reserve]: receives the original input and the recorded result. */
    @Compensate(forExecute = "reserve")
    suspend fun release(order: OrderRequest, reservationId: String) = inventory.release(reservationId)
}

class PaymentActions : Actions() {
    @Inject lateinit var payments: PaymentGateway

    @Execute
    suspend fun charge(order: OrderRequest): String = payments.charge(order.customerId, order.amountCents)

    @Compensate(forExecute = "charge")
    suspend fun refund(order: OrderRequest, paymentId: String) = payments.refund(paymentId)
}

class ShippingActions : Actions() {
    @Inject lateinit var carrier: Carrier

    /** Named on the annotation below; Skipper looks the strategy up by field name on this class. */
    val carrierOutage: RetryStrategy = FixedRetryStrategy(Duration.ofMinutes(1), 3)

    @Execute(retryStrategy = "carrierOutage")
    suspend fun ship(order: OrderRequest): String =
        try {
            carrier.ship(order.orderId, order.sku)
        } catch (e: CarrierUnavailableException) {
            // Retryable: Skipper reschedules the step per carrierOutage. Anything else thrown here is
            // non-retryable and triggers compensation of the steps that already completed.
            throw RetryableError("carrier unavailable, will retry", e)
        }
}
