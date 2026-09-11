package com.example.orders

import com.airbnb.skipper.IWorkflowFactory
import com.airbnb.skipper.SimpleInjector
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.builder
import com.airbnb.skipper.invoke
import com.airbnb.skipper.factory.SkipperRuntime
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Runs the order workflow and the return state machine against a file-backed SQLite store:
 *
 * 1. a small order that completes in one pass;
 * 2. a large order that parks for approval, is approved by a signal, and then completes;
 * 3. a hazardous order the carrier refuses, whose charge and reservation are compensated;
 * 4. a return for the first order, driven through the state machine by events.
 */
fun main(args: Array<String>) {
    val dbPath = args.firstOrNull() ?: "orders.db"
    val inventory = InMemoryInventory()
    val payments = InMemoryPayments()
    val carrier = FlakyCarrier()

    val runtime = startSkipper(dbPath, inventory, payments, carrier)
    val factory = runtime.workflowFactory.get()
    val run = UUID.randomUUID().toString().take(8)
    try {
        runBlocking {
            // 1. Small order: the suspend call returns when the workflow completes.
            val smallId = "order-$run-small"
            val small = factory.builder<OrderWorkflow>(smallId).build()
                .placeOrder(OrderRequest(smallId, "alice", "BOOK-1", 2, 3_900))
            println("$smallId -> $small")

            // 2. Large order: parks in WAITING. A suspend caller keeps waiting for a parked instance only up to
            //    SkipperConfig.resultPollingTimeLimit (30 s by default), then fails with TimeoutException, so a
            //    suspend call is not the way to await an approval that may take a day. Here the approval arrives
            //    within seconds; a real service would return as soon as the instance is persisted and read the
            //    outcome later through the result() query or a WorkflowCallbackHandler.
            val largeId = "order-$run-large"
            val large = factory.builder<OrderWorkflow>(largeId).build()
            val largeResult = async(Dispatchers.IO) {
                large.placeOrder(OrderRequest(largeId, "bob", "LAPTOP-9", 1, 149_900))
            }
            factory.awaitStatus<OrderWorkflow>(largeId, WorkflowInstanceStatusView.WAITING)
            println("$largeId -> WAITING, stage=${large.stage()}")
            factory<OrderWorkflow>(largeId).approve(true)
            println("$largeId -> ${largeResult.await()}")

            // 3. Hazardous order: reserve and charge succeed, the carrier refuses, both are undone. The call fails
            //    with the action's error; the compensation runs afterwards, so wait for its status.
            val hazId = "order-$run-hazmat"
            runCatching { factory.builder<OrderWorkflow>(hazId).build().placeOrder(OrderRequest(hazId, "carol", "HAZMAT-7", 1, 12_000)) }
            val view = factory.awaitStatus<OrderWorkflow>(hazId, WorkflowInstanceStatusView.COMPENSATION_COMPLETED, Duration.ofSeconds(90))
            println("$hazId -> ${view.status}")

            // 4. Return for the small order, as a state machine: one workflow, three events, one refund.
            val returnId = ReturnStateMachine.workflowId(smallId)
            val ret = factory.builder<ReturnStateMachine>(returnId).build()
            val returnRun = async(Dispatchers.IO) { ret.execute(ReturnInput(smallId, small.paymentId!!)) }
            factory.awaitStatus<ReturnStateMachine>(returnId, WorkflowInstanceStatusView.WAITING)
            ret.sendEvent(ReturnEvent.Approved("agent-7"))
            factory.awaitState(returnId, ReturnState.AWAITING_ITEM)
            ret.sendEvent(ReturnEvent.ItemReceived("unopened"))
            println("$returnId -> ${returnRun.await()}")

            println("inventory: ${inventory.entries()}")
            println("payments:  ${payments.entries()}")
        }
    } finally {
        runtime.skipperSchedulerManager.get().stop()
    }
}

/** Wires Skipper once per process: file-backed SQLite store, and SimpleInjector for the actions' @Inject fields. */
fun startSkipper(dbPath: String, inventory: InventoryService, payments: PaymentGateway, carrier: Carrier): SkipperRuntime {
    val config = SkipperConfig.forService("orders-example").apply {
        workflowStore = SqliteWorkflowStore.Factory(dbPath)
        scheduler = SqliteScheduler.Factory(dbPath)
        injector = SimpleInjector.builder()
            .bind(InventoryService::class.java, inventory)
            .bind(PaymentGateway::class.java, payments)
            .bind(Carrier::class.java, carrier)
            .build()
    }
    return SkipperRuntime(config).also { it.skipperSchedulerManager.get().start() }
}

/**
 * Polls the persisted status until it matches, failing fast on any other terminal status. Tolerates the
 * moment between starting an instance in another coroutine and its first write to the store.
 */
inline fun <reified T : Workflow> IWorkflowFactory.awaitStatus(
    id: String,
    expected: WorkflowInstanceStatusView,
    timeout: Duration = Duration.ofSeconds(20),
): WorkflowInstanceView = poll(id, timeout) { this<T>(id).getWorkflowInstanceView().takeIf { it.status == expected } }

fun IWorkflowFactory.awaitState(id: String, expected: ReturnState, timeout: Duration = Duration.ofSeconds(20)): ReturnState =
    poll(id, timeout) { this<ReturnStateMachine>(id).getState().takeIf { it == expected } }

fun <R : Any> poll(id: String, timeout: Duration, probe: () -> R?): R {
    val deadline = Instant.now().plus(timeout)
    while (true) {
        val found = runCatching(probe).getOrNull() // "workflow not found" until the first persist
        if (found != null) return found
        check(Instant.now().isBefore(deadline)) { "$id did not reach the expected state within $timeout" }
        Thread.sleep(200)
    }
}
