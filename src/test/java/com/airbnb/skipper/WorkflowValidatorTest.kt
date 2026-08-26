package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.SmartSerde
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkflowValidatorTest {
    private lateinit var workflowValidator: WorkflowValidator

    @BeforeEach
    fun setUp() {
        workflowValidator = WorkflowValidator(SmartSerde())
    }

    @Test
    fun testWorkflowCreationWorkflowMustHaveAtLeastOneWorkflowMethod() {
        val workflow: Workflow = object : Workflow() {
            fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.size == 1)
        assertThat(errors[0]).contains("Workflow class ${workflow.javaClass.name} must have at least one method annotated with @WorkflowMethod")
    }

    @Test
    fun testWorkflowCreationWorkflowMethodOverloadNotAllowed() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod() {
                throw UnsupportedOperationException()
            }

            @WorkflowMethod fun someMethod(int: Int) {
                throw UnsupportedOperationException()
            }
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(
            errors[0]
        ).contains(
            "Workflow class ${workflow.javaClass.name} contains multiple methods with the same name annotated with @WorkflowMethod or @SignalMethod"
        )
    }

    @Test
    fun testWorkflowCreationWorkflowSignalOverloadNotAllowed() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod() {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod() {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod(int: Int) {
                throw UnsupportedOperationException()
            }
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(
            errors[0]
        ).contains(
            "Workflow class ${workflow.javaClass.name} contains multiple methods with the same name annotated with @WorkflowMethod or @SignalMethod"
        )
    }

    @Test
    fun testWorkflowCreationWorkflowMethodsMustHaveAtMostOneArgument() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(
                int: Int,
                int2: Int
            ) {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod(
                int: Int,
                int2: Int
            ) {
                throw UnsupportedOperationException()
            }
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(2, errors.size)
        // Error ordering from reflection is not guaranteed, so check that both errors are present
        assertThat(errors).allMatch { it.contains("must have at most one argument") }
    }

    @Test
    fun testWorkflowCreationWorkflowStateFieldsMustBeSerializable() {
        val workflow: Workflow = object : Workflow() {
            @StateField val nonSerializableField: Optional<String> = Optional.empty()

            @WorkflowMethod fun someMethod() {
                throw UnsupportedOperationException()
            }
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("Workflow class ${workflow.javaClass.name} contains non-serializable fields")
    }

    @Test
    fun testWorkflowInvocationInputMustBeSerializable() {
        data class NonSerializable<T>(val value: T? = null)
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(nonSerializableField: NonSerializable<String>) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, NonSerializable("test"))
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("The argument for ${workflow.javaClass.name}.someMethod is not serializable")
    }

    @Test
    fun testHappyPath() {
        val workflow: Workflow = object : Workflow() {
            @StateField var sField: String = "test"

            @WorkflowMethod fun someMethod(test: String) {
                throw UnsupportedOperationException()
            }

            @WorkflowMethod fun someMethod2(int: Int) {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod() {
                throw UnsupportedOperationException()
            }
        }
        var errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.isEmpty())
        errors = workflowValidator.validateInvocation(
            workflow.javaClass,
            workflow.javaClass.declaredMethods.first { it.name == "someMethod" },
            "test"
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testWorkflowMethodMustReturnFutureWhenInvocationIsAsync() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("must return CompletableFuture when invocation is async")
        assertThat(errors[0]).contains("runAsync=true")
    }

    @Test
    fun testWorkflowMethodWithFutureReturnTypePassesAsyncValidation() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String): java.util.concurrent.CompletableFuture<String> {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testWorkflowMethodWithNonFutureReturnTypePassesSyncValidation() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = false)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testSignalMethodWithNonFutureReturnTypePassesAsyncValidation() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun workflowMethod(): java.util.concurrent.CompletableFuture<String> {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod(test: String) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "signalMethod" }
        // Signal methods should not be affected by the async validation rule
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testDetachedInvocationRejectsResultBearingWorkflowMethod() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String): String {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isDetached = true)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("cannot be invoked detached because it returns a result")
    }

    @Test
    fun testDetachedInvocationAllowsUnitReturningWorkflowMethod() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isDetached = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testDetachedInvocationAllowsCompletableFutureOfVoid() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String): java.util.concurrent.CompletableFuture<Void> {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isDetached = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testDetachedInvocationRejectsCompletableFutureCarryingAValue() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String): java.util.concurrent.CompletableFuture<String> {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isDetached = true)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("cannot be invoked detached because it returns a result")
    }

    @Test
    fun testDetachedInvocationExemptsAsyncFromTheFutureReturnTypeRule() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun someMethod(test: String) {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "someMethod" }
        // Without detached this combination is rejected — a sync method cannot deliver an async result.
        assertEquals(
            1,
            workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true).size
        )
        val errors =
            workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true, isDetached = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testDetachedInvocationIgnoresSignalMethods() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun workflowMethod() {
                throw UnsupportedOperationException()
            }

            @SignalMethod fun signalMethod(test: String): String {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "signalMethod" }
        // Signals always run synchronously, so a detached handle must not restrict their return type.
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isDetached = true)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testQueryMethodWithNonFutureReturnTypePassesAsyncValidation() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun workflowMethod(): java.util.concurrent.CompletableFuture<String> {
                throw UnsupportedOperationException()
            }

            @QueryMethod fun queryMethod(): String {
                throw UnsupportedOperationException()
            }
        }
        val method = workflow.javaClass.declaredMethods.first { it.name == "queryMethod" }
        // Query methods should not be affected by the async validation rule
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, null, isRunAsync = true)
        assertTrue(errors.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Suspend function (Kotlin coroutine) validation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testSuspendWorkflowMethodWithOneUserArgPassesParameterCountValidation() {
        // Kotlin compiles `suspend fun foo(arg: String)` to `foo(String, Continuation): Object`.
        // getUserParameterCount() must exclude the trailing Continuation so the method is
        // seen as having 1 user param — not 2 — and passes the at-most-one-argument rule.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod
            suspend fun run(input: String): String = input
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun testSuspendWorkflowMethodWithTwoUserArgsFailsParameterCountValidation() {
        // A suspend fun with 2 user params has 3 bytecode params (2 user + Continuation).
        // The validator must see 2 user params and reject it — not 3.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod
            suspend fun run(
                a: String,
                b: String
            ): String = "$a-$b"
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("must have at most one argument")
    }

    @Test
    fun testSuspendSignalMethodWithOneUserArgPassesParameterCountValidation() {
        // The workflowMethodsMustHaveAtMostOneArgument rule applies to @SignalMethod as well
        // as @WorkflowMethod. A suspend @SignalMethod with 1 user arg compiles to
        // (String, Continuation): Object — getUserParameterCount() must strip the Continuation
        // so the method is seen as having 1 user param and passes the rule.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod
            fun run(): String = throw UnsupportedOperationException()

            @SignalMethod
            suspend fun signal(input: String) = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun testSuspendSignalMethodWithTwoUserArgsFailsParameterCountValidation() {
        // A suspend @SignalMethod with 2 user params must be rejected by the validator.
        // The bytecode has 3 params (2 user + Continuation), but getUserParameterCount()
        // must report 2 user params and trigger the at-most-one-argument error.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod
            fun run(): String = throw UnsupportedOperationException()

            @SignalMethod
            suspend fun signal(
                a: String,
                b: String
            ) = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("must have at most one argument")
    }

    @Test
    fun testSuspendWorkflowMethodPassesAsyncInvocationValidation() {
        // Suspend functions have bytecode return type Object, not CompletableFuture.
        // The validator must NOT reject them when isRunAsync=true, because they are handled
        // via runBlocking in the engine — not via CompletableFuture unwrapping.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod
            suspend fun run(input: String): String = input
        }
        // Use getDeclaredMethods().first to locate the suspend method (its bytecode name is
        // "run" but has a Continuation as an extra parameter, so getDeclaredMethod("run") alone
        // would require knowing all parameter types — first{} is simpler).
        val method = workflow.javaClass.declaredMethods.first { it.name == "run" }
        val errors = workflowValidator.validateInvocation(workflow.javaClass, method, "test", isRunAsync = true)
        assertTrue(errors.isEmpty(), "Suspend @WorkflowMethod should pass async validation but got: $errors")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Persisted signal argument serializability (@SignalMethod(persist = true))
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testPersistableSignalWithNonSerializableArgFailsCreation() {
        data class NonSerializable<T>(val value: T? = null)
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun run() = Unit

            @SignalMethod(persist = true)
            fun signal(arg: NonSerializable<String>) = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertEquals(1, errors.size)
        assertThat(errors[0]).contains("Signal method ${workflow.javaClass.name}.signal opts into persistence")
        assertThat(errors[0]).contains("its argument type is not serializable")
    }

    @Test
    fun testPersistableSignalWithSerializableArgPassesCreation() {
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun run() = Unit

            @SignalMethod(persist = true)
            fun signal(arg: String) = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun testPersistableSignalWithNoArgPassesCreation() {
        // No-arg persisted signals are allowed: there is no input to serialize.
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun run() = Unit

            @SignalMethod(persist = true)
            fun signal() = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun testNonPersistableSignalWithNonSerializableArgPassesCreation() {
        // persist defaults to false, so the serializability rule must not apply: a non-persisted
        // signal with a non-serializable arg is still a valid workflow.
        data class NonSerializable<T>(val value: T? = null)
        val workflow: Workflow = object : Workflow() {
            @WorkflowMethod fun run() = Unit

            @SignalMethod fun signal(arg: NonSerializable<String>) = Unit
        }
        val errors = workflowValidator.validateCreation(workflow.javaClass)
        assertThat(errors).noneMatch { it.contains("opts into persistence") }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inherited annotation discovery
    // ──────────────────────────────────────────────────────────────────────────

    abstract class BaseStateMachineWorkflowForValidation : Workflow() {
        @StateField var baseState: String = "initial"

        @SignalMethod fun sendEvent(event: String) {
            baseState = event
        }
    }

    class ConcreteWorkflowForValidation : BaseStateMachineWorkflowForValidation() {
        @StateField var concreteField: String = "concrete"

        @WorkflowMethod fun execute() = Unit
    }

    class WorkflowWithOnlyInheritedSignal : BaseStateMachineWorkflowForValidation() {
        @WorkflowMethod fun run() = Unit
    }

    @Test
    fun testInheritedWorkflowMethodIsDiscovered() {
        val errors = workflowValidator.validateCreation(ConcreteWorkflowForValidation::class.java)
        // Should find @WorkflowMethod on concrete — no "must have at least one @WorkflowMethod" error
        assertThat(errors).noneMatch { it.contains("must have at least one method annotated with @WorkflowMethod") }
    }

    @Test
    fun testInheritedStateFieldIsValidated() {
        val errors = workflowValidator.validateCreation(ConcreteWorkflowForValidation::class.java)
        // Both baseState (inherited) and concreteField (declared) should be found and validated
        // String is serializable, so no errors
        assertThat(errors).noneMatch { it.contains("non-serializable fields") }
    }

    @Test
    fun testInheritedSignalMethodDoesNotCauseDuplicateError() {
        val errors = workflowValidator.validateCreation(ConcreteWorkflowForValidation::class.java)
        // sendEvent is inherited — should not cause "multiple methods with same name" error
        assertThat(errors).noneMatch { it.contains("multiple methods with the same name") }
    }

    @Test
    fun testWorkflowWithOnlyInheritedAnnotationsPassesValidation() {
        val errors = workflowValidator.validateCreation(WorkflowWithOnlyInheritedSignal::class.java)
        // @WorkflowMethod on concrete, @SignalMethod and @StateField on base — all should pass
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }
}
