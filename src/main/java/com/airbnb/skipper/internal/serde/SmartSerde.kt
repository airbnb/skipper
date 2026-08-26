package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.databind.ObjectMapper
import io.vavr.Tuple2
import io.vavr.collection.HashMap as VavrHashMap
import io.vavr.collection.Map as VavrMap

/**
 * A Serde implementation that can serialize and deserialize different types of objects.
 *
 * This Serde is a composition of other Serde implementations, and will attempt to find the correct
 * Serde for the given object.
 */
class SmartSerde(
    /**
     * The [SimplePojoSerde] used both for POJO payloads and for the outer envelope. Defaults to a
     * serde built against Skipper's own pinned Jackson version. Hosts on an ABI-incompatible
     * `jackson-module-kotlin` inject one built in their own pinned versions (wired through
     * `SkipperConfig.simplePojoSerde`) so this composite serde does not drag Skipper's compiled
     * Kotlin-module ABI onto their runtime classpath.
     */
    private val simpleSerde: SimplePojoSerde = SimplePojoSerde()
) : Serde {
    private val defaultSerde: ObjectMapper = simpleSerde.objectMapper
    private val serdes: VavrMap<String, Serde> =
        VavrHashMap.of(
            THRIFT_SERDE_KEY,
            ThriftSerde(),
            SIMPLE_SERDE_KEY,
            simpleSerde,
        )

    /**
     * Attempts to find a suitable serializer for the given object and serializes it.
     *
     * @param obj The object to serialize.
     * @return The resulting serialized string will include the key for the serialized used, such that
     *   it can be deserialized deterministically by the `deserialize` method.
     */
    override fun serialize(obj: Any?): String {
        val serde = findSerde(obj)
        val data = ArrayList<String>()
        data.add(serde._1)
        data.add(serde._2.serialize(obj))
        return defaultSerde.writeValueAsString(data)
    }

    private fun findSerde(obj: Any?): Tuple2<String, Serde> {
        return serdes
            .find { tuple ->
                try {
                    tuple._2.validateSerializableObject(obj)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            .getOrElseThrow {
                IllegalArgumentException(String.format("Unable to find a suitable serializer for %s", obj))
            }
    }

    /**
     * Deserializes the given serialized JSON string. The string must have been serialized by the
     * [serialize] method.
     *
     * @param serialized The serialized string to deserialize. Must be a string as returned by the
     *   [serialize] method.
     * @return The deserialized object.
     */
    override fun deserialize(serialized: String): Any? {
        @Suppress("UNCHECKED_CAST")
        val data = defaultSerde.readValue(serialized, List::class.java) as List<String>
        val serializer =
            serdes
                .get(data[0])
                .getOrElseThrow {
                    IllegalArgumentException(String.format("Unable to find serde for key '%s'", data[0]))
                }
        return serializer.deserialize(data[1])
    }

    override fun validateSerializableType(type: Class<*>) {
        serdes
            .find { tuple ->
                try {
                    tuple._2.validateSerializableType(type)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            .getOrElseThrow {
                IllegalArgumentException(String.format("Unable to find a suitable serializer for %s", type))
            }
    }

    override fun validateSerializableObject(obj: Any?) {
        findSerde(obj)
    }

    companion object {
        private const val THRIFT_SERDE_KEY = "thrift"
        private const val SIMPLE_SERDE_KEY = "simple"
    }
}
