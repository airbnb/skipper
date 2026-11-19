package com.airbnb.skipper.testutils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TestEncryptorTest {
    private val encryptor = TestEncryptor()

    @Test
    fun roundTripsAndHidesThePlaintext() {
        val plaintext = "TAXID-123".toByteArray()

        val ciphertext = encryptor.encrypt(plaintext)

        assertFalse(String(ciphertext).contains("TAXID-123"))
        assertArrayEquals(plaintext, encryptor.decrypt(ciphertext))
    }

    @Test
    fun rejectsBytesItDidNotProduce() {
        assertThrows(IllegalArgumentException::class.java) { encryptor.decrypt("TAXID-123".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { encryptor.decrypt(ByteArray(0)) }
    }
}
