package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import io.vavr.collection.HashSet as VavrHashSet
import io.vavr.collection.Set as VavrSet
import java.io.IOException
import java.util.Objects
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A simple serde class that can serialize and deserialize simple Java objects.
 *
 * This can be considered a best-effort serialization that uses simple rules to determine if the
 * object can be serialized and deserialized consistently (see [validateSerializableType]) for a
 * complete list of rules.
 */
class SimplePojoSerde(
    /**
     * The Jackson [ObjectMapper] used for POJO (de)serialization.
     *
     * Defaults to [buildDefaultObjectMapper], which is built against Skipper's own pinned dependency
     * versions. A host whose runtime classpath resolves an ABI-incompatible `jackson-module-kotlin`
     * (for example, pinned higher by another SDK on the same classpath) supplies a mapper built in
     * its OWN pinned versions via [buildObjectMapper], so the Kotlin module is constructed against the
     * Jackson the host actually runs. This is what lets a newer consumer coexist with Skipper's
     * default without a `NoSuchMethodError`. See `SkipperConfig.simplePojoSerde`.
     */
    val objectMapper: ObjectMapper = buildDefaultObjectMapper()
) : Serde {
    override fun serialize(obj: Any?): String {
        if (obj != null) {
            validateSerializableType(obj.javaClass)
        }
        validateSerializableObject(obj)
        return serializeInternal(obj)
    }

    fun serializeInternal(obj: Any?): String {
        if (obj == null) {
            return SERIALIZED_NULL
        }
        try {
            val serializedPayload = objectMapper.writeValueAsString(obj)
            val payload = ArrayList<String>()
            payload.add(obj.javaClass.name)
            payload.add(serializedPayload)
            return objectMapper.writeValueAsString(payload)
        } catch (e: IOException) {
            throw IllegalArgumentException(String.format("failed to serialize object: %s", obj), e)
        }
    }

    override fun deserialize(serialized: String): Any? {
        if (SERIALIZED_NULL == serialized) {
            return null
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val wrapper = objectMapper.readValue(serialized, List::class.java) as List<String>
            val clazz = ALIASING_CLASS_LOADER.loadClass(wrapper[0])
            val serializedPayload = wrapper[1]
            return objectMapper.readValue(serializedPayload, clazz)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException("unable to deserialize object: class not found", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("failed to deserialize object", e)
        }
    }

    /**
     * Validates that the given type is serializable.
     *
     * A type is considered serializable if:
     * - It is a primitive type
     * - It is not a generic type except for the types allows in [serializableGenericTypes]
     * - All its fields are serializable
     * - Its root type is not a generic of any type
     *
     * @param type The type to check
     * @throws IllegalArgumentException If the type is not serializable
     */
    override fun validateSerializableType(type: Class<*>) {
        // Top level rules here
        if (type.isEnum || Enum::class.java.isAssignableFrom(type)) {
            return // Enums are trivially serializable as strings
        }
        if (type.typeParameters.isNotEmpty()) {
            throw IllegalArgumentException("top-level generic types are not serializable")
        }
        validateSerializableTypeInternal(type, java.util.HashSet<Class<*>>())
    }

    private fun validateSerializableTypeInternal(
        type: Class<*>,
        visited: MutableSet<Class<*>>
    ) {
        if (visited.contains(type)) {
            return
        }
        visited.add(type)
        if (type.isPrimitive || Serde.PRIMITIVE_TYPES.contains(type)) {
            return
        }
        if (type.isEnum || Enum::class.java.isAssignableFrom(type)) {
            return // Enums are trivially serializable as strings
        }
        if (type.typeParameters.isNotEmpty()) {
            var isAllowedType = false
            for (serializableGenericType in serializableGenericTypes) {
                if (serializableGenericType.isAssignableFrom(type)) {
                    isAllowedType = true
                    break
                }
            }
            if (!isAllowedType) {
                throw IllegalArgumentException("generic type '" + type + "' is not serializable")
            }
        }
        // Only validate declared fields on the immediate type. Walking inherited fields from
        // library base classes (e.g., Jackson's own LinkedNode) would trigger false positives
        // for types that Jackson handles correctly via its own internal mechanisms.
        for (field in type.declaredFields) {
            validateSerializableTypeInternal(field.type, visited)
        }
    }

    override fun validateSerializableObject(obj: Any?) {
        if (obj == null || obj.javaClass.isAnnotationPresent(Serializable::class.java)) {
            return
        }
        // TODO: this method will work fine most of the time, but when dealing with objects that
        // contain date-times or floating point numbers, the precision might be lost during
        // serialization, therefore the equality check will fail even if the object is indeed
        // serializable. We should consider making this less strict.
        val areEqual: Boolean =
            try {
                Objects.equals(obj, deserialize(serializeInternal(obj)))
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    String.format(
                        "the object %s (%s) is not serializable: Jackson could not round-trip it. It needs a" +
                            " no-arg constructor and public fields or getters/setters, and no top-level generics",
                        obj,
                        obj.javaClass.name
                    ),
                    e
                )
            }
        if (!areEqual) {
            throw IllegalArgumentException(
                String.format(
                    "the object %s (%s) is not serializable: it does not compare equal to itself after a" +
                        " serialize/deserialize round trip. Implement equals() and hashCode() over the" +
                        " serialized fields (a Kotlin data class does this for you)",
                    obj,
                    obj.javaClass.name
                )
            )
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(SimplePojoSerde::class.java.name)
        private const val SERIALIZED_NULL = "null"
        private const val TEMPO_PACKAGE = "com.airbnb.tempo."
        private const val SKIPPER_PACKAGE = "com.airbnb.skipper."

        /**
         * Builds the fully-configured Skipper POJO/state [ObjectMapper], registering the
         * caller-supplied Kotlin module.
         *
         * The Kotlin module is taken as a plain [Module], so this builder carries **no** reference
         * to any `jackson-module-kotlin` constructor. That is the whole point of the seam: the
         * caller constructs the module in *their own* pinned versions (e.g.
         * `KotlinModule.Builder().build()` on a newer Jackson), and this method — compiled once in
         * Skipper's pinned versions — only ever touches the version-stable [Module] interface. A host
         * on an ABI-incompatible `jackson-module-kotlin` can therefore share Skipper's serde
         * without the compiled-vs-runtime clash that a hard-coded `KotlinModule()` call bakes in.
         *
         * Every module and mapper setting here is part of the persisted wire format — do not change
         * them without a backward-compatibility plan (see the golden-string serde tests).
         */
        @JvmStatic
        fun buildObjectMapper(kotlinModule: Module): ObjectMapper =
            ObjectMapper()
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
                .setFilterProvider(
                    SimpleFilterProvider().addFilter("thriftUnionFieldFilter", ThriftUnionFilter())
                )
                .registerModule(Jdk8Module())
                .registerModule(ParameterNamesModule())
                .registerModule(JavaTimeModule())
                .registerModule(kotlinModule)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addHandler(
                    object : DeserializationProblemHandler() {
                        @Throws(IOException::class)
                        override fun handleUnknownTypeId(
                            ctxt: DeserializationContext,
                            baseType: JavaType,
                            subTypeId: String,
                            idResolver: TypeIdResolver,
                            failureMsg: String,
                        ): JavaType? {
                            if (subTypeId.startsWith(TEMPO_PACKAGE)) {
                                // Class.forName is used here (not ALIASING_CLASS_LOADER) because
                                // the alias translation is already done — no further aliasing needed.
                                val aliased = SKIPPER_PACKAGE + subTypeId.substring(TEMPO_PACKAGE.length)
                                try {
                                    return ctxt.typeFactory.constructType(Class.forName(aliased))
                                } catch (e: ClassNotFoundException) {
                                    LOG.log(
                                        Level.WARNING,
                                        "Failed to alias type ''{0}'' to ''{1}'': class not found",
                                        arrayOf<Any>(subTypeId, aliased)
                                    )
                                }
                            }
                            return null
                        }
                    }
                )

        /**
         * The default mapper used when a host does not plug its own.
         *
         * This is the **only** site that names `jackson-module-kotlin`'s constructor, so it is
         * compiled against Skipper's own pinned versions. Hosts whose runtime resolves a newer,
         * ABI-incompatible `jackson-module-kotlin` must not rely on this default — they plug a
         * mapper built via [buildObjectMapper] through `SkipperConfig.simplePojoSerde`.
         */
        @JvmStatic
        fun buildDefaultObjectMapper(): ObjectMapper = buildObjectMapper(KotlinModule())

        // Falls back from com.airbnb.tempo.* to com.airbnb.skipper.* on ClassNotFoundException,
        // enabling deserialization of data serialized before the tempo→skipper rename.
        // Note: loadClass(name, false) does not run static initializers (unlike Class.forName);
        // Jackson will trigger initialization later when it instantiates the class.
        private val ALIASING_CLASS_LOADER: ClassLoader =
            object : ClassLoader(SimplePojoSerde::class.java.classLoader) {
                @Throws(ClassNotFoundException::class)
                public override fun loadClass(
                    name: String,
                    resolve: Boolean
                ): Class<*> {
                    return try {
                        super.loadClass(name, resolve)
                    } catch (e: ClassNotFoundException) {
                        if (name.startsWith(TEMPO_PACKAGE)) {
                            val aliased = SKIPPER_PACKAGE + name.substring(TEMPO_PACKAGE.length)
                            super.loadClass(aliased, resolve)
                        } else {
                            throw e
                        }
                    }
                }
            }

        /** These are the only generic types that are allowed to be serialized. */
        private val serializableGenericTypes: VavrSet<Class<*>> =
            VavrHashSet.of(
                java.util.List::class.java,
                java.util.Set::class.java,
                java.util.Map::class.java,
                java.util.Optional::class.java,
                Class::class.java,
            )
    }
}
