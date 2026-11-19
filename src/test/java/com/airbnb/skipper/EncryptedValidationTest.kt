package com.airbnb.skipper

import com.airbnb.skipper.encryption.Encrypted
import com.airbnb.skipper.internal.ValueEncryption
import com.airbnb.skipper.internal.serde.SmartSerde
import com.airbnb.skipper.testutils.TestEncryptor
import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `@Encrypted` is checked when a workflow is created, so a misconfiguration fails before any value is stored. */
class EncryptedValidationTest {
    private val serde = SmartSerde()
    private val encryption = ValueEncryption(mapOf(Encrypted.DEFAULT to TestEncryptor(), "pii" to TestEncryptor()), serde)
    private val actionValidator = ActionValidator(serde, encryption)
    private val workflowValidator = WorkflowValidator(serde, encryption)

    open class ValidActions : Actions() {
        @Execute
        @Encrypted
        open fun open(customer: String): String = customer

        @Execute
        @Encrypted("pii")
        open fun lookUp(customer: String): String = customer
    }

    open class UnregisteredEncryptorActions : Actions() {
        @Execute
        @Encrypted("missing")
        open fun open(customer: String): String = customer
    }

    open class EncryptedCompensationActions : Actions() {
        @Execute
        open fun open(customer: String): String = customer

        @Compensate(forExecute = "open")
        @Encrypted
        open fun close(customer: String) {}
    }

    open class ValidWorkflow : Workflow() {
        @StateField
        @Encrypted("pii")
        var taxId: String? = null

        @WorkflowMethod
        open fun run(): String = "done"
    }

    open class UnregisteredEncryptorWorkflow : Workflow() {
        @StateField
        @Encrypted("missing")
        var taxId: String? = null

        @WorkflowMethod
        open fun run(): String = "done"
    }

    open class EncryptedPlainFieldWorkflow : Workflow() {
        @Encrypted
        var notState: String? = null

        @WorkflowMethod
        open fun run(): String = "done"
    }

    open class EncryptedWorkflowMethodWorkflow : Workflow() {
        @WorkflowMethod(returnType = String::class)
        @Encrypted
        open fun run(): CompletableFuture<String> = CompletableFuture.completedFuture("done")
    }

    @Test
    fun acceptsEncryptedActionsAndStateFieldsWithRegisteredEncryptors() {
        assertThat(actionValidator.validateCreation(ValidActions::class.java)).isEmpty()
        assertThat(workflowValidator.validateCreation(ValidWorkflow::class.java)).isEmpty()
    }

    @Test
    fun rejectsAnActionUsingAnUnregisteredEncryptor() {
        assertThat(actionValidator.validateCreation(UnregisteredEncryptorActions::class.java))
            .containsExactly(
                "Action method ${UnregisteredEncryptorActions::class.java.name}.open uses encryptor 'missing', which is not " +
                    "registered in SkipperConfig.encryptors",
            )
    }

    @Test
    fun rejectsEncryptedOnAMethodThatIsNotAnExecuteMethod() {
        assertThat(actionValidator.validateCreation(EncryptedCompensationActions::class.java))
            .containsExactly(
                "Action method ${EncryptedCompensationActions::class.java.name}.close is annotated with @Encrypted but not with " +
                    "@Execute",
            )
    }

    @Test
    fun rejectsAStateFieldUsingAnUnregisteredEncryptor() {
        assertThat(workflowValidator.validateCreation(UnregisteredEncryptorWorkflow::class.java))
            .containsExactly(
                "State field ${UnregisteredEncryptorWorkflow::class.java.name}.taxId uses encryptor 'missing', which is not " +
                    "registered in SkipperConfig.encryptors",
            )
    }

    @Test
    fun rejectsEncryptedOnAFieldThatIsNotState() {
        assertThat(workflowValidator.validateCreation(EncryptedPlainFieldWorkflow::class.java))
            .containsExactly(
                "Workflow field ${EncryptedPlainFieldWorkflow::class.java.name}.notState is annotated with @Encrypted but not " +
                    "with @StateField",
            )
    }

    @Test
    fun rejectsEncryptedOnAWorkflowMethod() {
        assertThat(workflowValidator.validateCreation(EncryptedWorkflowMethodWorkflow::class.java))
            .containsExactly(
                "Workflow method ${EncryptedWorkflowMethodWorkflow::class.java.name}.run is annotated with @Encrypted, which " +
                    "is only supported on @StateField fields of a workflow",
            )
    }

    @Test
    fun validatorsBuiltWithoutEncryptorsRejectEveryEncryptedUse() {
        assertThat(ActionValidator(serde).validateCreation(ValidActions::class.java)).hasSize(2)
        assertThat(WorkflowValidator(serde).validateCreation(ValidWorkflow::class.java)).hasSize(1)
    }

    @Test
    fun aRuntimeWithoutTheEncryptorRefusesToCreateTheWorkflow() {
        val runtime = com.airbnb.skipper.factory.SkipperRuntime(SkipperConfig.forService("encryption-validation"))

        val error =
            runCatching { runtime.workflowFactory.get().invoke(ValidWorkflow::class.java, "wf-1").run() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!.message).contains("Workflow creation validation failed", "encryptor 'pii'")
    }
}
