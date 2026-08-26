package com.airbnb.skipper.internal.reflection

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Utility methods for hierarchy-aware reflection. Walks the class hierarchy to discover methods and
 * fields declared on intermediate base classes, not just the leaf class.
 *
 * This is necessary because [Class.getDeclaredMethods] and [Class.getDeclaredFields] only return
 * members declared directly on the class, missing any annotations on intermediate abstract base
 * classes (e.g., a theoretical SkipperStateMachine's @StateField, @SignalMethod).
 */
object ReflectionUtils {
    /**
     * Returns all declared methods from [clazz] and all its superclasses up to (but not including)
     * [stopClass].
     *
     * @param clazz The class to inspect.
     * @param stopClass The class at which to stop walking the hierarchy (exclusive). Use
     *   `Object.class` to walk the entire hierarchy.
     * @return All declared methods in the hierarchy.
     */
    @JvmStatic
    fun getAllDeclaredMethods(
        clazz: Class<*>,
        stopClass: Class<*>
    ): List<Method> {
        val methods = ArrayList<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != stopClass && current != Any::class.java) {
            methods.addAll(current.declaredMethods)
            current = current.superclass
        }
        return methods
    }

    /**
     * Returns all declared fields from [clazz] and all its superclasses up to (but not including)
     * [stopClass].
     *
     * @param clazz The class to inspect.
     * @param stopClass The class at which to stop walking the hierarchy (exclusive). Use
     *   `Object.class` to walk the entire hierarchy.
     * @return All declared fields in the hierarchy.
     */
    @JvmStatic
    fun getAllDeclaredFields(
        clazz: Class<*>,
        stopClass: Class<*>
    ): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != stopClass && current != Any::class.java) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        return fields
    }
}
