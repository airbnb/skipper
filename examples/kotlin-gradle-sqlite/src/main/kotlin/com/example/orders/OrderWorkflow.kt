package com.example.orders

import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import java.time.Duration

/**
 * Order fulfilment: reserve stock, charge the customer, hand the parcel to a carrier. Orders at or above
 * [APPROVAL_THRESHOLD_CENTS] park until someone approves them or a day passes. If the carrier refuses the
 * parcel, the charge and the reservation are compensated in reverse order.
 *
 * Kotlin suspend style: the workflow method is a `suspend fun` returning the result directly. The caller's
 * coroutine suspends while the workflow runs; no CompletableFuture and no `returnType` on the annotation.
 */
class OrderWorkflow : Workflow() {
    companion object {
        const val APPROVAL_THRESHOLD_CENTS = 50_000L
        val APPROVAL_WINDOW: Duration = Duration.ofDays(1)
    }

    private val inventory = actions<InventoryActions>()
    private val payments = actions<PaymentActions>()
    private val shipping = actions<ShippingActions>()

    @StateField var stage: String = "NEW"
    @StateField var approved: Boolean? = null
    @StateField var result: OrderResult? = null

    @WorkflowMethod
    suspend fun placeOrder(order: OrderRequest): OrderResult {
        if (order.amountCents >= APPROVAL_THRESHOLD_CENTS) {
            stage = "AWAITING_APPROVAL"
            // Parks the instance (status WAITING) with no thread held. A signal or the deadline wakes it.
            val decided = waitUntil({ approved != null }, APPROVAL_WINDOW)
            if (!decided) return finish(OrderResult.rejected("no approval within $APPROVAL_WINDOW"))
            if (approved != true) return finish(OrderResult.rejected("declined by reviewer"))
        }
        stage = "RESERVING"
        val reservationId = inventory.reserve(order)
        stage = "CHARGING"
        val paymentId = payments.charge(order)
        stage = "SHIPPING"
        val trackingId = shipping.ship(order)
        return finish(OrderResult.fulfilled(reservationId, paymentId, trackingId))
    }

    private fun finish(outcome: OrderResult): OrderResult {
        stage = outcome.status
        result = outcome
        return outcome
    }

    /** Delivered from outside while the instance is parked; persisted so it can be replayed from the admin UI. */
    @SignalMethod(persist = true)
    fun approve(decision: Boolean) {
        approved = decision
    }

    @QueryMethod
    fun stage(): String = stage

    /** Null until the workflow reaches a terminal decision. Lets callers read the outcome without holding a future. */
    @QueryMethod
    fun result(): OrderResult? = result
}
