package com.example.orders

/**
 * Workflow input and output. Skipper persists them as JSON and round-trips each value before the first run,
 * comparing with equals, so data classes are the natural fit: property-based equality for free.
 */
data class OrderRequest(
    val orderId: String,
    val customerId: String,
    val sku: String,
    val quantity: Int,
    val amountCents: Long,
)

data class OrderResult(
    val status: String,
    val reason: String? = null,
    val reservationId: String? = null,
    val paymentId: String? = null,
    val trackingId: String? = null,
) {
    companion object {
        fun fulfilled(reservationId: String, paymentId: String, trackingId: String) =
            OrderResult("FULFILLED", reservationId = reservationId, paymentId = paymentId, trackingId = trackingId)

        fun rejected(reason: String) = OrderResult("REJECTED", reason = reason)
    }
}
