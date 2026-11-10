package com.airbnb.skipper.encryption

/**
 * Encrypts and decrypts the values Skipper persists for [Encrypted] actions and state fields.
 *
 * Skipper serializes the value with its configured serde, hands the bytes to [encrypt], and stores
 * the returned ciphertext. On the way back it hands the stored ciphertext to [decrypt] and
 * deserializes the result. Skipper never manages keys: the implementation owns key material,
 * algorithm and rotation.
 *
 * Implementations must follow three rules:
 * 1. `decrypt(encrypt(x))` returns `x` for every `x`, including after a key rotation. The usual way
 *    to do this is to embed the key version in the ciphertext, as AEAD envelope formats such as
 *    Tink's and most KMS clients' already do.
 * 2. Both methods are fast and local. Skipper decrypts every encrypted value of a workflow each time
 *    it resumes, so a remote call per value adds up quickly. Cache data keys rather than calling a
 *    key management service for every value.
 * 3. Both methods are thread-safe. Skipper calls them concurrently from its worker threads.
 *
 * A failure in either method should be thrown as an exception. Skipper treats it as a transient
 * failure of the current execution: the execution is retried later, the workflow is never failed
 * because of it, and nothing is persisted in plaintext.
 *
 * Register implementations by name in `SkipperConfig.encryptors`.
 */
interface Encryptor {
    /**
     * Encrypts the given plaintext.
     *
     * @param plaintext The serialized value to encrypt.
     * @return The ciphertext. It must contain everything [decrypt] needs, such as the key version
     *   and any nonce.
     */
    fun encrypt(plaintext: ByteArray): ByteArray

    /**
     * Decrypts ciphertext produced by [encrypt].
     *
     * @param ciphertext The bytes previously returned by [encrypt].
     * @return The original plaintext.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray
}
