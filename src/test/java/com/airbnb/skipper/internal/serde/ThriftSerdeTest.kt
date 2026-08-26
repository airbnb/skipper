package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.test.fixtures.TestThriftPayload
import com.airbnb.skipper.test.fixtures.TestThriftStruct
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ThriftSerdeTest {
    @Test
    fun testSerdeSimpleType() {
        val serde = ThriftSerde()
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
    }

    @Test
    fun testSerdeThriftTBaseType() {
        val serde = ThriftSerde()
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
    fun testSerdeThriftTBaseTypeWithScalarFields() {
        val serde = ThriftSerde()
        val payload = TestThriftStruct()
        payload.setName("skipper")
        payload.setCount(7)

        val serialized = serde.serialize(payload)
        val deserialized = serde.deserialize(serialized)
        assertEquals(payload, deserialized)
    }

    @Test
    fun testSerdeNonThriftTypeShouldResultInError() {
        val serde = ThriftSerde()
        // Test non-ThriftSerializable type
        assertThrows(IllegalArgumentException::class.java) { serde.serialize(Duration.ofSeconds(10)) }
    }

    @Test
    fun testValidateSerializableTypeRejectsTypeWithoutThriftReadWrite() {
        val serde = ThriftSerde()
        // A plain type that is neither a TBase nor exposes Thrift read/write is not serializable.
        assertThrows(IllegalArgumentException::class.java) {
            serde.validateSerializableType(NotAThriftType::class.java)
        }
    }

    // A plain type that is neither a TBase nor exposes Thrift read(TProtocol)/write(TProtocol)
    // methods, so ThriftSerde must reject it.
    class NotAThriftType {
        @JvmField var value: String? = null
    }
}
