package com.airbnb.skipper.statemachine.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.statemachine.SkipperStateMachine
import com.airbnb.skipper.statemachine.StateMachineAdminSnapshot
import com.airbnb.skipper.statemachine.StateMachineBuilder
import com.airbnb.skipper.statemachine.StateMachineCheckpoint
import com.airbnb.skipper.statemachine.StateMachineEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vavr.collection.HashMap
import io.vavr.collection.Map as VavrMap
import io.vavr.control.Either
import io.vavr.control.Option
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import javax.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Event with a payload field, used for sendEvent deserialization tests. */
class ParameterizedTestEvent(val reason: String = "") : StateMachineEvent()

class StateMachineAdminResourceTest {
    private val store = mockk<WorkflowStore>()
    private val skipperEngine = mockk<SkipperEngine>()
    private val resource = StateMachineAdminResource(store, skipperEngine)

    private enum class TestState { OPEN, CLOSED, DONE }

    private class TestEvent : StateMachineEvent()

    private open class SomeActions

    private open class TestStateMachine :
        SkipperStateMachine<TestState, TestEvent, String>(TestState.OPEN) {
        override fun StateMachineBuilder<TestState, TestEvent, String>.define() {
            state(TestState.DONE) { terminal() }
        }
    }

    @Test
    fun `returns parsed state machine instance with full history`() {
        val snapshot = buildAdminSnapshot(
            currentState = "CLOSED",
            stateEntryTime = tsFromMs(2000),
            stateHistory = listOf(
                StateMachineAdminSnapshot.AdminStateEntry("OPEN", Instant.ofEpochMilli(1000)),
                StateMachineAdminSnapshot.AdminStateEntry("CLOSED", Instant.ofEpochMilli(2000)),
            ),
            eventHistory = listOf(
                StateMachineAdminSnapshot.AdminEventEntry("TestEvent", TestEvent(), Instant.ofEpochMilli(1500)),
            ),
            afterHookHistory = listOf(
                StateMachineAdminSnapshot.AfterHookExecutionEntry(
                    duration = Duration.ofHours(1),
                    timestamp = Instant.ofEpochMilli(1800),
                    handlerSpan = ptr(1, 800_000_000, 1, 900_000_000),
                ),
            ),
            timeoutHistory = listOf(
                StateMachineAdminSnapshot.TimeoutExecutionEntry(
                    duration = Duration.ofDays(14),
                    timestamp = Instant.ofEpochMilli(5000),
                    handlerSpan = ptr(5, 100_000_000, 5, 200_000_000),
                ),
            ),
            transitionHistory = listOf(
                SkipperStateMachine.TransitionLogEntry(
                    fromState = "OPEN",
                    toState = "CLOSED",
                    outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
                    triggerKind = SkipperStateMachine.TriggerKind.EVENT,
                    triggerName = "TestEvent",
                    span = ptr(1, 0, 1, 200_000_000),
                    handlerSpan = null,
                    eventIndex = 0,
                    initialMiddlewareSpan = null,
                    beforeMiddlewareSpan = ptr(1, 0, 1, 50_000_000),
                    onExitSpan = ptr(1, 50_000_000, 1, 80_000_000),
                    onEntrySpan = ptr(1, 100_000_000, 1, 120_000_000),
                    afterMiddlewareSpan = ptr(1, 120_000_000, 1, 200_000_000),
                    terminalMiddlewareSpan = null,
                ),
            ),
            validEventsPerState = mapOf(
                "OPEN" to listOf(ParameterizedTestEvent::class.java),
            ),
            pendingTimerDeadlines = listOf(
                StateMachineAdminSnapshot.AdminPendingTimer("timeout", "PT336H", 99, 999_000_000),
            ),
        )
        val instance = buildInstance(input = "test-input-value")
        stubWorkflow("test-id", instance, snapshot)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.workflowId).isEqualTo("test-id")
        assertThat(result.workflowClass).isEqualTo("TestStateMachine")
        assertThat(result.workflowMethod).isEqualTo("execute")
        assertThat(result.status).isEqualTo("RUNNING")
        assertThat(result.workflowInput).isEqualTo("test-input-value")
        assertThat(result.stateFields).isEmpty()
        assertThat(result.currentState).isEqualTo("CLOSED")
        assertThat(result.stateEntryTime).isEqualTo(PreciseTimestampView(2, 0))

