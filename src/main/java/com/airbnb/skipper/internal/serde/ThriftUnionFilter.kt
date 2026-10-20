package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.PropertyWriter
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import java.io.IOException
import java.io.Serializable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Jackson filter that serializes only the active field of a Thrift union. Thrift unions expose a
 * `getJsonName()` method that returns the name of the currently set field; this filter skips all
 * other fields.
 */
internal class ThriftUnionFilter : SimpleBeanPropertyFilter(), Serializable {
    @Throws(Exception::class)
    override fun serializeAsField(
        pojo: Any,
        jgen: JsonGenerator,
        provider: SerializerProvider,
        writer: PropertyWriter,
    ) {
        if (include(writer)) {
            try {
                val c: Class<*> = pojo.javaClass
                val fieldNameMethod =
                    GET_JSON_NAME_CACHE.computeIfAbsent(c) { cls ->
                        try {
                            val m = cls.getMethod("getJsonName")
                            m.isAccessible = true
                            m
                        } catch (e: NoSuchMethodException) {
                            throw RuntimeException("No getJsonName method found on class: " + cls.name, e)
                        }
                    }
                val fieldName = fieldNameMethod.invoke(pojo) as String
                if (writer.name == fieldName) {
                    writer.serializeAsField(pojo, jgen, provider)
                }
            } catch (e: Exception) {
                throw IOException(
                    "Error serializing Thrift union property: " +
                        writer.name +
                        " (caused by: " +
                        e.toString() +
                        ")",
                    e,
                )
            }
        } else if (!jgen.canOmitFields()) {
            writer.serializeAsOmittedField(pojo, jgen, provider)
        }
    }

    override fun include(writer: BeanPropertyWriter): Boolean = true

    override fun include(writer: PropertyWriter): Boolean = true

    companion object {
        private val GET_JSON_NAME_CACHE = ConcurrentHashMap<Class<*>, Method>()
    }
}
