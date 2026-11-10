package com.airbnb.skipper.encryption

/**
 * What Skipper persists in place of a value that was encrypted because of [Encrypted].
 *
 * This class is part of the persisted format: its name and fields are written to the store with
 * every encrypted value, so they must not change. The configured serde must be able to serialize
 * it, and a Skipper version without it cannot load a workflow that holds one, so upgrade every host
 * before adding [Encrypted]. It shows up wherever Skipper exposes stored data
 * without decrypting it, such as the admin UI and [com.airbnb.skipper.WorkflowInstance] state.
 */
data class EncryptedValue(
    /** The name of the [Encryptor] that produced [ciphertext]. */
    val encryptor: String,
    /** The base64-encoded ciphertext of the serialized value. */
    val ciphertext: String,
) {
    /**
     * What views of stored data, such as the admin UIs, show in place of the value: the encryptor's
     * name and no ciphertext.
     */
    // A plain function rather than a property, so the persistence serde does not store it.
    fun placeholder(): String = "[encrypted: $encryptor]"

    // Keeps ciphertext out of logs and debug output.
    override fun toString(): String = "EncryptedValue(encryptor=$encryptor)"
}
