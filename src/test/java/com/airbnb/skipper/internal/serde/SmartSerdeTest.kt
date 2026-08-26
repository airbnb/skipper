package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.internal.testutils.TestTypes
import com.airbnb.skipper.test.fixtures.TestThriftPayload
import com.airbnb.skipper.test.fixtures.TestThriftStruct
import java.util.ArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SmartSerdeTest {
    @Test
    fun testSerdeSimpleType() {
        val serde = SmartSerde()
        var serialized = serde.serialize("hello world")
        var deserialized = serde.deserialize(serialized)
        assertEquals("hello world", deserialized)
        // Test integers
        serialized = serde.serialize(42)
        deserialized = serde.deserialize(serialized)
        assertEquals(42, deserialized)
        // Test booleans
        serialized = serde.serialize(true)
        deserialized = serde.deserialize(serialized)
        assertEquals(true, deserialized)
        // Test against breaking changes
        serialized = "[\"simple\",\"[\\\"java.lang.Integer\\\",\\\"42\\\"]\"]"
        assertEquals(42, serde.deserialize(serialized))
    }

    @Test
    fun testSerdeWhenThriftType() {
        val serde = SmartSerde()
        // Test a Thrift TBase type (nested structs)
        val header = TestThriftPayload()
        header.setHumanReadable("hello world")
        val request = TestThriftPayload()
        request.setHumanReadable("hello world2")
        val payload = TestThriftStruct()
        payload.setHeader(header)
        payload.setRequest(request)

        val serialized = serde.serialize(payload)
        val deserialized = serde.deserialize(serialized)
        assertEquals(payload, deserialized)
    }

    @Test
    fun testWhenNoSuitableSerializer() {
        val serde = SmartSerde()
        val data: List<String> = ArrayList()
        assertThrows(IllegalArgumentException::class.java) { serde.serialize(data) }
    }

    @Test
    fun testWhenUserType() {
        val serde = SmartSerde()
        val user = TestTypes.User()
        user.name = "John"
        user.id = "test123"

        val serialized = serde.serialize(user)
        val deserialized = serde.deserialize(serialized)
        assertEquals(user, deserialized)
    }
}
