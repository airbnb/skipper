package com.airbnb.skipper.testutils

import com.airbnb.skipper.encryption.Encryptor

/**
 * A reversible [Encryptor] for tests. It is NOT secure: it only scrambles bytes so that a test can
 * tell plaintext from ciphertext without a real key.
 *
 * Register it in [WorkflowTest.configure]:
 * ```kotlin
 * override fun configure(config: SkipperConfig) {
 *     config.encryptors = mapOf(Encrypted.DEFAULT to TestEncryptor())
 * }
 * ```
 */
class TestEncryptor : Encryptor {
    override fun encrypt(plaintext: ByteArray): ByteArray = MARKER + scramble(plaintext)

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= MARKER.size && ciphertext.copyOfRange(0, MARKER.size).contentEquals(MARKER)) {
            "not produced by TestEncryptor"
        }
        return scramble(ciphertext.copyOfRange(MARKER.size, ciphertext.size))
    }

    // XOR is its own inverse, so the same function scrambles and unscrambles.
    private fun scramble(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor KEY).toByte() }

    private companion object {
        val MARKER = "test-encryptor:".toByteArray(Charsets.UTF_8)
        const val KEY = 0x5A
    }
}
