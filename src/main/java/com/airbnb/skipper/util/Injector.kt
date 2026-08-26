@file:Suppress("MatchingDeclarationName")

package com.airbnb.skipper.util

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.internal.ActionExecutor
import com.airbnb.skipper.internal.SkipperEngine
import java.lang.reflect.Field
import javax.inject.Provider

/**
 * Holds Skipper-internal components that must be injected into [Workflow] and [Actions]
 * base-class fields. These are set directly by the injection helpers below, so neither
 * [SkipperInjector] implementations nor consumers need to register them.
 */
data class SkipperInternalDeps(
    val actionExecutor: ActionExecutor,
    val skipperEngineProvider: Provider<SkipperEngine>,
    val contextPropagator: ContextPropagator,
    val defaultRetryStrategy: RetryStrategy,
)

/**
 * Injects dependencies into the specified workflow instance and recursively into all its nested `Actions` objects.
 *
 * The consumer's injector is called first to resolve all `@Inject` fields (including any overrides).
 * Then, Skipper-internal fields on [Workflow] (`actionExecutor`, `skipperEngine`, `contextPropagator`)
 * and [Actions] (`retryStrategy`) are set from [internalDeps] **only if the injector did not already
 * set them**. This means:
 * - Guice consumers that override bindings (e.g. `TracingActionExecutor`) keep their overrides.
 * - [com.airbnb.skipper.SimpleInjector] consumers get working defaults without registering internal types.
 */
fun SkipperInjector.injectWorkflowMembers(
    workflowInstance: Any,
    internalDeps: SkipperInternalDeps
) {
    this.injectMembers(workflowInstance)

    if (workflowInstance is Workflow) {
        setIfUninitialized(workflowInstance, "actionExecutor", internalDeps.actionExecutor)
        setIfUninitialized(workflowInstance, "skipperEngine", internalDeps.skipperEngineProvider.get())
        setIfUninitialized(workflowInstance, "contextPropagator", internalDeps.contextPropagator)
    }

    for (
    actionsField in workflowInstance::class.java.getAllFields().filter {
        Actions::class.java.isAssignableFrom(it.type)
    }
    ) {
        actionsField.isAccessible = true
        val actionsInstance = actionsField.get(workflowInstance)
        this.injectActionMembers(actionsInstance, internalDeps)
    }
}

/**
 * Recursively injects dependencies into the specified `actionsInstance` and all nested `Actions` objects.
 *
 * This overload delegates entirely to the injector and does NOT set Skipper-internal fallbacks.
 * Use this when the injector (e.g. Guice) already provides all `@Inject` bindings.
 */
fun SkipperInjector.injectActionMembers(actionsInstance: Any) {
    injectActionMembersInternal(actionsInstance, internalDeps = null)
}

/**
 * Recursively injects dependencies into the specified `actionsInstance` and all nested `Actions` objects.
 *
 * The [Actions.retryStrategy] field is set directly from [internalDeps]; the consumer's injector
 * handles only user-defined fields.
 */
fun SkipperInjector.injectActionMembers(
    actionsInstance: Any,
    internalDeps: SkipperInternalDeps
) {
    injectActionMembersInternal(actionsInstance, internalDeps)
}

private fun SkipperInjector.injectActionMembersInternal(
    actionsInstance: Any,
    internalDeps: SkipperInternalDeps?
) {
    val actionsList = mutableListOf(actionsInstance)
    var i = 0
    while (i < actionsList.size) {
        val actions = actionsList[i]
        this.injectMembers(actions)
        if (actions is Actions && internalDeps != null) {
            setIfUninitialized(actions, "retryStrategy", internalDeps.defaultRetryStrategy)
        }
        actionsList.addAll(
            actions::class.java.getAllFields().filter {
                Actions::class.java.isAssignableFrom(it.type)
            }.mapNotNull {
                it.isAccessible = true
                it.get(actions)
            }
        )
        i++
    }
}

/**
 * Sets a `lateinit var` field on [target] to [value] only if the field has not yet been initialized.
 * This is used to provide Skipper-internal defaults while preserving any value the consumer's
 * injector may have already set (e.g. a Guice override like `TracingActionExecutor`).
 */
private fun setIfUninitialized(
    target: Any,
    fieldName: String,
    value: Any
) {
    var clazz: Class<*>? = target.javaClass
    while (clazz != null && clazz != Any::class.java) {
        val field = try {
            clazz.getDeclaredField(fieldName)
        } catch (_: NoSuchFieldException) {
            clazz = clazz.superclass
            continue
        }
        field.isAccessible = true
        if (field.get(target) == null) {
            field.set(target, value)
        }
        return
    }
}

/**
 * Retrieves all declared fields from this class and recursively collects fields from all superclasses.
 * This function allows access to the complete set of fields up the class hierarchy, which is particularly
 * useful for scenarios requiring full introspection of an object's properties, including those inherited
 * from parent classes.
 */
fun Class<*>.getAllFields(): List<Field> {
    return this.declaredFields.toList() +
        if (this.superclass != null) this.superclass.getAllFields() else listOf()
}
