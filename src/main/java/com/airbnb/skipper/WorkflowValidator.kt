package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.util.allDeclaredFields
import com.airbnb.skipper.util.allDeclaredMethods
import java.lang.reflect.Method
import javax.inject.Inject

/**
 * A runtime validator for Workflow creation and invocations.
 */
class WorkflowValidator
    @Inject
    constructor(private val serde: Serde) {
        /**
         * Defines a rule for validating a workflow creation. This is the closest we can get to compile-time validation.
         */
        private fun interface CreationRule {
            fun evaluate(workflow: Class<out Workflow>): List<String>
        }

        /**
         * Defines a rule for validating a workflow invocation.
         */
        private fun interface InvocationRule {
            fun evaluate(
                workflow: Class<out Workflow>,
                method: Method,
                invocationInput: Any?,
                isRunAsync: Boolean,
                isDetached: Boolean,
            ): List<String>
        }

        /**
         * Validates that the workflow class does not contain multiple methods with the same name annotated with `@WorkflowMethod` or `@SignalMethod`.
         */
        private val workflowMethodOverloadNotAllowed = CreationRule { workflow ->
            val workflowMethodNames = workflow.allDeclaredMethods(Workflow::class.java).filter {
                !it.isSynthetic &&
                    (it.isAnnotationPresent(WorkflowMethod::class.java) || it.isAnnotationPresent(SignalMethod::class.java))
            }.map { it.name }
            val duplicateWorkflowMethodNames = workflowMethodNames.groupingBy { it }.eachCount().filter { it.value > 1 }
            if (duplicateWorkflowMethodNames.isNotEmpty()) {
                listOf(
                    "Workflow class ${workflow.name} contains multiple methods with the same name annotated with @WorkflowMethod or @SignalMethod: $duplicateWorkflowMethodNames"
                )
            } else {
                emptyList()
            }
        }

        /**
         * Validates that the workflow method has a single argument.
         */
        private val workflowMethodsMustHaveAtMostOneArgument = CreationRule { workflow ->
            val methods = workflow.allDeclaredMethods(Workflow::class.java).filter {
                !it.isSynthetic &&
                    (it.isAnnotationPresent(WorkflowMethod::class.java) || it.isAnnotationPresent(SignalMethod::class.java))
            }
            val errors = mutableListOf<String>()
            for (method in methods) {
                // Use getUserParameterCount to exclude the Continuation parameter that the
                // Kotlin compiler appends to suspend functions.
                if (SuspendSupport.getUserParameterCount(method) > 1) {
                    errors.add("Workflow method ${workflow.name}.${method.name} must have at most one argument")
                }
            }
            errors
        }

        /**
         * Validates that the workflow class has at least one method annotated with `@WorkflowMethod`.
         */
        private val workflowMustHaveAtLeastOneWorkflowMethod = CreationRule { workflow ->
            val workflowMethodNames = workflow.allDeclaredMethods(Workflow::class.java).filter {
                !it.isSynthetic && it.isAnnotationPresent(WorkflowMethod::class.java)
            }.map { it.name }
            if (workflowMethodNames.isEmpty()) {
                listOf("Workflow class ${workflow.name} must have at least one method annotated with @WorkflowMethod")
            } else {
                emptyList()
            }
        }

        /**
         * Validates that all fields annotated with `@StateField` are serializable. At invocation time, we don't
         * have values for state field, so we can't fully validate that the field is serializable, therefore this
         * rule will only use the field type to determine if it is serializable, but cannot guarantee (unlike the
         * workflow input rule).
         */
        private val workflowStateFieldsMustBeSerializable = CreationRule { workflow ->
            val stateFields = workflow.allDeclaredFields(Workflow::class.java).filter { it.isAnnotationPresent(StateField::class.java) }
            val nonSerializableFields = stateFields.mapNotNull { field ->
                try {
                    serde.validateSerializableType(field.type)
                    null
                } catch (e: Exception) {
                    field.name
                }
            }
            if (nonSerializableFields.isNotEmpty()) {
                listOf(
                    "Workflow class ${workflow.name} contains non-serializable fields annotated with @WorkflowState: " +
                        nonSerializableFields.joinToString(", ")
                )
            } else {
                emptyList()
            }
        }

        /**
         * Validates that the input to the workflow method is serializable.
         */
        private val invocationInputIsSerializable = InvocationRule { workflow, method, input, _, _ ->
            try {
                serde.validateSerializableObject(input)
                emptyList()
            } catch (e: Exception) {
                listOf("The argument for ${workflow.name}.${method.name} is not serializable: ${e.message}")
            }
        }

        /**
         * Validates that signal methods opting into persistence (`@SignalMethod(persist = true)`) have a
         * serializable argument type, so that the signal input can be durably stored and later replayed.
         *
         * This mirrors the conditional-serializability contract enforced for compensable `@Execute` actions
         * (see `ActionValidator.validateCompensableActionParameters`). No-arg persisted signals are valid:
         * we persist the invocation with a null input.
         */
        private val persistableSignalArgMustBeSerializable = CreationRule { workflow ->
            val persistableSignalMethods = workflow.allDeclaredMethods(Workflow::class.java).filter {
                !it.isSynthetic &&
                    it.isAnnotationPresent(SignalMethod::class.java) &&
                    it.getAnnotation(SignalMethod::class.java).persist
            }
            val errors = mutableListOf<String>()
            for (method in persistableSignalMethods) {
                // Use getUserParameterCount/getUserParameterTypes to exclude the Continuation
                // parameter that the Kotlin compiler appends to suspend functions.
                if (SuspendSupport.getUserParameterCount(method) == 0) {
                    continue
                }
                val parameterType = SuspendSupport.getUserParameterTypes(method)[0]
                try {
                    serde.validateSerializableType(parameterType)
                } catch (e: Exception) {
                    errors.add(
                        "Signal method ${workflow.name}.${method.name} opts into persistence " +
                            "(@SignalMethod(persist = true)) but its argument type is not serializable: ${e.message}"
                    )
                }
            }
            errors
        }

        private val workflowMethodMustReturnFutureWhenInvocationIsAsync = InvocationRule { workflow, method, _, isRunAsync, isDetached ->
            val isWorkflowMethod = method.isAnnotationPresent(WorkflowMethod::class.java)
            // Suspend functions have bytecode return type Object (not CompletableFuture).
            // They ARE supported with runAsync=true — the suspend proxy chains on the result
            // future regardless of execution mode, so no CompletableFuture return is needed.
            val returnsFuture = java.util.concurrent.CompletableFuture::class.java.isAssignableFrom(method.returnType)
            // Detached invocations never read the result, so the rule's premise — that a non-future
            // return type would force the caller to block on a result the async mode cannot provide —
            // does not hold. Any result-less signature is valid when detached.
            if (isWorkflowMethod && isRunAsync && !isDetached && !returnsFuture && !SuspendSupport.isSuspendFunction(method)) {
                listOf(
                    "Workflow method ${workflow.name}.${method.name} must return CompletableFuture when invocation is async (runAsync=true), but returns ${method.returnType.name}"
                )
            } else {
                emptyList()
            }
        }

        /**
         * Validates that a detached (fire-and-forget) invocation targets a workflow method that produces no
         * result. A detached call returns before the workflow executes, so there is no value to hand back —
         * allowing a result-bearing signature would silently resolve the caller with `null`.
         */
        private val detachedInvocationMustNotReturnAResult = InvocationRule { workflow, method, _, _, isDetached ->
            if (!isDetached || !method.isAnnotationPresent(WorkflowMethod::class.java)) {
                emptyList()
            } else {
                // A null effective return type means Kotlin metadata was unavailable; skip the check
                // rather than report a false positive, per getEffectiveReturnType's contract.
                val returnType = SuspendSupport.getEffectiveReturnType(method)
                if (returnType == null || producesNoResult(method, returnType)) {
                    emptyList()
                } else {
                    listOf(
                        "Workflow method ${workflow.name}.${method.name} cannot be invoked detached because it " +
                            "returns a result (${returnType.name}). A detached invocation returns before the " +
                            "workflow runs, so declare the method to return Unit/void (or CompletableFuture<Void>), " +
                            "or drop detached() and await the result."
                    )
                }
            }
        }

        /**
         * Whether [effectiveReturnType] carries no value the caller could read. `CompletableFuture` is
         * inspected through its type argument, since the future itself is only a carrier.
         */
        private fun producesNoResult(
            method: Method,
            effectiveReturnType: Class<*>
        ): Boolean {
            if (java.util.concurrent.CompletableFuture::class.java.isAssignableFrom(effectiveReturnType)) {
                val typeArgument =
                    (method.genericReturnType as? java.lang.reflect.ParameterizedType)
                        ?.actualTypeArguments
                        ?.singleOrNull() as? Class<*>
                // A raw or non-class type argument is unverifiable — accept rather than false-positive.
                return typeArgument == null || isVoidLike(typeArgument)
            }
            return isVoidLike(effectiveReturnType)
        }

        private fun isVoidLike(type: Class<*>): Boolean = type == Void.TYPE || type == Void::class.java || type == Unit::class.java

        /**
         * The list of rules to be applied when validating a workflow invocation.
         */
        private val invocationRules = listOf(
            invocationInputIsSerializable,
            workflowMethodMustReturnFutureWhenInvocationIsAsync,
            detachedInvocationMustNotReturnAResult
        )

        private val creationRules = listOf(
            workflowMethodOverloadNotAllowed,
            workflowMethodsMustHaveAtMostOneArgument,
            workflowMustHaveAtLeastOneWorkflowMethod,
            workflowStateFieldsMustBeSerializable,
            persistableSignalArgMustBeSerializable
        )

        /**
         * Validates a workflow invocation.
         *
         * @param workflow The workflow class to validate
         * @param method The method being invoked
         * @param invocationInput The input to the method
         * @param isRunAsync Whether the invocation is async
         * @param isDetached Whether the invocation is detached from the workflow result (fire-and-forget)
         * @return A list of error messages if the invocation is invalid, or an empty list if the invocation is valid
         */
        fun <T : Workflow> validateInvocation(
            workflow: Class<T>,
            method: Method,
            invocationInput: Any?,
            isRunAsync: Boolean = false,
            isDetached: Boolean = false
        ): List<String> {
            return invocationRules.flatMap { it.evaluate(workflow, method, invocationInput, isRunAsync, isDetached) }
        }

        /**
         * Validates a workflow creation.
         *
         * @param workflow The workflow class to validate
         * @return A list of error messages if the creation is invalid, or an empty list if the creation is valid
         */
        fun <T : Workflow> validateCreation(workflow: Class<T>): List<String> {
            return creationRules.flatMap { it.evaluate(workflow) }
        }
    }
