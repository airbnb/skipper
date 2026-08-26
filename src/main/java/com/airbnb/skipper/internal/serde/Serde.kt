package com.airbnb.skipper.internal.serde

import io.vavr.collection.HashSet as VavrHashSet
import io.vavr.collection.Set as VavrSet

/**
 * A serialization and deserialization abstraction.
 *
 * This interface is used to abstract the serialization and deserialization of objects that will be
 * used in Skipper internals for persistence and other purposes.
 *
 * Skipper provides a set of default serde implementations that can be used, but the client can
 * implement their own in case they have specific requirements or they already use a serialization
 * mechanism.
 */
interface Serde {
    /**
     * Serialize an object to a string.
     *
     * This will use Gson to serialize the object, therefore, for custom types, you will need to make
     * sure to provide a custom gson builder at construction time that will have the necessary adapters
     * registered, otherwise this will fail.
     *
     * @param obj The object to serialize.
     * @return The serialized string.
     */
    fun serialize(obj: Any?): String

    /**
     * Deserialize a string to an object.
     *
     * @param serialized The serialized JSON string. It must be a string that was serialized by the
     *   [serialize] method.
     * @return The deserialized object. The caller is responsible for down casting the object to the
     *   appropriate type if needed, but it is guaranteed that the object returned equal to the object
     *   that was initially serialized.
     */
    fun deserialize(serialized: String): Any?

    /** Validates that the given type is serializable. */
    fun validateSerializableType(type: Class<*>)

    /**
     * Validates the given object is serializable.
     *
     * @param obj The object to validate.
     */
    fun validateSerializableObject(obj: Any?)

    companion object {
        val PRIMITIVE_TYPES: VavrSet<Class<*>> =
            VavrHashSet.of(
                String::class.java,
                java.lang.Integer::class.java,
                java.lang.Long::class.java,
                java.lang.Double::class.java,
                java.lang.Float::class.java,
                java.lang.Boolean::class.java,
                java.lang.Short::class.java,
                java.lang.Byte::class.java,
            )
    }
}
