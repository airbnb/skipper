@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.testutils.MutableClock
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

// ── Data ─────────────────────────────────────────────────────────────────────

/** Input to the trip request approval workflow. */
data class TripRequest(
    val listingId: String,
    val guestId: String,
    val nights: Int,
    /** Total price in cents. */
    val totalPriceCents: Int,
)

/** Outcome of the booking approval workflow. */
data class BookingResult(
    val confirmed: Boolean,
    /** Set when [confirmed] is true. */
    val confirmationCode: String?,
    val message: String,
)

/** Tracks where the workflow is in the approval lifecycle. */
enum class ApprovalStatus { PENDING, AWAITING_HOST, CONFIRMED, DECLINED, EXPIRED }

// ── Actions ──────────────────────────────────────────────────────────────────

/**
 * Trip-related actions. The host-notification action is compensated: if the workflow fails
 * after notifying the host, [cancelHostNotification] is invoked automatically to avoid leaving
 * the host with a stale pending request.
 *
 * This demonstrates a **2-parameter `@Compensate`** method — the compensation receives both the
 * original action input (`TripRequest`) and the action's return value (`notifId: String`).
 */
open class TripActions : Actions() {
    @Execute
    suspend fun notifyHost(request: TripRequest): String {
        val notifId = "NOTIF-${request.listingId}-${request.guestId}-${request.nights}n"
        println(
            "[HOST] Listing ${request.listingId}: guest ${request.guestId} requests " +
                "${request.nights} nights. Dispatched notification ID: $notifId"
        )
        return notifId
    }

    /** Called automatically if the workflow fails after [notifyHost] succeeds. */
    @Compensate(forExecute = "notifyHost")
    suspend fun cancelHostNotification(
        request: TripRequest,
        notifId: String
    ) {
        println(
            "[HOST] Retracting notification $notifId for listing ${request.listingId}: " +
                "workflow failed — removing pending request from host inbox."
        )
    }

    @Execute
    suspend fun confirmBooking(request: TripRequest): BookingResult {
        val code = "CONF-${request.listingId.uppercase()}-${request.guestId.take(4).uppercase()}"
        return BookingResult(
            confirmed = true,
            confirmationCode = code,
            message = "Booking confirmed for ${request.nights} nights. Code: $code",
        )
    }

    @Execute
    suspend fun declineBooking(request: TripRequest): BookingResult {
        return BookingResult(
            confirmed = false,
            confirmationCode = null,
            message = "Host declined your trip request for listing ${request.listingId}.",
        )
    }

    @Execute
    suspend fun expireBooking(request: TripRequest): BookingResult {
        return BookingResult(
            confirmed = false,
            confirmationCode = null,
            message = "trip request for listing ${request.listingId} expired.",
        )
    }
}

// ── Workflow ──────────────────────────────────────────────────────────────────

/**
 * Trip booking approval workflow — a realistic multi-step `suspend fun` workflow.
 *
 * Demonstrates idiomatic Kotlin with `suspend fun` across all Skipper method types:
 * `@WorkflowMethod`, `@SignalMethod`, `@QueryMethod`, `@Execute`, and `@Compensate`.
 *
 * ## Flow
 * 1. Validate the trip request
 * 2. Notify the host (`@Execute`) — compensated with a 2-param `@Compensate`
 * 3. Wait for the host to signal a decision (up to 24 hours)
 * 4. Process the booking outcome (`@Execute`)
 * 5. Return the booking result
 */
open class TripApprovalWorkflow : Workflow() {
    private val actions = actions<TripActions>()

    @StateField var approvalStatus: ApprovalStatus = ApprovalStatus.PENDING
    @StateField var hostApproved: Boolean? = null

    @WorkflowMethod
    suspend fun requestApproval(request: TripRequest): BookingResult {
        if (request.nights <= 0) {
            throw NonRetryableError(
                "Invalid trip request: nights must be positive, got ${request.nights}"
            )
        }

        // Step 1: Notify the host. The @Compensate annotation ensures the notification is
        // cancelled automatically if this workflow fails after this point.
        approvalStatus = ApprovalStatus.AWAITING_HOST
        actions.notifyHost(request)

        // Step 2: Suspend until the host signals a decision (or 24 hours elapse).
        val decided = waitUntil({ hostApproved != null }, Duration.ofHours(24))
        if (!decided) {
            approvalStatus = ApprovalStatus.EXPIRED
            actions.expireBooking(request)
            throw NonRetryableError("Host did not respond within 24 hours")
        }

        // Step 3: Finalise the booking based on the host's decision.
        val approved = hostApproved!!
        approvalStatus = if (approved) ApprovalStatus.CONFIRMED else ApprovalStatus.DECLINED
        return if (approved) actions.confirmBooking(request) else actions.declineBooking(request)
    }

    /** Host signals their decision on the trip request. */
    @SignalMethod
    suspend fun setHostDecision(approved: Boolean) {
        hostApproved = approved
    }

