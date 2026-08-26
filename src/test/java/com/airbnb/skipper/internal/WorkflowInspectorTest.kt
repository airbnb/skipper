package com.airbnb.skipper.internal

import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.ValidationError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import io.vavr.collection.Map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WorkflowInspectorTest {
    @Test
    fun testValidateWorkflow() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        inspector.validate()
    }

    @Test
    fun testWorkflowMethodCannotHaveMoreThanOneParameter() {
        val instance = TestWorkflow2()
        val inspector = WorkflowInspector(instance)
        val error = assertThrows(ValidationError::class.java) { inspector.validate() }
        assert(error.message!!.contains("must have at most one parameter"))
    }

    @Test
    fun testSignalMethodCannotHaveMoreThanOneParameter() {
        val instance = TestWorkflow3()
        val inspector = WorkflowInspector(instance)
        val error = assertThrows(ValidationError::class.java) { inspector.validate() }
        assert(error.message!!.contains("must have at most one parameter"))
    }

    @Test
    fun testGetWorkflowMethods() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        assert(inspector.getWorkflowMethods().size() == 1)
        assertEquals("testMethod", inspector.getWorkflowMethods().get(0).name)
    }

    @Test
    fun testGetSignalMethods() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        assert(inspector.getSignalMethods().size() == 2)
        // Method ordering from reflection is not guaranteed, so check that both methods are present
        val methodNames = inspector.getSignalMethods().map { m -> m.name }.toJavaSet()
        assertTrue(methodNames.contains("signalMethod"))
        assertTrue(methodNames.contains("signalMethod2"))
    }

    @Test
    fun testSignalMethod() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        val method = inspector.getSignalMethod("signalMethod")
        assertEquals("signalMethod", method.name)
        val error =
            assertThrows(ValidationError::class.java) { inspector.getSignalMethod("notSignalMethod") }
        assert(error.message!!.contains("no method with name notSignalMethod"))
    }

    @Test
    fun testGetWorkflowState() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        val state = inspector.getState()
        assertEquals(1, state.size())
        assertTrue(state.containsKey("stateField"))
        assertEquals("defaultValue", state.get("stateField").get())
    }

    @Test
    fun testGetWorkflowMethod() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        val method = inspector.getWorkflowMethod("testMethod")
        assertEquals("testMethod", method.name)
        val error =
            assertThrows(ValidationError::class.java) { inspector.getWorkflowMethod("notWorkflowMethod") }
        assert(error.message!!.contains("no method with name notWorkflowMethod"))
    }

    @Test
    fun testSetWorkflowState() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        var state: Map<String, Any?> = inspector.getState()
        state = state.put("stateField", "newValue")
        inspector.setState(state)
        assertEquals("newValue", instance.stateField)
        // Unknown __-prefixed fields should be silently skipped to support rolling deploys
        state = state.put("__unknownFrameworkField", "newValue")
        inspector.setState(state)
        // The known field should still have been set correctly
        assertEquals("newValue", instance.stateField)
        // Try setting a field with a different type
        state = state.remove("__unknownFrameworkField")
        state = state.put("stateField", 1)
        val finalState2 = state
        val invalidTypeError =
            assertThrows(ValidationError::class.java) { inspector.setState(finalState2) }
        assert(invalidTypeError.message!!.contains("unable to set state field stateField"))
    }

    @Test
    fun testSetStateSkipsUnknownKeysForRollingDeploySafety() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        // Simulate a state map that was persisted by a newer version of the code
        // containing fields that this version doesn't know about (e.g. __childWorkflowState)
        val stateWithUnknownKeys: Map<String, Any?> =
            inspector
                .getState()
                .put("__childWorkflowState", "someSerializedData")
                .put("__futureField", 42)
        // Should NOT throw — unknown keys are skipped with a warning
        inspector.setState(stateWithUnknownKeys)
        // Known fields should be unaffected
        assertEquals("defaultValue", instance.stateField)
    }

    @Test
    fun testSetState_unknownUserFieldIsSkippedForWorkflowEvolution() {
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        // Unknown user-defined keys should be skipped (not thrown) to support workflow evolution
        // where a @StateField was removed from the current code version but still exists in storage
        val stateWithRemovedField: Map<String, Any?> =
            inspector.getState().put("removedField", "value").put("anotherRemovedField", 42)
        // Should NOT throw — unknown keys are logged and skipped
        inspector.setState(stateWithRemovedField)
        // Known fields should still be set correctly
        assertEquals("defaultValue", instance.stateField)
    }

    @Test
    fun testSetState_missingFieldRetainsDefault_afterAddingNewStateField() {
        // Simulates: a new @StateField was added to the code, but in-flight instances
        // have stored state that doesn't contain the new field
        val instance = WorkflowWithNewField()
        val inspector = WorkflowInspector(instance)
        // Stored state from before the new field was added — only contains the original field
        val oldStoredState: Map<String, Any?> = io.vavr.collection.HashMap.of("originalField", "stored")
        inspector.setState(oldStoredState)
        // Original field should be set from stored state
        assertEquals("stored", instance.originalField)
        // New nullable field should retain its default (null) since it wasn't in stored state
        assertEquals(null, instance.newNullableField)
        // New primitive field should retain its default (0) since it wasn't in stored state
        assertEquals(0, instance.newPrimitiveField)
    }

    @Test
    fun testSetState_removedFieldIsSkipped_existingFieldsUnaffected() {
        // Simulates: a @StateField was removed from the code, but stored state still contains it
        val instance = TestWorkflow()
        val inspector = WorkflowInspector(instance)
        // Stored state from before the field was removed — contains a key that no longer exists
        val stateWithRemovedField: Map<String, Any?> =
            io.vavr.collection.HashMap.of<String, Any?>("stateField", "updated")
                .put("removedField", "orphaned-value")
        // Should NOT throw — the unknown "removedField" is logged and skipped
        inspector.setState(stateWithRemovedField)
        // The known field should be updated normally
        assertEquals("updated", instance.stateField)
    }

    private class TestWorkflow : Workflow() {
        var notStateField: String? = null

        @StateField var stateField: String = "defaultValue"

        @WorkflowMethod
        fun testMethod() = Unit

        @SignalMethod
        fun signalMethod(one: String) = Unit

        @SignalMethod
        fun signalMethod2() = Unit

        fun notWorkflowMethod() = Unit
    }

    private class WorkflowWithNewField : Workflow() {
        @StateField var originalField: String = "default"

        @StateField var newNullableField: String? = null

        @StateField var newPrimitiveField: Int = 0

        @WorkflowMethod
        fun execute() = Unit
    }

    private class TestWorkflow2 : Workflow() {
        @StateField var stateField: String? = null

        @StateField var stateField2: String? = null

        @WorkflowMethod
        fun invalidWorkflowMethod(
            one: String,
            two: String
        ) = Unit
    }

    private class TestWorkflow3 : Workflow() {
        @WorkflowMethod
        fun workflowMethod(one: String) = Unit

        @SignalMethod
        fun invalidSignalMethod(
            one: String,
            two: Int
        ) = Unit
    }

    // ── Fixtures for inherited annotation tests ──

    /**
     * An intermediate base class that defines @SignalMethod, @QueryMethod, and @StateField. Simulates
     * the SkipperStateMachine pattern where annotations live on a base class.
     */
    private abstract class BaseStateMachineWorkflow : Workflow() {
        @JvmField @StateField
        final var baseState: String = "initial"

        @JvmField @StateField
        final var baseCounter: Int = 0

        @SignalMethod
        fun sendEvent(event: String) {
            baseState = event
        }

        @QueryMethod
        fun getBaseState(): String = baseState

        @QueryMethod
        fun getBaseCounter(): Int = baseCounter
    }

    /** Concrete workflow that adds its own annotations on top of the inherited ones. */
    private open class ConcreteStateMachineWorkflow : BaseStateMachineWorkflow() {
        @JvmField @StateField
        final var concreteField: String = "concrete"

        @WorkflowMethod
        fun execute() = Unit

        @QueryMethod
        fun getConcreteField(): String = concreteField
    }

    /** Concrete workflow with no extra annotations — relies entirely on the base class. */
    private class MinimalConcreteWorkflow : BaseStateMachineWorkflow() {
        @WorkflowMethod
        fun run() = Unit
    }

    // ── Tests for inherited annotation discovery ──

    @Nested
    inner class InheritedAnnotationDiscovery {
        @Test
        fun testInheritedSignalMethodsAreDiscovered() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            assertEquals(1, inspector.getSignalMethods().size())
            assertEquals("sendEvent", inspector.getSignalMethods().get(0).name)
        }

        @Test
        fun testInheritedQueryMethodsAreDiscovered() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            val queryNames = inspector.getQueryMethods().map { m -> m.name }.toJavaSet()
            // 2 from base + 1 from concrete
            assertEquals(3, queryNames.size)
            assertTrue(queryNames.contains("getBaseState"))
            assertTrue(queryNames.contains("getBaseCounter"))
            assertTrue(queryNames.contains("getConcreteField"))
        }

        @Test
        fun testInheritedStateFieldsAreDiscovered() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            val state = inspector.getState()
            // 2 from base + 1 from concrete
            assertEquals(3, state.size())
            assertTrue(state.containsKey("baseState"))
            assertTrue(state.containsKey("baseCounter"))
            assertTrue(state.containsKey("concreteField"))
            assertEquals("initial", state.get("baseState").get())
            assertEquals(0, state.get("baseCounter").get())
            assertEquals("concrete", state.get("concreteField").get())
        }

        @Test
        fun testSetStateWorksForInheritedFields() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            var state: Map<String, Any?> = inspector.getState()
            state = state.put("baseState", "updated").put("concreteField", "also updated")
            inspector.setState(state)
            assertEquals("updated", instance.baseState)
            assertEquals("also updated", instance.concreteField)
        }

        @Test
        fun testGetSignalMethodByNameFindsInheritedMethod() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            val method = inspector.getSignalMethod("sendEvent")
            assertEquals("sendEvent", method.name)
        }

        @Test
        fun testGetQueryMethodByNameFindsInheritedMethod() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            val method = inspector.getQueryMethod("getBaseState")
            assertEquals("getBaseState", method.name)
        }

        @Test
        fun testMinimalWorkflowWithOnlyInheritedAnnotationsValidates() {
            val instance = MinimalConcreteWorkflow()
            val inspector = WorkflowInspector(instance)
            // Should not throw — @WorkflowMethod is on concrete, @SignalMethod on base
            inspector.validate()
        }

        @Test
        fun testMinimalWorkflowInheritsAllBaseAnnotations() {
            val instance = MinimalConcreteWorkflow()
            val inspector = WorkflowInspector(instance)
            assertEquals(1, inspector.getWorkflowMethods().size())
            assertEquals(1, inspector.getSignalMethods().size())
            assertEquals(2, inspector.getQueryMethods().size())
            assertEquals(2, inspector.getState().size())
        }

        @Test
        fun testWorkflowMethodOnConcreteClassIsDiscovered() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            assertEquals(1, inspector.getWorkflowMethods().size())
            assertEquals("execute", inspector.getWorkflowMethods().get(0).name)
        }

        @Test
        fun testValidatePassesWithInheritedAnnotations() {
            val instance = ConcreteStateMachineWorkflow()
            val inspector = WorkflowInspector(instance)
            // Should not throw — has @WorkflowMethod (concrete) and @SignalMethod (base)
            inspector.validate()
        }
    }
}
