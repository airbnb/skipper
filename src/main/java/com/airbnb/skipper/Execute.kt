package com.airbnb.skipper

import kotlin.reflect.KClass

/**
 * Annotation used to designate a method within an Actions class as responsible for advancing the
 * execution of a workflow. This annotation explicitly marks methods that perform key operational
 * steps within the defined business process.
 *
 * Methods annotated with [Execute] are typically core to the workflow's logic, handling the primary
 * actions that drive the workflow forward. This annotation helps the workflow management system
 * identify and execute these crucial methods at the appropriate stages of the workflow lifecycle.
 *
 * Usage: Apply this annotation to methods in classes derived from [Actions] that are meant to
 * execute significant steps in a workflow. These methods should encapsulate logic that progresses
 * the state of the workflow.
 *
 * Example:
 *
 * ```
 * public abstract class OrderActions extends Actions {
 *     @Execute
 *     public void processOrder(int orderId) {
 *         // Logic to process the order
 *     }
 * }
 * ```
 *
 * By marking a method with [Execute], you indicate that it plays a direct role in the operational
 * flow of the workflow, such as processing orders, approving requests, or updating system states.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Execute(
    val returnType: KClass<*> = Void::class,
    val checkpointMode: CheckpointMode = CheckpointMode.DEFAULT,
    /**
     * The name of the class field that contains the retry strategy to use. This field must be of
     * type [RetryStrategy]. If empty, the default strategy for the class will be used.
     */
    val retryStrategy: String = "",
    /**
     * The name of the class field that contains the exception classifier to use. This field must be
     * of type [com.airbnb.skipper.internal.ExceptionClassifier]. If empty, the global exception
     * classifier will be used.
     */
    val exceptionClassifier: String = ""
)
