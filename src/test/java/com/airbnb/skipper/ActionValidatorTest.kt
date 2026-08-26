package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.SmartSerde
import io.vavr.control.Option
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActionValidatorTest {
    private lateinit var actionValidator: ActionValidator

    @BeforeEach
    fun setUp() {
        actionValidator = ActionValidator(SmartSerde())
    }

    @Test
    fun testActionCreationActionMustHaveAtLeastOneExecuteMethod() {
        val actions = object : Actions() {
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assert(errors.size == 1)
        assertThat(errors[0]).contains("Action class ${actions.javaClass.name} must have at least one method annotated with @Execute")
    }

    @Test
    fun testActionMethodOverloadNotAllowed() {
        val actions = object : Actions() {
            @Execute fun someMethod() {
                throw UnsupportedOperationException()
            }

            @Execute fun someMethod(int: Int) {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assert(errors.size == 1)
        assertThat(
            errors[0]
        ).contains("Action class ${actions.javaClass.name} contains multiple methods with the same name annotated with @Execute or @Compensate")
    }

    @Test
    fun testExecuteMethodMustHaveAtMostOneCompensationMethod() {
        val actions = object : Actions() {
            @Execute fun someMethod(input: String) {
                throw UnsupportedOperationException()
            }

            @Compensate(forExecute = "someMethod")
            fun compensateMethod(input: String) {
                throw UnsupportedOperationException()
            }

            @Compensate(forExecute = "someMethod")
            fun compensateMethod2(input: String) {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(
            errors[0]
        ).contains(
            "Action class ${actions.javaClass.name} contains multiple methods with the same name annotated with @Compensate for execute method someMethod"
        )
    }

    @Test
    fun testValidateActionsHappyPath() {
        val actions = object : Actions() {
            @Execute fun someMethod(input: String) {
                throw UnsupportedOperationException()
            }

            @Compensate(forExecute = "someMethod")
            fun compensateMethod(input: String) {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testValidateMethodLevelRetryStrategy() {
        val actions = object : Actions() {
            val retryPolicy = FixedRetryStrategy(Duration.ofSeconds(5), 4)

            @Execute(retryStrategy = "retryPolicy")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testValidateMethodLevelRetryStrategyWhenFieldDoesNotExistInActionClass() {
        val actions = object : Actions() {
            @Execute(retryStrategy = "invalidRetryPolicy")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("invalidRetryPolicy is not a valid field name in action")
    }

    @Test
    fun testValidateMethodLevelRetryStrategyWhenFieldIsNotOfTypeRetryStrategy() {
        val actions = object : Actions() {
            val retryPolicy: String = "test"

            @Execute(retryStrategy = "retryPolicy")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("retryStrategy field retryPolicy must implement RetryStrategy interface")
    }

    @Test
    fun testValidateMethodWhenActionMethodReturnsNonSerializableType() {
        val actions = object : Actions() {
            @Execute fun someMethod(): Option<String> {
                throw UnsupportedOperationException()
            }

            @Execute fun someMethodVoid() {
                throw UnsupportedOperationException()
            }

            @Execute fun someAsyncMethod(): CompletableFuture<String> {
                throw UnsupportedOperationException()
            }

            @Execute fun someAsyncMethodVoidRet(): CompletableFuture<Void> {
                throw UnsupportedOperationException()
            }

            @Execute fun someAsyncMethodWithInvalidRetType(): CompletableFuture<Option<String>> {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(2, errors.size)
        // Check that both expected errors are present, regardless of order
        // Use method name only to avoid issues with anonymous class name generation
        assertThat(errors).anyMatch { it.contains(".someMethod return type is not serializable") }
        assertThat(errors).anyMatch {
            it.contains(".someAsyncMethodWithInvalidRetType return type is not serializable")
        }
    }

    @Test
    fun testCompensableActionMethodMustHaveExactlyOneParameter() {
        val actions = object : Actions() {
            @Execute fun processPayment(): String {
                return "payment processed"
            }

            @Compensate(forExecute = "processPayment")
            fun refundPayment() {
                // Compensation logic
            }

            @Execute fun processOrder(
                orderId: String,
                customerId: String
            ): String {
                return "order processed"
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder(
                orderId: String,
                customerId: String
            ) {
                // Compensation logic
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(errors.size >= 2)
        assertTrue(
            errors.any {
                it.contains(
                    "Action method ${actions.javaClass.name}.processPayment has compensation but must have exactly 1 input parameter, found 0"
                )
            }
        )
        assertTrue(
            errors.any {
                it.contains("Action method ${actions.javaClass.name}.processOrder has compensation but must have exactly 1 input parameter, found 2")
            }
        )
    }

    @Test
    fun testCompensationMethodInputTypeMustMatchActionMethod() {
        val actions = object : Actions() {
            @Execute fun processPayment(paymentId: String): String {
                return "payment processed"
            }

            @Compensate(forExecute = "processPayment")
            fun refundPayment(paymentId: Int) {
                // Compensation logic with wrong parameter type
            }

            @Execute fun processOrder(orderId: String): String {
                return "order processed"
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder() {
                // Compensation logic with no parameters
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(2, errors.size)
        assertTrue(
            errors.any {
                it.contains(
                    "Compensation method ${actions.javaClass.name}.refundPayment first parameter type int must match action method processPayment input type java.lang.String"
                )
            }
        )
        assertTrue(
            errors.any { it.contains("Compensation method ${actions.javaClass.name}.cancelOrder must have 1 or 2 input parameters, found 0") }
        )
    }

    @Test
    fun testCompensationMethodMustReturnVoid() {
        val actions = object : Actions() {
            @Execute fun processPayment(paymentId: String): String {
                return "payment processed"
            }

            @Compensate(forExecute = "processPayment")
            fun refundPayment(paymentId: String): String {
                return "payment refunded"
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("Compensation method ${actions.javaClass.name}.refundPayment must return void, found java.lang.String")
    }

    @Test
    fun testValidCompensableAction() {
        val actions = object : Actions() {
            @Execute fun processPayment(paymentId: String): String {
                return "payment processed"
            }

            @Compensate(forExecute = "processPayment")
            fun refundPayment(paymentId: String) {
                // Valid compensation logic
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size)
    }

    @Test
    fun testValidCompensableActionWithTwoParameters() {
        val actions = object : Actions() {
            @Execute fun processOrder(orderId: String): String {
                return "order processed"
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder(
                orderId: String,
                result: String
            ) {
                // Valid compensation logic with two parameters
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size)
    }

    @Test
    fun testValidCompensableActionWithAsyncReturnType() {
        val actions = object : Actions() {
            @Execute fun processPayment(paymentId: String): CompletableFuture<String> {
                return CompletableFuture.completedFuture("payment processed")
            }

            @Compensate(forExecute = "processPayment")
            fun refundPayment(
                paymentId: String,
                result: String
            ) {
                // Valid compensation logic with async return type
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size)
    }

    @Test
    fun testCompensationMethodSecondParameterTypeMismatch() {
        val actions = object : Actions() {
            @Execute fun processOrder(orderId: String): String {
                return "order processed"
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder(
                orderId: String,
                result: Int
            ) {
                // Invalid compensation logic - second parameter type doesn't match return type
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertTrue(
            errors.any {
                it.contains(
                    "Compensation method ${actions.javaClass.name}.cancelOrder second parameter type int must match the unwrapped return type of action method processOrder"
                ) && it.contains("so compensation method should expect java.lang.String")
            }
        )
    }

    @Test
    fun testCompensationMethodWithTooManyParameters() {
        val actions = object : Actions() {
            @Execute fun processOrder(orderId: String): String {
                return "order processed"
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder(
                orderId: String,
                result: String,
                extraParam: Int
            ) {
                // Invalid compensation logic - too many parameters
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertTrue(
            errors.any {
                it.contains(
                    "Compensation method ${actions.javaClass.name}.cancelOrder must have 1 or 2 input parameters, found 3"
                )
            }
        )
    }

    @Test
    fun testCompensationMethodWithVoidReturnTypeSecondParameter() {
        val actions = object : Actions() {
            @Execute fun processOrder(orderId: String) {
                // Action with void return type
            }

            @Compensate(forExecute = "processOrder")
            fun cancelOrder(
                orderId: String,
                result: String
            ) {
                // Should be valid even though action returns void - second parameter is ignored for void returns
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size)
    }

    @Test
    fun testCompensationMethodWithCompletableFutureReturnType() {
        val actions = object : Actions() {
            @Execute fun processPaymentAsync(paymentId: String): CompletableFuture<String> {
                return CompletableFuture.completedFuture("payment processed")
            }

            @Compensate(forExecute = "processPaymentAsync")
            fun refundPaymentCorrect(
                paymentId: String,
                result: String // Should be String, not CompletableFuture<String>
            ) {
                // Valid compensation - second parameter type matches unwrapped return type
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size)
    }

    @Test
    fun testCompensationMethodWithIncorrectCompletableFutureParameterType() {
        val actions = object : Actions() {
            @Execute fun processPaymentAsync(paymentId: String): CompletableFuture<String> {
                return CompletableFuture.completedFuture("payment processed")
            }

            @Compensate(forExecute = "processPaymentAsync")
            fun refundPaymentIncorrect(
                paymentId: String,
                result: CompletableFuture<String> // Should be String, not CompletableFuture<String>
            ) {
                // Invalid compensation - second parameter should be unwrapped type
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        val error = errors.first()
        assertTrue(error.contains("second parameter type java.util.concurrent.CompletableFuture must match the unwrapped return type"))
        assertTrue(error.contains("CompletableFuture<String> (unwrapped to String)"))
        assertTrue(error.contains("so compensation method should expect java.lang.String"))
        assertTrue(error.contains("Note: For CompletableFuture<T> return types, the compensation parameter should be T, not CompletableFuture<T>"))
    }

    @Test
    fun testValidateMethodLevelExceptionClassifierWhenFieldExists() {
        val actions = object : Actions() {
            val customClassifier = object : com.airbnb.skipper.internal.ExceptionClassifier {
                override fun isRetryable(throwable: Throwable): Boolean = false
            }

            @Execute(exceptionClassifier = "customClassifier")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testValidateMethodLevelExceptionClassifierWhenFieldDoesNotExist() {
        val actions = object : Actions() {
            @Execute(exceptionClassifier = "nonExistentClassifier")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("nonExistentClassifier is not a valid field name in action")
    }

    @Test
    fun testValidateMethodLevelExceptionClassifierWhenFieldIsNotOfTypeExceptionClassifier() {
        val actions = object : Actions() {
            val customClassifier: String = "test"

            @Execute(exceptionClassifier = "customClassifier")
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("exceptionClassifier field customClassifier must implement ExceptionClassifier interface")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suspend function (Kotlin coroutine) validation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testSuspendExecuteMethodWithSerializableReturnTypePassesValidation() {
        // Suspend functions have bytecode return type Object. The validator must extract
        // the real return type from Kotlin metadata and validate that instead.
        // String is serializable, so no errors expected.
        val actions = object : Actions() {
            @Execute
            suspend fun doWork(input: String): String = throw UnsupportedOperationException()
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun testSuspendExecuteMethodWithNonSerializableReturnTypeFailsValidation() {
        // The validator must extract the real return type (Option<String>) from Kotlin
        // metadata, not the bytecode return type (Object), and correctly reject it.
        val actions = object : Actions() {
            @Execute
            suspend fun doWork(input: String): Option<String> = throw UnsupportedOperationException()
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains(".doWork return type is not serializable")
    }

    @Test
    fun testSuspendCompensableActionHappyPath() {
        // A suspend @Execute with 1 user param paired with a suspend @Compensate returning Unit
        // (which has bytecode return type Object) should pass all validation rules.
        val actions = object : Actions() {
            @Execute
            suspend fun processPayment(paymentId: String): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "processPayment")
            suspend fun refundPayment(paymentId: String) = Unit
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size, "Expected no errors but got: $errors")
    }

    @Test
    fun testSuspendCompensationMethodReturningUnitPassesVoidCheck() {
        // A suspend @Compensate method has bytecode return type Object (not void).
        // The validator must check Kotlin metadata and accept Unit as equivalent to void.
        val actions = object : Actions() {
            @Execute
            suspend fun doWork(input: String): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "doWork")
            suspend fun undoWork(input: String): Unit = throw UnsupportedOperationException()
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertTrue(
            errors.none { it.contains("must return void") },
            "suspend @Compensate returning Unit should not trigger void-return error, but got: $errors"
        )
    }

    @Test
    fun testSuspendCompensableActionWithResultParameterHappyPath() {
        // A suspend @Execute returning String paired with a suspend @Compensate that
        // accepts (input: String, result: String) should pass — the validator must extract
        // the Kotlin return type to validate the second compensation parameter.
        val actions = object : Actions() {
            @Execute
            suspend fun processOrder(orderId: String): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "processOrder")
            suspend fun cancelOrder(
                orderId: String,
                result: String
            ) = Unit
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(0, errors.size, "Expected no errors but got: $errors")
    }

    @Test
    fun testSuspendCompensableExecuteMethodWithZeroUserParamsFailsValidation() {
        // validateCompensableActionParameters: a compensable @Execute must have exactly 1
        // user input parameter so the compensation can receive the original input.
        // A suspend @Execute with no user params has bytecode param count 1 (just Continuation),
        // but getUserParameterCount() must report 0 user params and produce an error.
        val actions = object : Actions() {
            @Execute
            suspend fun process(): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "process")
            suspend fun undo(input: String) = Unit
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertThat(errors.any { it.contains("must have exactly 1 input parameter") }).isTrue()
    }

    @Test
    fun testSuspendCompensationSecondParamTypeMismatchFailsValidation() {
        // validateCompensationMethodParameterTypes: when the suspend @Compensate has 2 user
        // params, the second param type must match the @Execute return type extracted from
        // Kotlin metadata (not the bytecode Object type). A type mismatch must be reported.
        val actions = object : Actions() {
            @Execute
            suspend fun processOrder(orderId: String): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "processOrder")
            suspend fun cancelOrder(
                orderId: String,
                result: Int // Int != String — mismatch
            ) = Unit
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("second parameter type")
        assertThat(errors[0]).contains("must match the unwrapped return type")
    }

    @Test
    fun testSuspendCompensationMethodReturningNonUnitFailsVoidCheck() {
        // validateCompensationMethodReturnType: a @Compensate method must return void/Unit.
        // A suspend @Compensate that returns a non-Unit type (String here) must fail the check.
        // This verifies that getEffectiveReturnType() correctly extracts the Kotlin-declared return
        // type from metadata and does not fall back to the bytecode Object type (which would
        // incorrectly trigger a false-positive for all suspend compensators).
        val actions = object : Actions() {
            @Execute
            suspend fun processOrder(orderId: String): String = throw UnsupportedOperationException()

            @Compensate(forExecute = "processOrder")
            suspend fun cancelOrder(orderId: String): String = throw UnsupportedOperationException()
        }
        val errors = actionValidator.validateCreation(actions.javaClass)
        assertThat(errors.any { it.contains("must return void") }).isTrue()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inherited annotation discovery
    // ──────────────────────────────────────────────────────────────────────────

    open class BaseActionsForValidation : Actions() {
        @Execute open fun baseExecute(input: String): String = throw UnsupportedOperationException()
    }

    open class ConcreteActionsForValidation : BaseActionsForValidation() {
        @Execute open fun concreteExecute(input: String): String = throw UnsupportedOperationException()

        @Compensate(forExecute = "baseExecute")
        open fun compensateBase(input: String) {}
    }

    @Test
    fun testInheritedExecuteMethodIsDiscoveredByValidator() {
        val errors = actionValidator.validateCreation(ConcreteActionsForValidation::class.java)
        // Should find both baseExecute (inherited) and concreteExecute (declared)
        // No "must have at least one @Execute" error
        assertThat(errors).noneMatch { it.contains("must have at least one method annotated with @Execute") }
    }

    @Test
    fun testInheritedExecuteWithConcreteCompensatePassesValidation() {
        val errors = actionValidator.validateCreation(ConcreteActionsForValidation::class.java)
        // @Compensate on concrete references @Execute on base — should not be orphan
        assertThat(errors).noneMatch { it.contains("no corresponding method annotated with @Execute") }
    }

    open class BaseActionsWithRetryField : Actions() {
        val customRetry = FixedRetryStrategy(java.time.Duration.ofSeconds(1), 3)

        @Execute(retryStrategy = "customRetry")
        open fun retryableAction(): String = throw UnsupportedOperationException()
    }

    open class ConcreteActionsWithInheritedRetryField : BaseActionsWithRetryField() {
        @Execute open fun anotherAction(): String = throw UnsupportedOperationException()
    }

    @Test
    fun testInheritedRetryStrategyFieldIsDiscoveredByValidator() {
        val errors = actionValidator.validateCreation(ConcreteActionsWithInheritedRetryField::class.java)
        // retryStrategy field "customRetry" is on the base class — should be found
        assertThat(errors).noneMatch { it.contains("is not a valid field name") }
    }
}
