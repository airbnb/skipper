package com.airbnb.skipper.testing

import kotlin.reflect.KClass

/**
 * Marks a field on a [WorkflowTest] subclass whose value should be handed to workflows, actions and
 * callback handlers that `@Inject` a field of the same type. The typical use is a mock or fake of a
 * collaborator your actions call:
 *
 * ```kotlin
 * class OrderWorkflowTest : WorkflowTest() {
 *   @Bind val payments: PaymentService = mock()
 * }
 * ```
 *
 * The binding key is the field's declared type, or [to] when given (for a field declared as the
 * concrete fake while the action injects the interface). [qualifier] matches a `@Named` value.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Bind(
    val qualifier: String = "",
    val to: KClass<*> = Void::class,
)
