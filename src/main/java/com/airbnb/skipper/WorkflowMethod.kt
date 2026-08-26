package com.airbnb.skipper

import java.lang.annotation.ElementType
import kotlin.reflect.KClass

/**
 * Annotation to denote a method within a [Workflow] class that should be executed as part of a
 * Skipper workflow. This annotation identifies specific functions that are intended to be the
 * executable parts of a workflow, allowing these methods to be managed by the workflow execution
 * engine.
 *
 * Applying this annotation enables the underlying workflow infrastructure to automatically handle
 * method invocations, state transitions, and other aspects of workflow management. It is typically
 * used in conjunction with a workflow factory which creates proxy instances of the workflow
 * classes.
 *
 * **Example:**
 *
 * In the following example, the `execute` method in a workflow class is marked with
 * `@WorkflowMethod`. This signifies that `execute` is a primary action of the workflow, potentially
 * involving multiple actions that are part of the business process being modeled.
 *
 * ```
 * class OrderProcessingWorkflow extends Workflow {
 *     @WorkflowMethod
 *     OrderStatus execute(int orderId) {
 *         // Implementation details here
 *     }
 * }
 * ```
 *
 * Only methods annotated with `@WorkflowMethod` should contain the logic for processing parts of
 * the workflow. These methods are the points of entry into the workflow from external callers or
 * other parts of the system.
 *
 * @see ElementType.METHOD Indicates that this annotation can only be applied to method declarations.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class WorkflowMethod(
    val returnType: KClass<*> = Void::class
)
