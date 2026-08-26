package com.airbnb.skipper

import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Named
import javax.inject.Provider

/**
 * A registry-based [SkipperInjector] that does not depend on any DI framework.
 *
 * Each binding maps a `(type, qualifier)` pair to either a fixed instance (singleton)
 * or a [Provider] that produces a new instance on every call. The qualifier is the
 * `@Named` value (or `null` for unqualified bindings).
 *
 * When [injectMembers] is called, the injector walks every declared `@Inject` field
 * (including inherited fields) and resolves it by type + `@Named` qualifier. Fields
 * for which no binding exists are left untouched.
 *
 * [getInstance] first checks the registry; if no binding is registered for the
 * requested type it falls back to creating one via the no-arg constructor.
 *
 * Example usage:
 * ```
 * val injector = SimpleInjector.builder()
 *     .bind(ActionExecutor::class.java, actionExecutor)
 *     .bind(RetryStrategy::class.java, "defaultRetryStrategy", defaultStrategy)
 *     .build()
 * ```
 */
class SimpleInjector private constructor(
    private val bindings: MutableMap<BindingKey, Provider<*>>
) : SkipperInjector {
    /**
     * A binding key consisting of a type and an optional `@Named` qualifier.
     */
    data class BindingKey(val type: Class<*>, val qualifier: String? = null)

    companion object {
        /** Creates a new builder for constructing a [SimpleInjector]. */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Registers a singleton binding at runtime -- the same instance is returned every time.
     *
     * @param type the type key
     * @param instance the instance to bind
     * @param T the binding type
     */
    fun <T> register(
        type: Class<T>,
        instance: T
    ) {
        bindings[BindingKey(type)] = Provider { instance }
    }

    /**
     * Registers a singleton binding with a `@Named` qualifier at runtime.
     *
     * @param type the type key
     * @param qualifier the `@Named` value
     * @param instance the instance to bind
     * @param T the binding type
     */
    fun <T> register(
        type: Class<T>,
        qualifier: String,
        instance: T
    ) {
        bindings[BindingKey(type, qualifier)] = Provider { instance }
    }

    /**
     * Registers a provider binding at runtime -- the supplier is invoked each time.
     *
     * @param type the type key
     * @param provider supplier invoked each time the type is requested
     * @param T the binding type
     */
    fun <T> registerProvider(
        type: Class<T>,
        provider: Provider<out T>
    ) {
        bindings[BindingKey(type)] = provider
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getInstance(clazz: Class<T>): T {
        val supplier = bindings[BindingKey(clazz)]
        if (supplier != null) {
            return supplier.get() as T
        }
        return try {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance()
            injectMembers(instance as Any)
            instance
        } catch (e: Exception) {
            throw InjectionException(
                "Failed to create instance of ${clazz.name} via no-arg constructor",
                e
            )
        }
    }

    override fun injectMembers(instance: Any) {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                val mods = field.modifiers
                if (Modifier.isStatic(mods) || Modifier.isFinal(mods) || field.isSynthetic) {
                    continue
                }
                if (!isInjectable(field)) {
                    continue
                }
                val qualifier = getNamedQualifier(field)
                val value = findBinding(field.type, qualifier)
                if (value != null) {
                    field.isAccessible = true
                    try {
                        field.set(instance, value)
                    } catch (e: IllegalAccessException) {
                        throw InjectionException(
                            "Failed to inject field '${field.name}' on ${clazz.name}",
                            e
                        )
                    }
                }
            }
            clazz = clazz.superclass
        }
    }

    private fun isInjectable(field: java.lang.reflect.Field): Boolean {
        for (annotation in field.annotations) {
            val name = annotation.annotationClass.java.name
            if (name == "javax.inject.Inject" || name == "com.google.inject.Inject") {
                return true
            }
        }
        return false
    }

    /**
     * Extracts the `@Named` value from a field, checking both `javax.inject.Named`
     * and `com.google.inject.name.Named`.
     */
    private fun getNamedQualifier(field: java.lang.reflect.Field): String? {
        field.getAnnotation(Named::class.java)?.let { return it.value }
        // Also check Guice's @Named
        for (annotation in field.annotations) {
            if (annotation.annotationClass.java.name == "com.google.inject.name.Named") {
                try {
                    val valueMethod = annotation.javaClass.getMethod("value")
                    return valueMethod.invoke(annotation) as? String
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        return null
    }

    private fun findBinding(
        fieldType: Class<*>,
        qualifier: String?
    ): Any? {
        // Exact match by type + qualifier
        val exactSupplier = bindings[BindingKey(fieldType, qualifier)]
        if (exactSupplier != null) {
            return exactSupplier.get()
        }
        // Fallback: assignable match by type + qualifier
        for ((key, value) in bindings) {
            if (key.qualifier == qualifier && fieldType.isAssignableFrom(key.type)) {
                return value.get()
            }
        }
        return null
    }

    /** Fluent builder for [SimpleInjector]. */
    class Builder internal constructor() {
        private val bindings: MutableMap<BindingKey, Provider<*>> = ConcurrentHashMap()

        /**
         * Registers a singleton binding -- the same instance is returned every time.
         *
         * @param type the type key used for lookup during injection
         * @param instance the instance to inject for this type
         * @param T the binding type
         * @return this builder
         */
        fun <T> bind(
            type: Class<T>,
            instance: T
        ): Builder {
            bindings[BindingKey(type)] = Provider { instance }
            return this
        }

        /**
         * Registers a singleton binding with a `@Named` qualifier.
         *
         * @param type the type key used for lookup during injection
         * @param qualifier the `@Named` value
         * @param instance the instance to inject for this type
         * @param T the binding type
         * @return this builder
         */
        fun <T> bind(
            type: Class<T>,
            qualifier: String,
            instance: T
        ): Builder {
            bindings[BindingKey(type, qualifier)] = Provider { instance }
            return this
        }

        /**
         * Registers a provider binding -- the supplier is invoked each time the type is requested.
         *
         * @param type the type key used for lookup during injection
         * @param provider supplier that creates an instance on each call
         * @param T the binding type
         * @return this builder
         */
        fun <T> bindProvider(
            type: Class<T>,
            provider: Provider<out T>
        ): Builder {
            bindings[BindingKey(type)] = provider
            return this
        }

        /** Builds the injector with all registered bindings. */
        fun build(): SimpleInjector = SimpleInjector(ConcurrentHashMap(bindings))
    }
}
