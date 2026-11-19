package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.encryption.Encrypted
import com.airbnb.skipper.encryption.EncryptedValue
import com.airbnb.skipper.encryption.Encryptor
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.serde.SmartSerde
import com.airbnb.skipper.testutils.TestEncryptor
import io.vavr.control.Either
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ValueEncryptionTest {
    private val encryption = ValueEncryption(mapOf(Encrypted.DEFAULT to TestEncryptor(), "other" to TestEncryptor()), SmartSerde())

    data class Card(
        val holder: String = "",
        val number: String = "",
    )

    @Test
    fun encryptsAValueIntoAnEnvelopeNamingItsEncryptor() {
        val encrypted = encryption.encrypt("other", Card("Ada", "4111"))

        assertThat(encrypted).isInstanceOf(EncryptedValue::class.java)
        encrypted as EncryptedValue
        assertThat(encrypted.encryptor).isEqualTo("other")
        assertThat(encrypted.ciphertext).doesNotContain("Ada", "4111")
        assertThat(encryption.decrypt(encrypted)).isEqualTo(Card("Ada", "4111"))
    }

    @Test
    fun nullAndAlreadyEncryptedValuesAreLeftAsTheyAre() {
        val envelope = EncryptedValue(Encrypted.DEFAULT, "abc")

        assertThat(encryption.encrypt(Encrypted.DEFAULT, null)).isNull()
        assertThat(encryption.encrypt(Encrypted.DEFAULT, envelope)).isSameAs(envelope)
    }

    @Test
    fun decryptingAPlainValueReturnsItUnchanged() {
        val plain = Card("Ada", "4111")

        assertThat(encryption.decrypt(plain)).isSameAs(plain)
        assertThat(encryption.decrypt(null)).isNull()
    }

    @Test
    fun anUnregisteredEncryptorFailsBothWays() {
        assertThatThrownBy { encryption.encrypt("missing", "secret") }
            .isInstanceOf(InternalError::class.java)
            .hasMessageContaining("'missing'")
            .hasMessageContaining("SkipperConfig.encryptors")
        assertThatThrownBy { encryption.decrypt(EncryptedValue("missing", "abc")) }
            .isInstanceOf(InternalError::class.java)
            .hasMessageContaining("'missing'")
    }

    @Test
    fun encryptorFailuresAreInternalErrorsThatDoNotLeakTheValue() {
        val failing =
            ValueEncryption(
                mapOf(
                    Encrypted.DEFAULT to
                        object : Encryptor {
                            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException("kms down")

                            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalStateException("kms down")
                        },
                ),
                SmartSerde(),
            )

        assertThatThrownBy { failing.encrypt(Encrypted.DEFAULT, "4111-secret") }
            .isInstanceOf(InternalError::class.java)
            .hasMessageNotContaining("4111-secret")
            .hasRootCauseMessage("kms down")
        assertThatThrownBy { failing.decrypt(EncryptedValue(Encrypted.DEFAULT, "AAAA")) }
            .isInstanceOf(InternalError::class.java)
            .hasRootCauseMessage("kms down")
    }

    @Test
    fun ciphertextThatDoesNotDecryptIsAnInternalError() {
        assertThatThrownBy { encryption.decrypt(EncryptedValue(Encrypted.DEFAULT, "bm90LWVuY3J5cHRlZA==")) }
            .isInstanceOf(InternalError::class.java)
            .hasMessageContaining("failed to decrypt")
    }

    @Test
    fun unconfiguredPassesPlainValuesButRefusesToEncrypt() {
        val unconfigured = ValueEncryption.UNCONFIGURED

        assertThat(unconfigured.isRegistered(Encrypted.DEFAULT)).isFalse()
        assertThat(unconfigured.decrypt("plain")).isEqualTo("plain")
        assertThat(unconfigured.encrypt(Encrypted.DEFAULT, null)).isNull()
        assertThatThrownBy { unconfigured.encrypt(Encrypted.DEFAULT, "secret") }.isInstanceOf(InternalError::class.java)
    }

    @Test
    fun aSerdeIsRequiredOnceEncryptorsAreRegistered() {
        assertThatThrownBy { ValueEncryption(mapOf(Encrypted.DEFAULT to TestEncryptor()), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun encryptsTheResultAndInputOfAnEncryptedActionsCheckpoint() {
        val original = checkpoint(Either.right("ACCOUNT-1"), input = "CUSTOMER-1")

        val stored = encryption.encrypt(original, method("encrypted"))

        assertThat(stored.result.get()).isInstanceOf(EncryptedValue::class.java)
        assertThat(stored.input).isInstanceOf(EncryptedValue::class.java)
        assertThat(stored.checkpointTag).isEqualTo(original.checkpointTag)
        assertThat(stored.executionStartTime).isEqualTo(original.executionStartTime)
        assertThat(stored.executionEndTime).isEqualTo(original.executionEndTime)
        assertThat(stored.isTransient).isEqualTo(original.isTransient)
        assertThat(stored.isResultIsAsync).isEqualTo(original.isResultIsAsync)
        // Replay decrypts the result only; compensation decrypts the input when it needs it.
        val replayed = encryption.decryptResult(stored)
        assertThat(replayed.result).isEqualTo(original.result)
        assertThat(replayed.input).isSameAs(stored.input)
        assertThat(encryption.decrypt(stored.input)).isEqualTo("CUSTOMER-1")
    }

    @Test
    fun encryptsAValueOnlyForAnAnnotatedMethod() {
        assertThat(encryption.encrypt(method("encrypted"), "CUSTOMER-1")).isInstanceOf(EncryptedValue::class.java)
        assertThat(encryption.encrypt(method("plain"), "CUSTOMER-1")).isEqualTo("CUSTOMER-1")
    }

    @Test
    fun keepsAnInputThatIsAlreadyEncrypted() {
        val input = encryption.encrypt(Encrypted.DEFAULT, "CUSTOMER-1")

        val stored = encryption.encrypt(checkpoint(Either.right("ACCOUNT-1"), input), method("encrypted"))

        assertThat(stored.input).isSameAs(input)
    }

    @Test
    fun leavesTheCheckpointOfAnUnannotatedActionAlone() {
        val original = checkpoint(Either.right("ACCOUNT-1"), input = "CUSTOMER-1")

        assertThat(encryption.encrypt(original, method("plain"))).isSameAs(original)
        assertThat(encryption.decryptResult(original)).isSameAs(original)
    }

    @Test
    fun neverEncryptsAnError() {
        val error = IllegalStateException("boom")
        val original = checkpoint(Either.left(error), input = "CUSTOMER-1")

        val stored = encryption.encrypt(original, method("encrypted"))

        assertThat(stored.result.left).isSameAs(error)
        assertThat(stored.input).isInstanceOf(EncryptedValue::class.java)
        assertThat(encryption.decryptResult(stored)).isSameAs(stored)
        assertThat(encryption.decrypt(stored.input)).isEqualTo("CUSTOMER-1")
    }

    @Test
    fun decryptsACheckpointWhoseOnlyEncryptedValueIsTheResult() {
        val original = checkpoint(Either.right("ACCOUNT-1"), input = null)

        val stored = encryption.encrypt(original, method("encrypted"))

        assertThat(stored.input).isNull()
        assertThat(encryption.decryptResult(stored)).isEqualTo(original)
    }

    @Test
    fun theEnvelopeKeepsCiphertextOutOfItsStringAndPlaceholder() {
        assertThat(EncryptedValue("pii", "c2VjcmV0").toString()).isEqualTo("EncryptedValue(encryptor=pii)")
        assertThat(EncryptedValue("pii", "c2VjcmV0").placeholder()).isEqualTo("[encrypted: pii]")
    }

    private fun method(name: String) = SampleActions::class.java.getDeclaredMethod(name, String::class.java)

    private fun checkpoint(
        result: Either<Throwable, Any?>,
        input: Any?,
    ): ActionCheckpoint =
        ActionCheckpoint.builder()
            .checkpointTag(CheckpointTag("wf-1", SampleActions::class.java, "encrypted", 0, null))
            .executionStartTime(Instant.ofEpochSecond(1))
            .executionEndTime(Instant.ofEpochSecond(2))
            .result(result)
            .input(input)
            .isTransient(false)
            .resultIsAsync(true)
            .build()

    open class SampleActions : Actions() {
        @Execute
        @Encrypted
        open fun encrypted(customer: String): String = customer

        @Execute
        open fun plain(customer: String): String = customer
    }
}