        assertThat(result.stateHistory).containsExactly(
            StateEntry("OPEN", PreciseTimestampView(1, 0)),
            StateEntry("CLOSED", PreciseTimestampView(2, 0)),
        )
        assertThat(result.eventHistory).hasSize(1)
        assertThat(result.eventHistory[0].eventType).isEqualTo("TestEvent")
        assertThat(result.afterHookHistory).hasSize(1)
        assertThat(result.afterHookHistory[0].duration).isEqualTo("PT1H")
        assertThat(result.afterHookHistory[0].handlerSpan).isNotNull()
        assertThat(result.timeoutHistory).hasSize(1)
        assertThat(result.timeoutHistory[0].duration).isEqualTo("PT336H")
        assertThat(result.timeoutHistory[0].handlerSpan).isNotNull()
        assertThat(result.transitionLog).hasSize(1)
        assertThat(result.transitionLog[0].triggerName).isEqualTo("TestEvent")
        assertThat(result.transitionLog[0].beforeMiddlewareSpan).isNotNull()
        assertThat(result.transitionLog[0].onExitSpan).isNotNull()
        assertThat(result.transitionLog[0].onEntrySpan).isNotNull()
        assertThat(result.transitionLog[0].afterMiddlewareSpan).isNotNull()
        assertThat(result.transitionLog[0].terminalMiddlewareSpan).isNull()
        assertThat(result.validEvents).containsKey("OPEN")
        assertThat(result.validEvents["OPEN"]!![0].eventSimpleName).isEqualTo("ParameterizedTestEvent")
        assertThat(result.pendingTimers).containsExactly(
            PendingTimerInfo("timeout", "PT336H", PreciseTimestampView(99, 999_000_000)),
        )
        assertThat(result.sequenceDiagramMermaid).startsWith("sequenceDiagram")
        assertThat(result.sequenceDiagramMermaid).contains("Ext->>SM: TestEvent")
    }

    @Test
    fun `returns empty histories when snapshot is unavailable`() {
        val instance = buildInstance(workflowId = "empty-sm")
        stubWorkflow("empty-sm", instance, snapshot = null)

        val result = resource.getWorkflowInstance("empty-sm", null)

        assertThat(result.stateHistory).isEmpty()
        assertThat(result.eventHistory).isEmpty()
        assertThat(result.afterHookHistory).isEmpty()
        assertThat(result.timeoutHistory).isEmpty()
        assertThat(result.transitionLog).isEmpty()
        assertThat(result.actionHistory).isEmpty()
        assertThat(result.pendingTimers).isEmpty()
        assertThat(result.validEvents).isEmpty()
        assertThat(result.currentState).isNull()
        assertThat(result.stateEntryTime).isNull()
        assertThat(result.sequenceDiagramMermaid).isEmpty()
    }

    @Test
    fun `returns empty histories and logs warning when snapshot query fails`() {
        val instance = buildInstance(workflowId = "broken-sm")
        var result: StateMachineInstanceView? = null
        every { store.getWorkflow("broken-sm") } returns Option.of(instance)
        every { store.getActionCheckpoints("broken-sm") } returns io.vavr.collection.List.empty()
        every { skipperEngine.invokeQueryMethod(any()) } throws RuntimeException("query failed")

        val warnings = captureWarnings {
            result = resource.getWorkflowInstance("broken-sm", null)
        }
        val view = result ?: error("Expected state machine view")

        assertThat(view.stateHistory).isEmpty()
        assertThat(view.currentState).isNull()
        assertThat(warnings.map { it.formattedMessage })
            .anyMatch { it.contains("Failed to query state machine admin snapshot") && it.contains("workflowId=broken-sm") }
    }

    @Test
    fun `includes workflow metadata when present`() {
        val created = Instant.ofEpochMilli(5000)
        val updated = Instant.ofEpochMilli(6000)
        val instance = buildInstance(
            workflowId = "child-456",
            status = WorkflowInstance.Status.COMPLETED,
            parentWorkflowId = "parent-123",
            createdAt = created,
            updatedAt = updated,
        )
        stubWorkflow("child-456", instance, snapshot = null)

        val result = resource.getWorkflowInstance("child-456", null)

        assertThat(result.parentWorkflowId).isEqualTo("parent-123")
        assertThat(result.createdAt).isEqualTo(created)
        assertThat(result.updatedAt).isEqualTo(updated)
        assertThat(result.status).isEqualTo("COMPLETED")
    }

    @Test
    fun `returns public state fields while filtering state machine internals`() {
        val persistedState = SkipperStateMachine.PersistedStateBlob(byteArrayOf(1, 2, 3))
        val state = HashMap.empty<String, Any?>()
            .put("persistedState", persistedState)
            .put("__framework", "hidden")
            .put("winningResponseId", 123L)
            .put("responseStates", mapOf("456" to "ACTIVE"))
        val instance = buildInstance(state = state)
        stubWorkflow("test-id", instance, snapshot = null)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.stateFields)
            .containsEntry("winningResponseId", 123L)
            .containsEntry("responseStates", mapOf("456" to "ACTIVE"))
        assertThat(result.stateFields).doesNotContainKeys("persistedState", "__framework")
    }

    @Test
    fun `returns 404 when workflow not found`() {
        every { store.getWorkflow("missing") } returns Option.none()

        assertThatThrownBy { resource.getWorkflowInstance("missing", null) }
            .isInstanceOf(WebApplicationException::class.java)
            .satisfies({ ex ->
                assertThat((ex as WebApplicationException).response.status).isEqualTo(404)
            })
    }

    @Test
    fun `returns 400 when workflow is not a state machine`() {
        val instance = buildInstance(workflowId = "plain-wf", workflowClass = Workflow::class.java)
        every { store.getWorkflow("plain-wf") } returns Option.of(instance)

        assertThatThrownBy { resource.getWorkflowInstance("plain-wf", null) }
            .isInstanceOf(WebApplicationException::class.java)
            .satisfies({ ex ->
                assertThat((ex as WebApplicationException).response.status).isEqualTo(400)
            })
    }

    @Test
    fun `parses action checkpoints and filters bookkeeping actions`() {
        val bookkeepingCheckpoint = buildCheckpoint(
            actionClass = StateMachineCheckpoint::class.java,
            actionMethod = "recordStateMachineJournalSegment",
        )
        val businessCheckpoint = buildCheckpoint(
            actionClass = SomeActions::class.java,
            actionMethod = "doSomething",
            startTimeMs = 1500,
            endTimeMs = 1600,
            result = Either.right("ok-value"),
            input = "my-input",
        )
        val instance = buildInstance()
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { store.getActionCheckpoints("test-id") } returns io.vavr.collection.List.of(bookkeepingCheckpoint, businessCheckpoint)
        stubSnapshotQuery(null)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.actionHistory).hasSize(1)
        with(result.actionHistory.single()) {
            assertThat(actionClass).isEqualTo("SomeActions")
            assertThat(actionMethod).isEqualTo("doSomething")
            assertThat(startTime).isEqualTo(PreciseTimestampView(1, 500_000_000))
            assertThat(endTime).isEqualTo(PreciseTimestampView(1, 600_000_000))
            assertThat(successful).isTrue()
            assertThat(compensation).isFalse()
            assertThat(input).isEqualTo("my-input")
            assertThat(this.result).isEqualTo("ok-value")
        }
        assertThat(result.sequenceDiagramMermaid).contains("SomeActions.doSomething()")
    }

    @Test
    fun `scopes action checkpoints to transition phases and expands transition span`() {
        val businessCheckpoint = buildCheckpoint(
            actionClass = SomeActions::class.java,
            actionMethod = "doSomething",
            startTimeMs = 1080,
            endTimeMs = 1120,
            result = Either.right("ok-value"),
        )
        val snapshot = buildAdminSnapshot(
            transitionHistory = listOf(
                SkipperStateMachine.TransitionLogEntry(
                    fromState = "OPEN",
                    toState = "CLOSED",
                    outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
                    triggerKind = SkipperStateMachine.TriggerKind.EVENT,
                    triggerName = "TestEvent",
                    span = ptr(1, 0, 1, 200_000_000),
                    handlerSpan = ptr(0, 900_000_000, 1, 0),
                    eventIndex = 0,
                    initialMiddlewareSpan = null,
                    beforeMiddlewareSpan = ptr(1, 0, 1, 50_000_000),
                    onExitSpan = ptr(1, 50_000_000, 1, 80_000_000),
                    onEntrySpan = ptr(1, 80_000_000, 1, 120_000_000),
                    afterMiddlewareSpan = ptr(1, 120_000_000, 1, 200_000_000),
                    terminalMiddlewareSpan = ptr(1, 300_000_000, 1, 400_000_000),
                )
            )
        )
        val instance = buildInstance()
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { store.getActionCheckpoints("test-id") } returns io.vavr.collection.List.of(businessCheckpoint)
        stubSnapshotQuery(snapshot)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.actionHistory.single().transitionLabel).isEqualTo("OPEN → CLOSED")
        assertThat(result.actionHistory.single().transitionPhase).isEqualTo("onEntry")
        assertThat(result.actionHistory.single().transitionScopeKind).isEqualTo("EXACT")
        assertThat(result.transitionLog.single().span.start).isEqualTo(PreciseTimestampView(0, 900_000_000))
        assertThat(result.transitionLog.single().span.end).isEqualTo(PreciseTimestampView(1, 400_000_000))
    }

    @Test
    fun `infers terminal transition scope for replay-shifted side effects`() {
        val businessCheckpoint = buildCheckpoint(
            actionClass = SomeActions::class.java,
            actionMethod = "doSomething",
            startTimeMs = 3200,
            endTimeMs = 3400,
            result = Either.right("ok-value"),
        )
        val snapshot = buildAdminSnapshot(
            transitionHistory = listOf(
                SkipperStateMachine.TransitionLogEntry(
                    fromState = "OPEN",
                    toState = "DONE",
                    outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
                    triggerKind = SkipperStateMachine.TriggerKind.EVENT,
                    triggerName = "Stopped",
                    span = ptr(1, 0, 1, 200_000_000),
                    handlerSpan = ptr(0, 900_000_000, 1, 0),
                    eventIndex = 0,
                    initialMiddlewareSpan = null,
                    beforeMiddlewareSpan = ptr(1, 0, 1, 50_000_000),
                    onExitSpan = ptr(1, 50_000_000, 1, 80_000_000),
                    onEntrySpan = ptr(1, 80_000_000, 1, 120_000_000),
                    afterMiddlewareSpan = ptr(1, 120_000_000, 1, 200_000_000),
                    terminalMiddlewareSpan = ptr(1, 300_000_000, 1, 400_000_000),
                )
            )
        )
        val instance = buildInstance(status = WorkflowInstance.Status.COMPLETED)
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { store.getActionCheckpoints("test-id") } returns io.vavr.collection.List.of(businessCheckpoint)
        stubSnapshotQuery(snapshot)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.actionHistory.single().transitionLabel).isEqualTo("OPEN → DONE")
        assertThat(result.actionHistory.single().transitionPhase).isNull()
        assertThat(result.actionHistory.single().transitionScopeKind).isEqualTo("INFERRED")
        assertThat(result.transitionLog.single().span.end).isEqualTo(PreciseTimestampView(3, 400_000_000))
    }

    @Test
    fun `leaves actions unscoped when no exact or terminal inference applies`() {
        val businessCheckpoint = buildCheckpoint(
            actionClass = SomeActions::class.java,
            actionMethod = "doSomething",
            startTimeMs = 3200,
            endTimeMs = 3400,
            result = Either.right("ok-value"),
        )
        val snapshot = buildAdminSnapshot(
            transitionHistory = listOf(
                SkipperStateMachine.TransitionLogEntry(
                    fromState = "OPEN",
                    toState = "CLOSED",
                    outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
                    triggerKind = SkipperStateMachine.TriggerKind.EVENT,
                    triggerName = "TestEvent",
                    span = ptr(1, 0, 1, 200_000_000),
                    handlerSpan = ptr(0, 900_000_000, 1, 0),
                    eventIndex = 0,
                    initialMiddlewareSpan = null,
                    beforeMiddlewareSpan = ptr(1, 0, 1, 50_000_000),
                    onExitSpan = ptr(1, 50_000_000, 1, 80_000_000),
                    onEntrySpan = ptr(1, 80_000_000, 1, 120_000_000),
                    afterMiddlewareSpan = ptr(1, 120_000_000, 1, 200_000_000),
                    terminalMiddlewareSpan = null,
                )
            )
        )
        val instance = buildInstance(status = WorkflowInstance.Status.RUNNING)
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { store.getActionCheckpoints("test-id") } returns io.vavr.collection.List.of(businessCheckpoint)
        stubSnapshotQuery(snapshot)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.actionHistory.single().transitionLabel).isNull()
        assertThat(result.actionHistory.single().transitionPhase).isNull()
        assertThat(result.actionHistory.single().transitionScopeKind).isNull()
    }

    @Test
    fun `marks failed and compensation action checkpoints`() {
        val failedCheckpoint = buildCheckpoint(
            actionMethod = "compensateDoSomething",
            result = Either.left(RuntimeException("boom")),
        )
        val instance = buildInstance()
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { store.getActionCheckpoints("test-id") } returns io.vavr.collection.List.of(failedCheckpoint)
        stubSnapshotQuery(null)

        val result = resource.getWorkflowInstance("test-id", null)

        assertThat(result.actionHistory).hasSize(1)
        assertThat(result.actionHistory[0].successful).isFalse()
        assertThat(result.actionHistory[0].compensation).isTrue()
        assertThat(result.actionHistory[0].result).isInstanceOf(RuntimeException::class.java)
        assertThat(result.sequenceDiagramMermaid).contains("⟲ ")
    }

    @Test
    fun `discoverChildWorkflows returns child state machine instances`() {
        val parent = buildInstance(workflowId = "parent-id")
        val child = buildInstance(workflowId = "child-id", parentWorkflowId = "parent-id")
        every { store.getWorkflow("parent-id") } returns Option.of(parent)
        every { store.getActionCheckpoints("parent-id") } returns io.vavr.collection.List.empty()
        every { store.getActionCheckpoints("child-id") } returns io.vavr.collection.List.empty()
        every { store.findWorkflows(any(), any()) } returns io.vavr.collection.List.of(child)
        stubSnapshotQuery(null)

        val result = resource.getWorkflowInstance("parent-id", true)

        assertThat(result.childWorkflows).hasSize(1)
        assertThat(result.childWorkflows[0].workflowId).isEqualTo("child-id")
        assertThat(result.childWorkflows[0].parentWorkflowId).isEqualTo("parent-id")
    }

    @Test
    fun `discoverChildWorkflows filters non state machine children`() {
        val parent = buildInstance(workflowId = "parent-id")
        val nonSmChild = buildInstance(workflowId = "plain-child", workflowClass = Workflow::class.java)
        every { store.getWorkflow("parent-id") } returns Option.of(parent)
        every { store.getActionCheckpoints("parent-id") } returns io.vavr.collection.List.empty()
        every { store.findWorkflows(any(), any()) } returns io.vavr.collection.List.of(nonSmChild)
        stubSnapshotQuery(null)

        val result = resource.getWorkflowInstance("parent-id", true)

        assertThat(result.childWorkflows).isEmpty()
    }

    @Test
    fun `discoverChildWorkflows logs warning and returns empty children when store fails`() {
        val parent = buildInstance(workflowId = "parent-id")
        var result: StateMachineInstanceView? = null
        every { store.getWorkflow("parent-id") } returns Option.of(parent)
        every { store.getActionCheckpoints("parent-id") } returns io.vavr.collection.List.empty()
        every { store.findWorkflows(any(), any()) } throws RuntimeException("store error")
        stubSnapshotQuery(null)

        val warnings = captureWarnings {
            result = resource.getWorkflowInstance("parent-id", true)
        }
        val view = result ?: error("Expected state machine view")

        assertThat(view.childWorkflows).isEmpty()
        assertThat(warnings.map { it.formattedMessage })
            .anyMatch { it.contains("Failed to discover child state machine workflows") && it.contains("parentWorkflowId=parent-id") }
    }

    @Test
    fun `discoverChildWorkflows logs warning and skips child when view conversion fails`() {
        val parent = buildInstance(workflowId = "parent-id")
        val child = buildInstance(workflowId = "child-id", parentWorkflowId = "parent-id")
        var result: StateMachineInstanceView? = null
        every { store.getWorkflow("parent-id") } returns Option.of(parent)
        every { store.getActionCheckpoints("parent-id") } returns io.vavr.collection.List.empty()
        every { store.getActionCheckpoints("child-id") } throws RuntimeException("checkpoint error")
        every { store.findWorkflows(any(), any()) } returns io.vavr.collection.List.of(child)
        stubSnapshotQuery(null)

        val warnings = captureWarnings {
            result = resource.getWorkflowInstance("parent-id", true)
        }
        val view = result ?: error("Expected state machine view")

        assertThat(view.childWorkflows).isEmpty()
        assertThat(warnings.map { it.formattedMessage })
            .anyMatch {
                it.contains("Failed to build child state machine admin view") &&
                    it.contains("parentWorkflowId=parent-id") &&
                    it.contains("childWorkflowId=child-id")
            }
    }

    @Test
    fun `sendEvent returns 400 for terminal workflow`() {
        val instance = buildInstance(workflowId = "done-wf", status = WorkflowInstance.Status.COMPLETED)
        every { store.getWorkflow("done-wf") } returns Option.of(instance)

        val message = badRequestMessage {
            resource.sendEvent("done-wf", mapOf("eventClass" to "com.Foo"))
        }

        assertThat(message).contains("workflowId=done-wf")
        assertThat(message).contains("eventClassName=com.Foo")
    }

    @Test
    fun `sendEvent validates event payload and class`() {
        val snapshot = buildAdminSnapshot(
            validEventsPerState = mapOf("OPEN" to listOf(ParameterizedTestEvent::class.java)),
        )
        val instance = buildInstance(workflowId = "running-wf")
        every { store.getWorkflow("running-wf") } returns Option.of(instance)
        stubSnapshotQuery(snapshot)

        assertThat(badRequestMessage { resource.sendEvent("running-wf", mapOf("payload" to "x")) })
            .contains("workflowId=running-wf")
        assertThat(badRequestMessage { resource.sendEvent("running-wf", mapOf("eventClass" to TestEvent::class.java.name)) })
            .contains("workflowId=running-wf")
            .contains("eventClassName=${TestEvent::class.java.name}")
        assertThat(badRequestMessage { resource.sendEvent("running-wf", mapOf("eventClass" to "com.nonexistent.FakeEvent")) })
            .contains("workflowId=running-wf")
            .contains("eventClassName=com.nonexistent.FakeEvent")
        assertThat(
            badRequestMessage {
                resource.sendEvent(
                    "running-wf",
                    mapOf("eventClass" to ParameterizedTestEvent::class.java.name, "payload" to "not-a-valid-object"),
                )
            }
        )
            .contains("workflowId=running-wf")
            .contains("eventClassName=${ParameterizedTestEvent::class.java.name}")
    }

    @Test
    fun `sendEvent returns 400 when context class loader cannot load allowed event class`() {
        val snapshot = buildAdminSnapshot(
            validEventsPerState = mapOf("OPEN" to listOf(ParameterizedTestEvent::class.java)),
        )
        val instance = buildInstance(workflowId = "running-wf")
        val originalClassLoader = Thread.currentThread().contextClassLoader
        every { store.getWorkflow("running-wf") } returns Option.of(instance)
        stubSnapshotQuery(snapshot)

        val message = try {
            Thread.currentThread().contextClassLoader = object : ClassLoader(null) {}
            badRequestMessage {
                resource.sendEvent("running-wf", mapOf("eventClass" to ParameterizedTestEvent::class.java.name))
            }
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }

        assertThat(message).contains("workflowId=running-wf")
        assertThat(message).contains("eventClassName=${ParameterizedTestEvent::class.java.name}")
    }

    @Test
    fun `sendEvent succeeds with and without payload`() {
        val snapshot = buildAdminSnapshot(
            validEventsPerState = mapOf("OPEN" to listOf(ParameterizedTestEvent::class.java)),
        )
        val instance = buildInstance(workflowId = "running-wf")
        every { store.getWorkflow("running-wf") } returns Option.of(instance)
        every { skipperEngine.sendSignal(any()) } returns mockk(relaxed = true)
        stubSnapshotQuery(snapshot)

        val noPayload = resource.sendEvent(
            "running-wf",
            mapOf("eventClass" to ParameterizedTestEvent::class.java.name),
        )
        val withPayload = resource.sendEvent(
            "running-wf",
            mapOf("eventClass" to ParameterizedTestEvent::class.java.name, "payload" to mapOf("reason" to "test-reason")),
        )

        assertThat(noPayload["status"]).isEqualTo("ok")
        assertThat(withPayload["status"]).isEqualTo("ok")
        verify(exactly = 2) { skipperEngine.sendSignal(any()) }
    }

    @Test
    fun `sendEvent returns 400 when snapshot query fails`() {
        val instance = buildInstance(workflowId = "running-wf")
        every { store.getWorkflow("running-wf") } returns Option.of(instance)
        every { skipperEngine.invokeQueryMethod(any()) } throws RuntimeException("query failed")

        val message = badRequestMessage {
            resource.sendEvent("running-wf", mapOf("eventClass" to ParameterizedTestEvent::class.java.name))
        }

        assertThat(message).contains("workflowId=running-wf")
        assertThat(message).contains("eventClassName=${ParameterizedTestEvent::class.java.name}")
    }

    @Test
    fun `getValidEvents returns event schemas from snapshot`() {
        val snapshot = buildAdminSnapshot(
            validEventsPerState = mapOf(
                "OPEN" to listOf(ParameterizedTestEvent::class.java),
                "DONE" to listOf(TestEvent::class.java),
            ),
        )
        val instance = buildInstance()
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        stubSnapshotQuery(snapshot)

        val result = resource.getValidEvents("test-id")

        assertThat(result).containsKeys("OPEN", "DONE")
        assertThat(result["OPEN"]).hasSize(1)
        assertThat(result["OPEN"]!![0].eventSimpleName).isEqualTo("ParameterizedTestEvent")
        assertThat(result["OPEN"]!![0].parameters[0].name).isEqualTo("reason")
        assertThat(result["DONE"]!![0].parameters).isEmpty()
    }

    @Test
    fun `getValidEvents returns 400 on query failure`() {
        val instance = buildInstance()
        every { store.getWorkflow("test-id") } returns Option.of(instance)
        every { skipperEngine.invokeQueryMethod(any()) } throws RuntimeException("query failed")

        assertThatThrownBy { resource.getValidEvents("test-id") }
            .isInstanceOf(WebApplicationException::class.java)
            .satisfies({ ex ->
                assertThat((ex as WebApplicationException).response.status).isEqualTo(400)
            })
    }

    @Test
    fun `index returns StateMachineAdminView`() {
        val view = resource.index()

        assertThat(view).isInstanceOf(StateMachineAdminView::class.java)
        assertThat(view.templateName).endsWith("statemachine_admin.ftl")
        assertThat(view.charset).hasValue(StandardCharsets.UTF_8)
    }

    // ── Helpers ──

    private fun stubWorkflow(
        id: String,
        instance: WorkflowInstance,
        snapshot: StateMachineAdminSnapshot.AdminSnapshot? = null,
    ) {
        every { store.getWorkflow(id) } returns Option.of(instance)
        every { store.getActionCheckpoints(id) } returns io.vavr.collection.List.empty()
        stubSnapshotQuery(snapshot)
    }

    private fun captureWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(StateMachineAdminResource::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    private fun badRequestMessage(block: () -> Unit): String {
        try {
            block()
        } catch (e: WebApplicationException) {
            assertThat(e.response.status).isEqualTo(400)
            return e.response.entity.toString()
        }
        throw AssertionError("Expected a bad request response")
    }

    private fun stubSnapshotQuery(snapshot: StateMachineAdminSnapshot.AdminSnapshot?) {
        every { skipperEngine.invokeQueryMethod(any()) } answers {
            val request = args[0] as RunRequest
            when (request.workflowMethod) {
                "getAdminSnapshot" -> snapshot
                else -> throw IllegalStateException("unexpected query: ${request.workflowMethod}")
            }
        }
    }

    private fun buildAdminSnapshot(
        currentState: String? = null,
        stateEntryTime: SkipperStateMachine.PreciseTimestamp? = null,
        stateHistory: List<StateMachineAdminSnapshot.AdminStateEntry> = emptyList(),
        eventHistory: List<StateMachineAdminSnapshot.AdminEventEntry> = emptyList(),
        afterHookHistory: List<StateMachineAdminSnapshot.AfterHookExecutionEntry> = emptyList(),
        timeoutHistory: List<StateMachineAdminSnapshot.TimeoutExecutionEntry> = emptyList(),
        transitionHistory: List<SkipperStateMachine.TransitionLogEntry> = emptyList(),
        validEventsPerState: Map<String, List<Class<*>>> = emptyMap(),
        pendingTimerDeadlines: List<StateMachineAdminSnapshot.AdminPendingTimer> = emptyList(),
    ) = StateMachineAdminSnapshot.AdminSnapshot(
        currentState = currentState,
        stateEntryTime = stateEntryTime,
        stateHistory = stateHistory,
        eventHistory = eventHistory,
        afterHookHistory = afterHookHistory,
        timeoutHistory = timeoutHistory,
        transitionHistory = transitionHistory,
        validEventsPerState = validEventsPerState,
        pendingTimerDeadlines = pendingTimerDeadlines,
    )

    private fun buildInstance(
        workflowId: String = "test-id",
        workflowClass: Class<out Workflow> = TestStateMachine::class.java,
        status: WorkflowInstance.Status = WorkflowInstance.Status.RUNNING,
        parentWorkflowId: String? = null,
        input: Any? = null,
        state: VavrMap<String, Any?> = HashMap.empty(),
        createdAt: Instant? = null,
        updatedAt: Instant? = null,
    ): WorkflowInstance =
        WorkflowInstance.builder()
            .workflowId(workflowId)
            .workflowClass(workflowClass)
            .workflowMethod("execute")
            .input(input)
            .requestContext(mockk(relaxed = true))
            .extraRequestData(mockk(relaxed = true))
            .state(state)
            .result(CompletableFuture.completedFuture(null))
            .version(1)
            .status(status)
            .parentWorkflowId(parentWorkflowId)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build()

    private fun tsFromMs(ms: Long) = SkipperStateMachine.PreciseTimestamp(ms / 1000, (ms % 1000).toInt() * 1_000_000)

    private fun ptr(
        startSec: Long,
        startNano: Int,
        endSec: Long,
        endNano: Int,
    ) = SkipperStateMachine.PreciseTimeRange(
        SkipperStateMachine.PreciseTimestamp(startSec, startNano),
        SkipperStateMachine.PreciseTimestamp(endSec, endNano),
    )

    private fun buildCheckpoint(
        actionClass: Class<*> = SomeActions::class.java,
        actionMethod: String = "doSomething",
        iteration: Long = 0,
        startTimeMs: Long = 1000,
        endTimeMs: Long? = 1100,
        result: Either<Throwable, Any?> = Either.right(null),
        input: Any? = null,
    ): ActionCheckpoint =
        ActionCheckpoint.builder()
            .checkpointTag(
                CheckpointTag.builder()
                    .workflowId("test-id")
                    .actionClass(actionClass)
                    .actionMethod(actionMethod)
                    .iteration(iteration)
                    .build(),
            )
            .executionStartTime(Instant.ofEpochMilli(startTimeMs))
            .executionEndTime(endTimeMs?.let { Instant.ofEpochMilli(it) })
            .result(result)
            .input(input)
            .build()
}
