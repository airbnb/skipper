package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.internal.reflection.ReflectionUtils
import io.vavr.control.Option
import java.lang.reflect.Method
import javassist.util.proxy.ProxyObject

/**
 * ActionInspector provides a convenient interface to interact with action classes by abstracting
 * away the reflection low level details, particularly for compensation-related method detection and
 * validation.
 */
class ActionInspector {
    private val actionClass: Class<out Actions>

    constructor(actionClass: Class<out Actions>) {
        this.actionClass = getBaseActionClass(actionClass)
    }

    constructor(actionObject: Actions) {
        this.actionClass = getBaseActionClass(actionObject.javaClass)
    }

    /**
     * Checks if the given action method has a corresponding compensation method defined.
     *
     * @param methodName The name of the action method to check
     * @return true if there is a method annotated with @Compensate that references this action
     *   method
     */
    fun hasCompensationMethod(methodName: String): Boolean {
        return ReflectionUtils.getAllDeclaredMethods(actionClass, Actions::class.java).stream()
            .filter { method -> !method.isSynthetic }
            .filter { method -> method.isAnnotationPresent(Compensate::class.java) }
            .anyMatch { method ->
                val annotation = method.getAnnotation(Compensate::class.java)
                methodName == annotation.forExecute
            }
    }

    /**
     * Gets the compensation method for the given action method.
     *
     * @param methodName The name of the action method to get compensation for
     * @return Option containing the compensation method, or None if no compensation method is found
     */
    fun getCompensationMethod(methodName: String): Option<Method> {
        return Option.ofOptional(
            ReflectionUtils.getAllDeclaredMethods(actionClass, Actions::class.java).stream()
                .filter { method -> !method.isSynthetic }
                .filter { method -> method.isAnnotationPresent(Compensate::class.java) }
                .filter { method ->
                    val annotation = method.getAnnotation(Compensate::class.java)
                    methodName == annotation.forExecute
                }
                .findFirst()
        )
    }

    /**
     * If the action class is a proxy, the name will be the name of the proxy class, which is
     * different from the real Action class name. This method will return the real action class.
     *
     * @param actionClass The potentially proxied action class
     * @return The base (non-proxy) action class
     */
    @Suppress("UNCHECKED_CAST")
    private fun getBaseActionClass(actionClass: Class<out Actions>): Class<out Actions> {
        // Check if the class is a proxy created by Java Assist
        if (ProxyObject::class.java.isAssignableFrom(actionClass)) {
            // This is a proxy class, so we need to get the real action class
            // If Java Assist is used, typically the superclass of the proxy is the actual class
            val superClass: Class<*>? = actionClass.superclass
            if (superClass != null && Actions::class.java.isAssignableFrom(superClass)) {
                return superClass as Class<out Actions>
            }
        }
        return actionClass
    }
}
