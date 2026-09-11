package com.example.orders

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Collaborators the actions call. Hand-written fakes stand in for them in tests. */
interface InventoryService {
    fun reserve(sku: String, quantity: Int): String

    fun release(reservationId: String)
}

interface PaymentGateway {
    fun charge(customerId: String, amountCents: Long): String

    fun refund(paymentId: String)
}

interface Carrier {
    /**
     * @throws CarrierUnavailableException when the carrier is down and the call should be retried
     * @throws IllegalArgumentException when the carrier refuses the shipment for good
     */
    fun ship(orderId: String, sku: String): String
}

/** A transient failure: the action wraps it in Skipper's RetryableError so the engine retries the step. */
class CarrierUnavailableException(message: String) : RuntimeException(message)

class InMemoryInventory : InventoryService {
    private val seq = AtomicInteger()
    private val log = Collections.synchronizedList(mutableListOf<String>())

    override fun reserve(sku: String, quantity: Int): String =
        "res-${seq.incrementAndGet()}".also { log += "RESERVE $it $quantity x $sku" }

    override fun release(reservationId: String) {
        log += "RELEASE $reservationId"
    }

    fun entries(): List<String> = log.toList()
}

class InMemoryPayments : PaymentGateway {
    private val seq = AtomicInteger()
    private val log = Collections.synchronizedList(mutableListOf<String>())

    override fun charge(customerId: String, amountCents: Long): String =
        "pay-${seq.incrementAndGet()}".also { log += "CHARGE $it $customerId $${amountCents / 100}" }

    override fun refund(paymentId: String) {
        log += "REFUND $paymentId"
    }

    fun entries(): List<String> = log.toList()
}

/**
 * A carrier that can be told to be down for the next N calls (transient) and that permanently refuses any
 * SKU starting with "HAZMAT" (non-retryable, so the workflow compensates).
 */
class FlakyCarrier : Carrier {
    private val failuresRemaining = AtomicInteger()
    private val callCount = AtomicInteger()
    private val seq = AtomicInteger()

    fun failNext(times: Int) = failuresRemaining.set(times)

    val calls: Int get() = callCount.get()

    override fun ship(orderId: String, sku: String): String {
        callCount.incrementAndGet()
        if (failuresRemaining.getAndUpdate { maxOf(0, it - 1) } > 0) {
            throw CarrierUnavailableException("carrier API returned 503")
        }
        require(!sku.startsWith("HAZMAT")) { "carrier refuses hazardous goods: $sku" }
        return "trk-${seq.incrementAndGet()}"
    }
}
