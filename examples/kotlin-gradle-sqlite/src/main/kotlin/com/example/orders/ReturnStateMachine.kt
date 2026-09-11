package com.example.orders

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.statemachine.SkipperStateMachine
import com.airbnb.skipper.statemachine.StateBuilder
import com.airbnb.skipper.statemachine.StateMachineBuilder
import com.airbnb.skipper.statemachine.StateMachineEvent
import java.time.Duration
import javax.inject.Inject

/**
 * A product return, written with the skipper-state-machine DSL instead of `waitUntil` and signal flags:
 *
 *     REQUESTED --Approved--> AWAITING_ITEM --ItemReceived--> REFUNDED
 *     REQUESTED --Rejected--> REJECTED
 *     AWAITING_ITEM --(14 days)--> EXPIRED
 *
 * States are an enum, events a sealed hierarchy, and the input is whatever every handler needs.
 */
enum class ReturnState { REQUESTED, AWAITING_ITEM, REFUNDED, REJECTED, EXPIRED }

sealed class ReturnEvent : StateMachineEvent() {
    data class Approved(val agentId: String) : ReturnEvent()

    data class Rejected(val reason: String) : ReturnEvent()

    data class ItemReceived(val condition: String) : ReturnEvent()
}

data class ReturnInput(val orderId: String, val paymentId: String)

class ReturnActions : Actions() {
    @Inject lateinit var payments: PaymentGateway

    @Execute
    suspend fun refund(paymentId: String) = payments.refund(paymentId)
}

class ReturnStateMachine :
    SkipperStateMachine<ReturnState, ReturnEvent, ReturnInput>(ReturnState.REQUESTED) {
    companion object {
        val RETURN_WINDOW: Duration = Duration.ofDays(14)

        /** Every caller derives the same workflow id from the order id, so creator, signaller and reader agree. */
        fun workflowId(orderId: String) = workflowId<ReturnStateMachine, String>(orderId)
    }

    private val actions = actions<ReturnActions>()

    override fun StateMachineBuilder<ReturnState, ReturnEvent, ReturnInput>.define() {
        state(ReturnState.REQUESTED) { handleRequested() }
        state(ReturnState.AWAITING_ITEM) { handleAwaitingItem() }
        state(ReturnState.REFUNDED) { terminal() }
        state(ReturnState.REJECTED) { terminal() }
        state(ReturnState.EXPIRED) { terminal() }
    }

    private fun StateBuilder<ReturnState, ReturnEvent, ReturnInput>.handleRequested() {
        on<ReturnEvent.Approved> { _, _ -> transitionTo(ReturnState.AWAITING_ITEM) }
        on<ReturnEvent.Rejected> { _, _ -> transitionTo(ReturnState.REJECTED) }
    }

    private fun StateBuilder<ReturnState, ReturnEvent, ReturnInput>.handleAwaitingItem() {
        on<ReturnEvent.ItemReceived> { _, input ->
            actions.refund(input.paymentId)
            transitionTo(ReturnState.REFUNDED)
        }
        timeout(RETURN_WINDOW) { _ -> transitionTo(ReturnState.EXPIRED) }
    }
}
