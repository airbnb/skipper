package com.airbnb.skipper.encryption

/**
 * What Skipper persists in place of a value that was encrypted because of [Encrypted].
 *
 * This class is part of the persisted format: its name and fields are written to the store with
 * every encrypted value, so they must not change. It shows up wherever Skipper exposes stored data
 * without decrypting it, such as the admin UI and [com.airbnb.skipper.WorkflowInstance] state.
 */
data class EncryptedValue(
    /** The name of the [Encryptor] that produced [ciphertext]. */
    val encryptor: String,
    /** The base64-encoded ciphertext of the serialized value. */
    val ciphertext: String,
) {
    // Keeps ciphertext out of logs and debug output.
    override fun toString(): String = "EncryptedValue(encryptor=$encryptor)"
}
