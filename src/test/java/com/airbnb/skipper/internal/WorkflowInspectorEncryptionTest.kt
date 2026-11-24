package com.airbnb.skipper.internal

import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.encryption.Encrypted
import com.airbnb.skipper.encryption.EncryptedValue
import com.airbnb.skipper.internal.serde.SmartSerde
import com.airbnb.skipper.testutils.TestEncryptor
import io.vavr.collection.HashMap
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkflowInspectorEncryptionTest {
    private val encryption = ValueEncryption(mapOf(Encrypted.DEFAULT to TestEncryptor()), SmartSerde())

    open class AccountWorkflow : Workflow() {
        @StateField
        @Encrypted
        var taxId: String? = null

        @StateField
        var label: String? = null

        @WorkflowMethod
        open fun run(): String = "done"
    }

    /** The same state, before `@Encrypted` was added (or after it was removed). */
    open class PlainAccountWorkflow : Workflow() {
        @StateField
        var taxId: String? = null

        @WorkflowMethod
        open fun run(): String = "done"
    }

    @Test
    fun getStateEncryptsOnlyTheAnnotatedFieldsAndSetStateDecryptsThem() {
        val source = AccountWorkflow().apply {
            taxId = "TAXID-1"
            label = "LABEL-1"
        }

        val state = WorkflowInspector(source, encryption).getState()

        assertThat(state.get("taxId").get()).isInstanceOf(EncryptedValue::class.java)
        assertThat(state.get("label").get()).isEqualTo("LABEL-1")

        val target = AccountWorkflow()
        WorkflowInspector(target, encryption).setState(state)
        assertThat(target.taxId).isEqualTo("TAXID-1")
        assertThat(target.label).isEqualTo("LABEL-1")
    }

    @Test
    fun aNullEncryptedFieldIsStoredAsNull() {
        val state = WorkflowInspector(AccountWorkflow(), encryption).getState()

        assertThat(state.get("taxId").get()).isNull()
    }

    @Test
    fun plaintextStoredBeforeTheAnnotationWasAddedStillLoads() {
        val target = AccountWorkflow()

        WorkflowInspector(target, encryption).setState(HashMap.of("taxId", "TAXID-OLD"))

        assertThat(target.taxId).isEqualTo("TAXID-OLD")
    }

    @Test
    fun ciphertextStoredBeforeTheAnnotationWasRemovedStillDecrypts() {
        val state = WorkflowInspector(AccountWorkflow().apply { taxId = "TAXID-1" }, encryption).getState()

        val target = PlainAccountWorkflow()
        WorkflowInspector(target, encryption).setState(state)

        assertThat(target.taxId).isEqualTo("TAXID-1")
    }

    @Test
    fun withoutEncryptionAnAnnotatedFieldIsNeverReturnedInPlaintext() {
        val inspector = WorkflowInspector(AccountWorkflow().apply { taxId = "TAXID-1" })

        assertThatThrownBy { inspector.getState() }.isInstanceOf(InternalError::class.java)
    }
}