    /** Returns the current stage of the approval workflow. */
    @QueryMethod
    suspend fun getApprovalStatus(): ApprovalStatus = approvalStatus
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/**
 * Real-world example: Trip Booking Approval using Kotlin Coroutines.
 *
 * ## Non-blocking proxy pattern
 *
 * Tests that involve [Workflow.waitUntil] use `async(Dispatchers.Default) { }` to start the
 * workflow through the suspend proxy while keeping the test thread free for polling and signals:
 *
 * ```kotlin
 * val deferred = async(Dispatchers.Default) { workflow.requestApproval(request) }
 * helper.expectWorkflowToWait()   // test thread polls; workflow is on Skipper's thread pool
 * workflow.setHostDecision(true)  // send signal via proxy
 * val result = deferred.await()   // resumes when the workflow completes
 * ```
 *
 * `Dispatchers.Default` is required because `runBlocking` uses a single-threaded event loop.
 * Without it, `async { }` would only queue the body — it wouldn't run until the outer
 * coroutine suspended, which is after `helper.expectWorkflowToWait()` has already timed out.
 * Running on `Dispatchers.Default` starts the body on a background thread immediately, so
 * `skipperEngine.startWorkflow()` is called before the polling begins. The proxy then returns
 * `COROUTINE_SUSPENDED`, and the `Deferred` completes when the workflow reaches a terminal state.
 */
class TripBookingApprovalExample {
    private lateinit var deps: TestRuntime
    private lateinit var workflowId: String
    private lateinit var helper: TestHelper
    private lateinit var clock: MutableClock

    private val sampleRequest = TripRequest(
        listingId = "listing-beach-123",
        guestId = "guest-alice",
        nights = 3,
        totalPriceCents = 45_000,
    )

    @BeforeEach
    fun setUp() {
        // Reset the process-global MutableClock singleton first. getInstance() ignores its argument
        // when an instance already exists, so without this we would inherit another integtest suite's
        // clock (sharing this JVM) instead of the fixed EPOCH clock this example requires.
        MutableClock.resetInstance()
        deps = TestRuntime()
        deps.setClock(MutableClock.getInstance(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))))
        clock = deps.config.utcClock as MutableClock

        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(deps.getSkipperEngine(), deps.getScheduler(), workflowId)
        whenever(deps.featureGate.isEnabled(any())).thenReturn(true)
        deps.getSchedulerManager().start()
    }

    @AfterEach
    fun tearDown() {
        deps.getSchedulerManager().forceStop()
    }

    /**
     * Happy path: the host approves and the booking is confirmed.
     * Exercises: suspend @WorkflowMethod, @Execute, @SignalMethod, @QueryMethod,
     * and the non-blocking `async { }` proxy pattern.
     */
    @Test
    fun `host approves trip request - booking is confirmed`(): Unit =
        runBlocking {
            val workflow = deps.getWorkflowFactory().builder(TripApprovalWorkflow::class.java, workflowId).build()

            val deferred = async(Dispatchers.Default) { workflow.requestApproval(sampleRequest) }
            helper.expectWorkflowToWait()
            assertThat(workflow.getApprovalStatus()).isEqualTo(ApprovalStatus.AWAITING_HOST)

            workflow.setHostDecision(true)

            val result = deferred.await()
            assertThat(result.confirmed).isTrue()
            assertThat(result.confirmationCode).startsWith("CONF-")
            assertThat(result.message).contains("confirmed")
            assertThat(workflow.getApprovalStatus()).isEqualTo(ApprovalStatus.CONFIRMED)
        }

    /**
     * Decline path: the host declines and the booking is not created.
     * Exercises: the declined branch driven by @SignalMethod.
     */
    @Test
    fun `host declines trip request - booking is declined`(): Unit =
        runBlocking {
            val workflow = deps.getWorkflowFactory().builder(TripApprovalWorkflow::class.java, workflowId).build()

            val deferred = async(Dispatchers.Default) { workflow.requestApproval(sampleRequest) }
            helper.expectWorkflowToWait()

            workflow.setHostDecision(false)

            val result = deferred.await()
            assertThat(result.confirmed).isFalse()
            assertThat(result.confirmationCode).isNull()
            assertThat(result.message).contains("declined")
            assertThat(workflow.getApprovalStatus()).isEqualTo(ApprovalStatus.DECLINED)
        }

    /**
     * Compensation path: the host does not respond in time, the workflow times out, and the
     * 2-param `@Compensate` method [TripActions.cancelHostNotification] is invoked automatically.
     *
     * This is the key test for the 2-parameter compensation form:
     * `fun cancelHostNotification(request: TripRequest, notifId: String)` — the compensation
     * receives both the original action input **and** the action's return value.
     */
    @Test
    fun `host does not respond - compensation cancels host notification`(): Unit =
        runBlocking {
            val workflow = deps.getWorkflowFactory().builder(TripApprovalWorkflow::class.java, workflowId).build()

            // runCatching suppresses the NonRetryableError that the proxy eventually surfaces
            // when the workflow fails after the timeout. Without it, the deferred's failure would
            // propagate to the parent runBlocking scope via structured concurrency.
            async(Dispatchers.Default) { runCatching { workflow.requestApproval(sampleRequest) } }
            helper.expectWorkflowToWait()

            // Advance past the 24-hour deadline — triggers waitUntil timeout, which throws
            // NonRetryableError, which triggers compensation for the notifyHost action.
            clock.fastForward(Duration.ofHours(25))

            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
            assertThat(workflow.getApprovalStatus()).isEqualTo(ApprovalStatus.EXPIRED)
        }

    /**
     * Validation path: a request with zero nights is rejected before any actions execute.
     * Exercises: early NonRetryableError propagation through the suspend proxy.
     */
    @Test
    fun `invalid trip request with zero nights fails immediately`() {
        val invalidRequest = sampleRequest.copy(nights = 0, totalPriceCents = 0)
        val workflow = deps.getWorkflowFactory().builder(TripApprovalWorkflow::class.java, workflowId).build()

        val thrown = assertThrows<NonRetryableError> {
            runBlocking { workflow.requestApproval(invalidRequest) }
        }
        assertThat(thrown.message).contains("nights must be positive")
    }
}
