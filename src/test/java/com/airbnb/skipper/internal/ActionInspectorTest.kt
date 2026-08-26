package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ActionInspectorTest {
    @Test
    fun testHasCompensationMethod_withCompensableAction() {
        val inspector = ActionInspector(TestCompensableActions::class.java)
        assertTrue(inspector.hasCompensationMethod("processPayment"))
        assertTrue(inspector.hasCompensationMethod("reserveInventory"))
    }

    @Test
    fun testHasCompensationMethod_withNonCompensableAction() {
        val inspector = ActionInspector(TestCompensableActions::class.java)
        assertFalse(inspector.hasCompensationMethod("sendNotification"))
    }

    @Test
    fun testHasCompensationMethod_withNonExistentMethod() {
        val inspector = ActionInspector(TestCompensableActions::class.java)
        assertFalse(inspector.hasCompensationMethod("nonExistentMethod"))
    }

    @Test
    fun testConstructorWithActionObject() {
        val actionObject = TestCompensableActions()
        val inspector = ActionInspector(actionObject)
        assertTrue(inspector.hasCompensationMethod("processPayment"))
    }

    @Test
    fun testNoCompensationMethods() {
        val inspector = ActionInspector(TestNonCompensableActions::class.java)
        assertFalse(inspector.hasCompensationMethod("regularAction"))
    }

    // Test action classes
    private class TestCompensableActions : Actions() {
        @Execute(checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT)
        fun processPayment(paymentId: String): String = "Payment processed: $paymentId"

        @Compensate(forExecute = "processPayment")
        fun refundPayment(paymentId: String) {
            // Compensation logic for payment processing
        }

        @Execute
        fun reserveInventory(itemId: String): String = "Inventory reserved: $itemId"

        @Compensate(forExecute = "reserveInventory")
        fun releaseInventory(itemId: String) {
            // Compensation logic for inventory reservation
        }

        @Execute(checkpointMode = CheckpointMode.NO_CHECKPOINT)
        fun sendNotification(message: String) {
            // Send notification - not compensable
        }
    }

    private class TestNonCompensableActions : Actions() {
        @Execute
        fun regularAction(data: String): String = "Processed: $data"

        @Execute
        fun anotherAction() {
            // Another regular action
        }
    }

    /** Base actions class with compensation defined at the base level. */
    private open class BaseActions : Actions() {
        @Execute
        fun processPayment(paymentId: String): String = "Payment processed: $paymentId"
    }

    /** Concrete actions that inherits @Execute from base and adds @Compensate. */
    private class ConcreteActionsWithInheritedExecute : BaseActions() {
        @Compensate(forExecute = "processPayment")
        fun refundPayment(paymentId: String) = Unit
    }

    /** Concrete actions that adds @Execute and inherits @Compensate from base. */
    private open class BaseActionsWithCompensate : Actions() {
        @Execute
        fun doWork(input: String): String = input

        @Compensate(forExecute = "doWork")
        fun undoWork(input: String) = Unit
    }

    private class ConcreteActionsExtendingCompensate : BaseActionsWithCompensate() {
        @Execute
        fun doMoreWork(input: String): String = input
    }

    @Nested
    inner class InheritedAnnotationDiscovery {
        @Test
        fun testCompensationMethodOnConcreteFoundForBaseExecuteMethod() {
            val inspector = ActionInspector(ConcreteActionsWithInheritedExecute::class.java)
            assertTrue(inspector.hasCompensationMethod("processPayment"))
            assertTrue(inspector.getCompensationMethod("processPayment").isDefined)
        }

        @Test
        fun testCompensationMethodOnBaseFoundForBaseExecuteMethod() {
            val inspector = ActionInspector(ConcreteActionsExtendingCompensate::class.java)
            assertTrue(inspector.hasCompensationMethod("doWork"))
            assertTrue(inspector.getCompensationMethod("doWork").isDefined)
        }

        @Test
        fun testNoCompensationForConcreteOnlyExecuteMethod() {
            val inspector = ActionInspector(ConcreteActionsExtendingCompensate::class.java)
            assertFalse(inspector.hasCompensationMethod("doMoreWork"))
        }
    }
}
