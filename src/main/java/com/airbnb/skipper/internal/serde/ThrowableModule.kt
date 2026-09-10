package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.introspect.AnnotatedField
import com.fasterxml.jackson.databind.introspect.AnnotatedMember
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.std.StdSerializer

/**
 * Jackson module that lets Skipper persist errors without reflecting into `java.base`.
 *
 * Skipper persists every `SkipperError` (and its `StackTraceElement`s) as JSON. Skipper's mapper
 * auto-detects fields with visibility `ANY`, so for JDK types Jackson's bean (de)serializers reach
 * into private fields of `java.lang.Throwable` and `java.lang.StackTraceElement`. The JDK 16+ module
 * system denies that unless the JVM runs with `--add-opens java.base/java.lang=ALL-UNNAMED`; without
 * it, the first persisted action failure aborts with `InaccessibleObjectException`, retries never
 * follow the configured strategy and compensation never runs. This module removes every such access:
 *
 * 1. [StackTraceElement] gets an explicit serializer and deserializer built on its public API.
 * 2. For any `Throwable` bean, properties whose only accessor is a **field declared by a JDK class**
 *    (`Throwable.detailMessage`, `cause`, `stackTrace`, `suppressedExceptions`, but also
 *    `SQLException.SQLState`, `URISyntaxException.index`, ...) are
 *    dropped from bean introspection. Everything that has a public getter or setter (`message`,
 *    `cause`, `stackTrace`, `suppressed`, `localizedMessage`) is unaffected, as are Skipper's own
 *    explicitly annotated error fields and any field an application declares on its own exceptions.
 *
 * **Persisted wire format is unchanged.** Skipper's error types use `@JsonAutoDetect(NONE)` with
 * explicit `@JsonProperty` fields, so rule 2 never touches what they write; rule 2 only matters for
 * Jackson's eager construction of a deserializer for raw `Throwable` (the parameter type of
 * `initCause`, which Jackson wires in as the `cause` setter of every Throwable) and for raw
 * throwables in write-only admin views. The [StackTraceElement] serializer writes exactly the six
 * keys, in the same order, that the bean serializer produced on JDK 8: rows written by this code are
 * byte-identical to rows written by a JDK 8 deployment, and a rolled-back deployment reads them with
 * Jackson's built-in deserializer. A JDK 9+ deployment that ran under `--add-opens` wrote four more
 * keys (`classLoaderName`, `moduleName`, `moduleVersion`, `format`); those rows still read back, since
 * the deserializer accepts `declaringClass` or `className` and ignores every other key, but such a
 * deployment now writes the six-key shape, so frames rebuilt from stored rows carry no module or
 * class-loader name and print without the `app//` or `java.base/` prefix. That is a deliberate
 * narrowing to the shape JDK 8 rows always had.
 */
internal class ThrowableModule : SimpleModule("skipper-throwable") {
    init {
        addSerializer(StackTraceElement::class.java, StackTraceElementSerializer())
        addDeserializer(StackTraceElement::class.java, StackTraceElementDeserializer())
        setSerializerModifier(DropThrowableFieldsOnWrite())
        setDeserializerModifier(DropThrowableFieldsOnRead())
    }

    private class StackTraceElementSerializer : StdSerializer<StackTraceElement>(StackTraceElement::class.java) {
        override fun serialize(
            value: StackTraceElement,
            gen: JsonGenerator,
            provider: SerializerProvider
        ) {
            gen.writeStartObject()
            gen.writeStringField("declaringClass", value.className)
            gen.writeStringField("methodName", value.methodName)
            gen.writeStringField("fileName", value.fileName)
            gen.writeNumberField("lineNumber", value.lineNumber)
            gen.writeStringField("className", value.className)
            gen.writeBooleanField("nativeMethod", value.isNativeMethod)
            gen.writeEndObject()
        }
    }

    private class StackTraceElementDeserializer : StdDeserializer<StackTraceElement>(StackTraceElement::class.java) {
        override fun deserialize(
            p: JsonParser,
            ctxt: DeserializationContext
        ): StackTraceElement {
            var token = p.currentToken
            if (token == JsonToken.START_OBJECT) {
                token = p.nextToken()
            } else if (token != JsonToken.FIELD_NAME) {
                return ctxt.handleUnexpectedToken(StackTraceElement::class.java, p) as StackTraceElement
            }
            var className = ""
            var methodName = ""
            var fileName: String? = null
            var lineNumber = -1
            while (token == JsonToken.FIELD_NAME) {
                val name = p.currentName
                p.nextToken()
                when (name) {
                    // The historical bean form carries both keys with the same value; either is enough.
                    "declaringClass", "className" -> className = p.valueAsString ?: className
                    "methodName" -> methodName = p.valueAsString ?: methodName
                    "fileName" -> fileName = p.valueAsString
                    "lineNumber" -> lineNumber = p.valueAsInt
                    // nativeMethod, format, moduleName, moduleVersion, classLoaderName, and anything a
                    // future JDK adds are not needed to rebuild the element.
                    else -> p.skipChildren()
                }
                token = p.nextToken()
            }
            return StackTraceElement(className, methodName, fileName, lineNumber)
        }
    }

    private class DropThrowableFieldsOnWrite : BeanSerializerModifier() {
        override fun changeProperties(
            config: SerializationConfig,
            beanDesc: BeanDescription,
            beanProperties: MutableList<BeanPropertyWriter>
        ): MutableList<BeanPropertyWriter> {
            if (!isThrowable(beanDesc)) return beanProperties
            return beanProperties.filterNot { isJdkField(it.member) }.toMutableList()
        }
    }

    private class DropThrowableFieldsOnRead : BeanDeserializerModifier() {
        override fun updateProperties(
            config: DeserializationConfig,
            beanDesc: BeanDescription,
            propDefs: MutableList<BeanPropertyDefinition>
        ): MutableList<BeanPropertyDefinition> {
            if (!isThrowable(beanDesc)) return propDefs
            // A property that also has a setter or a creator parameter is written through those, so
            // only the ones Jackson would have to assign through the private field are dropped.
            return propDefs
                .filterNot { it.hasField() && !it.hasSetter() && !it.hasConstructorParameter() && isJdkField(it.field) }
                .toMutableList()
        }
    }

    private companion object {
        fun isThrowable(beanDesc: BeanDescription): Boolean = Throwable::class.java.isAssignableFrom(beanDesc.beanClass)

        /**
         * A field declared by a JDK class, whose private fields are what the module system keeps closed.
         * `java.base` classes come from the bootstrap loader (null on every JDK since 8); other JDK
         * modules such as `java.sql` load through the platform loader on JDK 9+, so the `java.` package
         * prefix, which only the JDK may define, covers those.
         */
        fun isJdkField(member: AnnotatedMember?): Boolean =
            member is AnnotatedField &&
                (member.declaringClass.classLoader == null || member.declaringClass.name.startsWith("java."))
    }
}
