package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.PersistentRetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.internal.PersistentRetryableError
import com.airbnb.skipper.internal.api.WaitSignal
import java.time.Duration
import java.time.Instant
import java.util.ArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SimplePojoSerdeTest {
    private lateinit var serdeUtils: SimplePojoSerde

    @BeforeEach
    fun setUp() {
        serdeUtils = SimplePojoSerde()
    }

    @Test
    fun testSerdeSimpleType() {
        val serialized = serdeUtils.serialize("hello world")
        val deserialized = serdeUtils.deserialize(serialized)
        assertEquals("hello world", deserialized)
        assertTrue(deserialized is String)
    }

    @Test
    fun testSerdePrimitiveType() {
        val serialized = serdeUtils.serialize(42)
        val deserialized = serdeUtils.deserialize(serialized)
        assertEquals(42, deserialized)
        assertTrue(deserialized is Integer)
    }

    @Test
    fun testSerdeNull() {
        assertNull(serdeUtils.deserialize(serdeUtils.serialize(null)))
    }

    @Test
    fun testCustomType() {
        val user = User2()
        user.name = "John"
        user.createdAt = Instant.now()
        val address = Address()
        address.street = "123 Main St"
        address.country = Country.USA
        user.address = ArrayList()
        user.address!!.add(address)
        user.lastName = "Doe"

        val serialized = serdeUtils.serialize(user)
        val deserialized = serdeUtils.deserialize(serialized)
        assertEquals(user, deserialized)
        // Guard against breaking changes
        val serializedPayload =
            "[\"com.airbnb.skipper.internal.serde.SimplePojoSerdeTest\$User2\"," +
                "\"{\\\"name\\\":\\\"John\\\",\\\"address\\\":[{\\\"street\\\":\\\"123" +
                " Main" +
                " St\\\",\\\"country\\\":\\\"USA\\\"}],\\\"createdAt\\\":1723477706.624733000,\\\"lastName\\\":\\\"Doe\\\"}\"]"
        val deserializedPayload = serdeUtils.deserialize(serializedPayload)
        assertEquals(user, deserializedPayload)
    }

    @Test
    fun testIsSerializable() {
        serdeUtils.validateSerializableType(String::class.java)
        serdeUtils.validateSerializableType(Integer::class.java)
        // Top-level generics now allowed
        assertThrows(IllegalArgumentException::class.java) {
            serdeUtils.validateSerializableType(java.util.List::class.java)
        }
        serdeUtils.validateSerializableType(User::class.java)
        serdeUtils.validateSerializableType(Address::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            serdeUtils.validateSerializableType(GenericUser::class.java)
        }
        serdeUtils.validateSerializableType(User2::class.java)
    }

    @Test
    fun serializeSkipperErrors() {
        try {
            throw NonRetryableError("message", RuntimeException("cause"))
        } catch (error: NonRetryableError) {
            val serialized = serdeUtils.serialize(error)
            val deserialized = serdeUtils.deserialize(serialized)
            assertEquals(error, deserialized)
        } catch (e: Exception) {
            throw RuntimeException("expected NonRetryableError to be thrown", e)
        }

        // Wait signal
        val signal = WaitSignal(Duration.ofHours(1))
        val serialized = serdeUtils.serialize(signal)
        val deserialized = serdeUtils.deserialize(serialized)
        assertEquals(signal, deserialized)

        // RetryableError
        try {
            throw RetryableError(
                "this is a retryable error",
                ApplicationError.fromException(
                    RuntimeException(
                        "cause!",
                        IllegalArgumentException("illegal argument exception")
                    )
                ),
                FixedRetryStrategy(Duration.ofSeconds(1), 1),
                1
            )
        } catch (error: RetryableError) {
            val serializedRetryable = serdeUtils.serialize(error)
            val deserializedRetryable = serdeUtils.deserialize(serializedRetryable)
            assertEquals(error, deserializedRetryable)
        } catch (e: Exception) {
            throw RuntimeException("expected RetryableError to be thrown", e)
        }

        try {
            throw RetryableError(
                "this is a retryable error",
                RuntimeException(
                    "cause!",
                    IllegalArgumentException("illegal argument exception")
                )
            )
        } catch (error: RetryableError) {
            val serializedRetryable = serdeUtils.serialize(error)
            val deserializedRetryable = serdeUtils.deserialize(serializedRetryable)
            assertEquals(error, deserializedRetryable)
        } catch (e: Exception) {
            throw RuntimeException("expected RetryableError to be thrown", e)
        }

        // Persistent retryable error
        val persistentRetryStrategy =
            PersistentRetryStrategy.of(FixedRetryStrategy(Duration.ofSeconds(1), 1))
        val serializedPersistentRetryStrategy =
            serdeUtils.serializeInternal(persistentRetryStrategy)
        val deserializedPersistentRetryStrategy =
            serdeUtils.deserialize(serializedPersistentRetryStrategy)
        assertEquals(persistentRetryStrategy, deserializedPersistentRetryStrategy)

        val pError =
            PersistentRetryableError(
                RetryableError(
                    "this is a retryable error",
                    ApplicationError.fromException(
                        RuntimeException(
                            "cause!",
                            IllegalArgumentException("illegal argument exception")
                        )
                    ),
                    persistentRetryStrategy,
                    1
                )
            )
        try {
            throw pError
        } catch (error: PersistentRetryableError) {
            val serializedPersistentError = serdeUtils.serialize(pError)
            val deserializedPersistentError = serdeUtils.deserialize(serializedPersistentError)
            assertEquals(pError, deserializedPersistentError)
        } catch (e: Exception) {
            throw RuntimeException("expected PersistentRetryableError to be thrown", e)
        }
    }

    // ── Cross-package deserialization (tempo → skipper) ──

    @Test
    fun deserializeTempoRetryableError() {
        val original = RetryableError("retry me")
        val serialized = serdeUtils.serializeInternal(original)
        val tempoSerialized = serialized.replace("com.airbnb.skipper", "com.airbnb.tempo")
        val deserialized = serdeUtils.deserialize(tempoSerialized)
        assertTrue(deserialized is com.airbnb.skipper.RetryableError)
    }

    @Test
    fun deserializeTempoNonRetryableError() {
        val original = NonRetryableError("fatal")
        val serialized = serdeUtils.serializeInternal(original)
        val tempoSerialized = serialized.replace("com.airbnb.skipper", "com.airbnb.tempo")
        val deserialized = serdeUtils.deserialize(tempoSerialized)
        assertTrue(deserialized is com.airbnb.skipper.NonRetryableError)
    }

    @Test
    fun deserializeTempoApplicationError() {
        val original = ApplicationError.fromException(RuntimeException("oops"))
        val serialized = serdeUtils.serializeInternal(original)
        val tempoSerialized = serialized.replace("com.airbnb.skipper", "com.airbnb.tempo")
        val deserialized = serdeUtils.deserialize(tempoSerialized)
        assertTrue(deserialized is com.airbnb.skipper.ApplicationError)
    }

    @Test
    fun deserializeTempoExtraRequestData() {
        // Simulate data that was serialized with the old tempo class name
        val tempoSerialized =
            "[\"com.airbnb.tempo.util.ExtraRequestData\"," +
                "\"{\\\"dataBag\\\":{\\\"key1\\\":\\\"value1\\\",\\\"key2\\\":\\\"value2\\\"}}\"]"

        val deserialized = serdeUtils.deserialize(tempoSerialized)
        assertTrue(deserialized is com.airbnb.skipper.util.ExtraRequestData)
        val result = deserialized as com.airbnb.skipper.util.ExtraRequestData
        assertEquals("value1", result.dataBag.get("key1"))
        assertEquals("value2", result.dataBag.get("key2"))
    }

    // ── Inherited field serialization validation ──

    @Test
    fun testValidateSerializableTypeForEnumType() {
        // Enum types should be considered serializable (Jackson serializes them as strings)
        serdeUtils.validateSerializableType(Country::class.java)
    }

    @Test
    fun testValidateSerializableTypeForClassWithInheritedFields() {
        // User2 extends User and adds a field. All fields (including inherited ones from User)
        // should be validated. Since both User and User2 have serializable fields, this passes.
        serdeUtils.validateSerializableType(User2::class.java)
    }

    private class GenericUser<T> {
        @JvmField var name: String? = null
        @JvmField var lastName: String? = null
        @JvmField var data: T? = null
    }

    private open class User {
        @JvmField var name: String? = null
        @JvmField var address: MutableList<Address>? = null
        @JvmField var createdAt: Instant? = null
    }

    private class Address {
        @JvmField var street: String? = null
        @JvmField var country: Country? = null
    }

    private enum class Country {
        USA,
        CANADA
    }

    private class User2 : User() {
        @JvmField var lastName: String? = null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is User2) return false
            return lastName == other.lastName
        }

        override fun hashCode(): Int = lastName?.hashCode() ?: 0
    }
}
