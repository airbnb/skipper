package com.airbnb.skipper

import kotlin.reflect.KClass

/**
 * Annotation used to mark a method within an Actions class as responsible for compensating actions
 * that reverse the effects of a previously executed workflow step. This annotation is typically
 * applied to methods that semantically undo the side effects introduced by a corresponding
 * [Execute] method. The [forExecute] parameter links the compensation method to its corresponding
 * execute method, ensuring clear and manageable rollback procedures.
 *
 * This annotation aids in the robust handling of errors and exceptions by providing a systematic
 * approach to reverting changes when a workflow cannot proceed as planned. It's useful in scenarios
 * where workflows involve eventually consistent transactions or other reversible operations that
 * must be undone if later steps fail.
 *
 * Parameters:
 * - [forExecute]: A String value referencing the name of the Execute method this Compensate method
 *   is designed to counteract. This link helps maintain the integrity and traceability of the
 *   workflow's compensatory actions.
 * - [checkpointMode]: The [CheckpointMode] to use when executing this compensation action. Defaults
 *   to [CheckpointMode.DEFAULT]. This controls when the compensation action's result is persisted.
 *   Use [CheckpointMode.IMMEDIATE_CHECKPOINT] for critical compensations that need immediate
 *   persistence, or [CheckpointMode.EVENTUAL_CHECKPOINT] for better performance when many
 *   compensations are executed.
 *
 * Usage: Apply this annotation to methods that are specifically designed to undo the work done by
 * another method annotated with [Execute]. This setup is particularly important in transactional
 * workflows where each action has a potential failure point that requires a rollback.
 *
 * ```
 * public class PaymentsActions extends Actions {
 *     @Execute
 *     public void chargeCreditCard(double amount) {
 *         // Logic to charge a credit card
 *     }
 *
 *     @Compensate(forExecute = "chargeCreditCard")
 *     public void refundCreditCard(double amount) {
 *         // Logic to refund a credit card charge
 *     }
 * }
 * ```
 *
 * This example demonstrates how to define compensation logic that can revert the effects of a
 * payment action should subsequent steps in the workflow fail, ensuring that the system's state
 * remains consistent.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Compensate(
    val forExecute: String,
    val returnType: KClass<*> = Void::class,
    val checkpointMode: CheckpointMode = CheckpointMode.DEFAULT
)
