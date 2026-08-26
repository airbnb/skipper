package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.charset.Charset
import java.util.Base64
import org.apache.thrift.TBase
import org.apache.thrift.TException
import org.apache.thrift.protocol.TCompactProtocol
import org.apache.thrift.protocol.TProtocol
import org.apache.thrift.transport.TIOStreamTransport

/** A Serde implementation that can serialize and deserialize Thrift types. */
class ThriftSerde : Serde {
    val objectMapper: ObjectMapper =
        ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
            .registerModule(ParameterNamesModule())
            .registerModule(JavaTimeModule())

    /**
     * Serialize the given Thrift type along with its type.
     *
     * The resulting type will be a JSON string that contains the class name of the object and the
     * serialized object base64 encoded.
     *
     * @param obj The object to serialize.
     * @return The serialized JSON string.
     */
    override fun serialize(obj: Any?): String {
        validateSerializableObject(obj)
        return serializeInternal(obj)
    }

    private fun serializeInternal(obj: Any?): String {
        return try {
            val clazz = obj!!.javaClass
            val serializedValue: String =
                if (clazz.isPrimitive || Serde.PRIMITIVE_TYPES.contains(clazz)) {
                    objectMapper.writeValueAsString(obj)
                } else {
                    String(Base64.getEncoder().encode(serializeThrift(obj)), Charset.defaultCharset())
                }
            val payload = ArrayList<String>()
            payload.add(clazz.name)
            payload.add(serializedValue)
            objectMapper.writeValueAsString(payload)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                String.format("failed to serialize ThriftSerializable object: %s", obj),
                e
            )
        }
    }

    /**
     * Deserialize the given serialized JSON string.
     *
     * @param serialized The serialized JSON string. It must be a string that was serialized by the
     *   [serialize] method.
     * @return
     */
    override fun deserialize(serialized: String): Any? {
        try {
            @Suppress("UNCHECKED_CAST")
            val serializedObject = objectMapper.readValue(serialized, List::class.java) as List<String>
            val clazz = Class.forName(serializedObject[0])
            return if (Serde.PRIMITIVE_TYPES.contains(clazz)) {
                objectMapper.readValue(serializedObject[1], clazz)
            } else {
                val decoded =
                    Base64.getDecoder().decode(serializedObject[1].toByteArray(Charset.defaultCharset()))
                deserializeThrift(decoded, clazz)
            }
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException(
                String.format(
                    "failed to deserialize ThriftSerializable object: %s. Invalid class name",
                    serialized
                ),
                e
            )
        } catch (e: TException) {
            throw IllegalArgumentException(
                String.format("failed to deserialize ThriftSerializable object: %s", serialized),
                e
            )
        } catch (e: IOException) {
            throw IllegalArgumentException(
                String.format("failed to deserialize ThriftSerializable object: %s", serialized),
                e
            )
        }
    }

    /**
     * The given type is considered serializable if it's either a primitive type, a TBase type, or a
     * type that exposes Thrift read/write methods.
     *
     * @param type The type to check
     */
    override fun validateSerializableType(type: Class<*>) {
        if (type.isPrimitive || Serde.PRIMITIVE_TYPES.contains(type)) {
            return
        }
        if (!TBase::class.java.isAssignableFrom(type) && !hasThriftReadWrite(type)) {
            throw IllegalArgumentException(
                String.format("type %s must be a Thrift serializable type or a primitive", type)
            )
        }
    }

    override fun validateSerializableObject(obj: Any?) {
        if (obj == null) {
            throw NullPointerException("object to serialize cannot be null")
        }
        validateSerializableType(obj.javaClass)
    }

    companion object {
        @Throws(TException::class)
        private fun serializeThrift(data: Any): ByteArray {
            val outputStream = ByteArrayOutputStream()
            val protocol: TProtocol = TCompactProtocol(TIOStreamTransport(outputStream))
            if (data is TBase<*, *>) {
                data.write(protocol)
            } else {
                invokeThriftWrite(data, protocol)
            }
            protocol.transport.flush()
            return outputStream.toByteArray()
        }

        @Suppress("UNCHECKED_CAST")
        @Throws(TException::class)
        private fun <T> deserializeThrift(
            data: ByteArray,
            type: Class<T>
        ): T {
            val protocol: TProtocol =
                TCompactProtocol(TIOStreamTransport(ByteArrayInputStream(data)))
            try {
                val instance: Any = type.getDeclaredConstructor().newInstance()!!
                if (instance is TBase<*, *>) {
                    instance.read(protocol)
                } else {
                    invokeThriftRead(instance, protocol)
                }
                return instance as T
            } catch (e: ReflectiveOperationException) {
                throw TException(e)
            }
        }

        @Throws(TException::class)
        private fun invokeThriftWrite(
            obj: Any,
            protocol: TProtocol
        ) {
            try {
                val writeMethod = obj.javaClass.getMethod("write", TProtocol::class.java)
                writeMethod.invoke(obj, protocol)
            } catch (e: InvocationTargetException) {
                val cause = e.cause
                if (cause is TException) {
                    throw cause
                }
                throw TException("Failed to invoke write(TProtocol) on " + obj.javaClass.name, e)
            } catch (e: Exception) {
                throw TException("Failed to invoke write(TProtocol) on " + obj.javaClass.name, e)
            }
        }

        @Throws(TException::class)
        private fun invokeThriftRead(
            obj: Any,
            protocol: TProtocol
        ) {
            try {
                val readMethod = obj.javaClass.getMethod("read", TProtocol::class.java)
                readMethod.invoke(obj, protocol)
            } catch (e: InvocationTargetException) {
                val cause = e.cause
                if (cause is TException) {
                    throw cause
                }
                throw TException("Failed to invoke read(TProtocol) on " + obj.javaClass.name, e)
            } catch (e: Exception) {
                throw TException("Failed to invoke read(TProtocol) on " + obj.javaClass.name, e)
            }
        }

        private fun hasThriftReadWrite(type: Class<*>): Boolean {
            return try {
                type.getMethod("read", TProtocol::class.java)
                type.getMethod("write", TProtocol::class.java)
                true
            } catch (e: NoSuchMethodException) {
                false
            }
        }
    }
}
