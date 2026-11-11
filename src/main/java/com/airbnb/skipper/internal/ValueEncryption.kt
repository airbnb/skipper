package com.airbnb.skipper.internal

import com.airbnb.skipper.encryption.Encrypted
import com.airbnb.skipper.encryption.EncryptedValue
import com.airbnb.skipper.encryption.Encryptor
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.serde.Serde
import io.vavr.control.Either
import java.lang.reflect.AnnotatedElement
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Turns values into [EncryptedValue]s before Skipper hands them to the store, and back after it
 * reads them.
 *
 * Encrypting is driven by the [Encrypted] annotation; decrypting is driven by the stored value
 * alone: anything that is not an [EncryptedValue] is returned unchanged. That is what makes adding
 * or removing the annotation safe for workflows that are already running.
 *
 * Every failure is thrown as an [InternalError], which Skipper retries at the task level without
 * failing the workflow or spending its retry budget. Error messages never include the value.
 */
class ValueEncryption(
    encryptors: Map<String, Encryptor>,
    /** Serializes values before encryption. Only used when an encryptor is registered. */
    private val serde: Serde?,
) {
    private val encryptors: Map<String, Encryptor> = HashMap(encryptors)

    init {
        require(this.encryptors.isEmpty() || serde != null) { "a serde is required when encryptors are registered" }
    }

    /** Whether an encryptor is registered under [name]. */
    fun isRegistered(name: String): Boolean = encryptors.containsKey(name)

    /**
     * Encrypts [value] with the encryptor registered under [encryptorName]. Null stays null.
     *
     * @throws InternalError If no encryptor is registered under the name, or if serializing or
     *   encrypting fails.
     */
    fun encrypt(
        encryptorName: String,
        value: Any?,
    ): Any? {
        if (value == null || value is EncryptedValue) {
            return value
        }
        val encryptor = encryptorFor(encryptorName)
        val ciphertext =
            try {
                encryptor.encrypt(serde!!.serialize(value).toByteArray(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                throw InternalError("failed to encrypt a value with encryptor '$encryptorName'", e)
            }
        return EncryptedValue(encryptorName, Base64.getEncoder().encodeToString(ciphertext))
    }

    /**
     * Decrypts [value] if it is an [EncryptedValue], and returns it unchanged otherwise.
     *
     * @throws InternalError If the value's encryptor is not registered, or if decrypting or
     *   deserializing fails.
     */
    fun decrypt(value: Any?): Any? {
        if (value !is EncryptedValue) {
            return value
        }
        val encryptor = encryptorFor(value.encryptor)
        try {
            val plaintext = encryptor.decrypt(Base64.getDecoder().decode(value.ciphertext))
            return serde!!.deserialize(String(plaintext, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw InternalError("failed to decrypt a value with encryptor '${value.encryptor}'", e)
        }
    }

    /**
     * Encrypts [value] if [method] is annotated with [Encrypted], and returns it unchanged
     * otherwise.
     */
    fun encrypt(
        method: AnnotatedElement,
        value: Any?,
    ): Any? {
        val annotation = method.getAnnotation(Encrypted::class.java) ?: return value
        return encrypt(annotation.encryptor, value)
    }

    /**
     * Returns [checkpoint] with its successful result and its input encrypted, if [method] is
     * annotated with [Encrypted]. Errors are never encrypted. An input that is already encrypted is
     * kept as it is.
     */
    fun encrypt(
        checkpoint: ActionCheckpoint,
        method: AnnotatedElement,
    ): ActionCheckpoint {
        val annotation = method.getAnnotation(Encrypted::class.java) ?: return checkpoint
        return checkpoint.withValues(
            checkpoint.result.map { encrypt(annotation.encryptor, it) },
            encrypt(annotation.encryptor, checkpoint.input),
        )
    }

    /**
     * Returns [checkpoint] with its result decrypted, for replay. The input is left encrypted: only
     * compensation reads it, and it decrypts the input itself, so replay pays for one decryption
     * per checkpoint rather than two.
     */
    fun decryptResult(checkpoint: ActionCheckpoint): ActionCheckpoint {
        val result = checkpoint.result
        if (!(result.isRight && result.get() is EncryptedValue)) {
            return checkpoint
        }
        return checkpoint.withValues(result.map { decrypt(it) }, checkpoint.input)
    }

    private fun encryptorFor(name: String): Encryptor =
        encryptors[name]
            ?: throw InternalError("no encryptor is registered under '$name'; register one in SkipperConfig.encryptors")

    private fun ActionCheckpoint.withValues(
        result: Either<Throwable, Any?>,
        input: Any?,
    ): ActionCheckpoint =
        ActionCheckpoint(
            checkpointTag,
            executionStartTime,
            executionEndTime,
            result,
            input,
            isTransient,
            isResultIsAsync,
        )

    companion object {
        /**
         * Used when nothing was configured. Plain values pass through; anything that needs an
         * encryptor fails, so an [Encrypted] value is never stored in plaintext.
         */
        @JvmField
        val UNCONFIGURED = ValueEncryption(emptyMap(), null)
    }
}
