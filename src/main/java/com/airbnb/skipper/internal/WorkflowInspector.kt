package com.airbnb.skipper.internal

import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.SuspendSupport
import com.airbnb.skipper.ValidationError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.reflection.ReflectionUtils
import io.vavr.collection.List
import io.vavr.collection.Map
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.HashMap
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * WorkflowInspector provides a convenient interface to interact with a workflow object by
 * abstracting away the reflection low level details.
 */
class WorkflowInspector(private val workflow: Workflow) {
    private val workflowClass: Class<out Workflow> = workflow.javaClass

    /** Validates that the workflow object adheres to the specification. */
    fun validate() {
        if (getWorkflowMethods().isEmpty) {
            throw ValidationError(
                "workflow class %s must have at least one method annotated with @WorkflowMethod",
                getWorkflowClass().name,
            )
        }
        getWorkflowMethods()
            .appendAll(getSignalMethods())
            .forEach { m ->
                // Use getUserParameterCount to ignore the Continuation parameter that the
                // Kotlin compiler appends to suspend functions.
                if (SuspendSupport.getUserParameterCount(m) > 1) {
                    throw ValidationError(
                        "workflow method %s must have at most one parameter",
                        m.name,
                    )
                }
            }
    }

    /**
     * Returns the current state of the workflow object.
     *
     * @return A map with the state field names as keys and their values as values.
     */
    fun getState(): Map<String, Any?> {
        val result: MutableMap<String, Any?> = HashMap()
        for (field in getStateFields().collect(Collectors.toList())) {
            try {
                if (!field.isAccessible) {
                    field.isAccessible = true
                }
                result[field.name] = field.get(workflow)
            } catch (e: IllegalAccessException) {
                throw ValidationError(
                    "unable to access state field %s, it must be public",
                    field.name,
                )
            }
        }
        return io.vavr.collection.HashMap.ofAll(result)
    }

    /**
     * Sets the state of the workflow object.
     *
     * @param newState A map with the state field names as keys and their values as values. In case
     *   a field is not present in the map, it will be left unchanged. In case a field is present in
     *   the map but does not exist in the workflow object, a warning is logged and the field is
     *   skipped — this allows safe rolling deploys and workflow evolution (adding/removing
     *   {@code @StateField} fields without breaking in-flight instances).
     */
    fun setState(newState: Map<String, Any?>) {
        val stateFieldsByName = HashMap<String, Field>()
        getStateFields().forEach { f -> stateFieldsByName[f.name] = f }
        newState
            .keySet()
            .forEach { k ->
                if (!stateFieldsByName.containsKey(k)) {
                    if (k.startsWith("__")) {
                        log.debug(
                            "ignoring unknown framework state field '{}' during setState" +
                                " — this can happen during rolling deploys or schema evolution",
                            k,
                        )
                    } else {
                        log.warn(
                            "Ignoring unknown state field '{}' during setState" +
                                " — this can happen during rolling deploys or workflow evolution." +
                                " The field may have been removed in the current code version.",
                            k,
                        )
                    }
                }
            }
        stateFieldsByName
            .values
            .forEach { s ->
                try {
                    if (newState.containsKey(s.name)) {
                        if (!s.isAccessible) {
                            s.isAccessible = true
                        }
                        s.set(workflow, newState.get(s.name).get())
                    }
                } catch (e: IllegalArgumentException) {
                    val rawVal: Any? = newState.get(s.name).getOrElse(null as Any?)
                    throw ValidationError(
                        "unable to set state field %s with value of type '%s', expected type is" +
                            " '%s'",
                        e,
                        s.name,
                        if (rawVal != null) rawVal.javaClass else "null",
                        s.type,
                    )
                } catch (e: IllegalAccessException) {
                    throw ValidationError(
                        "unable to access state field %s, it must be public",
                        s.name,
                    )
                }
            }
    }

    fun getWorkflowMethods(): List<Method> {
        return ReflectionUtils.getAllDeclaredMethods(getWorkflowClass(), Workflow::class.java)
            .stream()
            .filter { m -> m.isAnnotationPresent(WorkflowMethod::class.java) }
            .filter { m -> !m.isSynthetic }
            .collect(List.collector())
    }

    fun getSignalMethods(): List<Method> {
        return ReflectionUtils.getAllDeclaredMethods(getWorkflowClass(), Workflow::class.java)
            .stream()
            .filter { m -> m.isAnnotationPresent(SignalMethod::class.java) }
            .filter { m -> !m.isSynthetic }
            .collect(List.collector())
    }

    fun getQueryMethods(): List<Method> {
        return ReflectionUtils.getAllDeclaredMethods(getWorkflowClass(), Workflow::class.java)
            .stream()
            .filter { m -> m.isAnnotationPresent(QueryMethod::class.java) }
            .filter { m -> !m.isSynthetic }
            .collect(List.collector())
    }

    /**
     * Returns the signal method with the given name.
     *
     * @param name The name of the signal method to return.
     * @return The signal method with the given name.
     * @throws IllegalArgumentException If no method with the given name exists.
     */
    fun getSignalMethod(name: String): Method {
        return getSignalMethods()
            .find { m -> m.name == name }
            .getOrElseThrow { ValidationError("no method with name $name") }
    }

    /**
     * Returns the workflow method with the given name.
     *
     * @param name The name of the workflow method to return.
     * @return The workflow method with the given name.
     * @throws IllegalArgumentException If no method with the given name exists.
     */
    fun getWorkflowMethod(name: String): Method {
        return getWorkflowMethods()
            .find { m -> m.name == name }
            .getOrElseThrow { ValidationError("no method with name $name") }
    }

    fun getQueryMethod(name: String): Method {
        return getQueryMethods()
            .find { m -> m.name == name }
            .getOrElseThrow { ValidationError("no query method with name $name") }
    }

    private fun getStateFields(): Stream<Field> {
        return ReflectionUtils.getAllDeclaredFields(getWorkflowClass(), Workflow::class.java)
            .stream()
            .filter { f -> f.isAnnotationPresent(StateField::class.java) }
    }

    private fun isProxyClass(): Boolean {
        return workflowClass.name.contains("_\$\$_")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getWorkflowClass(): Class<out Workflow> {
        return if (isProxyClass()) {
            workflowClass.superclass as Class<out Workflow>
        } else {
            workflowClass
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(WorkflowInspector::class.java)

        /**
         * Returns true if the named signal method on the given workflow class opts into persistence
         * via {@code @SignalMethod(persist = true)}. Returns false if the method is not found or
         * does not opt in.
         *
         * @param workflowClass The workflow class to inspect.
         * @param signalMethodName The name of the signal method.
         */
        @JvmStatic
        fun isSignalMethodPersistable(
            workflowClass: Class<out Workflow>,
            signalMethodName: String,
        ): Boolean {
            return ReflectionUtils.getAllDeclaredMethods(workflowClass, Workflow::class.java)
                .stream()
                .filter { m -> !m.isSynthetic }
                .filter { m -> m.isAnnotationPresent(SignalMethod::class.java) }
                .filter { m -> m.name == signalMethodName }
                .findFirst()
                .map { m -> m.getAnnotation(SignalMethod::class.java).persist }
                .orElse(false)
        }
    }
}
