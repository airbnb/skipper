package com.airbnb.skipper

import com.airbnb.skipper.ActionValidator.CreationRule
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.util.allDeclaredFields
import com.airbnb.skipper.util.allDeclaredMethods
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.CompletableFuture
import javax.inject.Inject

/**
 * A runtime validator for Action classes.
 */
class ActionValidator
    @Inject
    constructor(private val serde: Serde) {
        private fun interface CreationRule {
            fun evaluate(action: Class<out Actions>): List<String>
        }

        /**
         * Validates that the action class does not contain multiple methods with the same name annotated with `@Execute` or `@Compensate`.
         */
        private val actionMethodOverloadNotAllowed = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val compensateMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val allMethods = executeMethods + compensateMethods
            val uniqueMethods = allMethods.distinctBy { it.name }
            if (allMethods.size != uniqueMethods.size) {
                listOf("Action class ${actions.name} contains multiple methods with the same name annotated with @Execute or @Compensate")
            } else {
                emptyList()
            }
        }

        /**
         * Validates that the action class has at least one method annotated with `@Execute`.
         */
        private val actionMustHaveAtLeastOneExecuteMethod = CreationRule { actions ->
            val executeMethodNames = actions.allDeclaredMethods(Actions::class.java).filter {
                !it.isSynthetic && it.isAnnotationPresent(Execute::class.java)
            }.map { it.name }
            if (executeMethodNames.isEmpty()) {
                listOf("Action class ${actions.name} must have at least one method annotated with @Execute")
            } else {
                emptyList()
            }
        }

        private val actionMethodReturnTypeMustBeSerializable = CreationRule { actions ->
            val methods = actions.allDeclaredMethods(Actions::class.java).filter {
                !it.isSynthetic &&
                    (it.isAnnotationPresent(Execute::class.java) || it.isAnnotationPresent(Compensate::class.java))
            }
            val errors = mutableListOf<String>()
            for (method in methods) {
                val returnType = when {
                    method.returnType == CompletableFuture::class.java -> {
                        with(method.genericReturnType as ParameterizedType) {
                            getClassFromType(actualTypeArguments[0]) // We know CompletableFuture only has a single type argument
                        }
                    }
                    SuspendSupport.isSuspendFunction(method) -> SuspendSupport.getEffectiveReturnType(method)
                    else -> method.returnType
                }
                if (returnType == null || returnType == Void::class.java || returnType == Unit::class.java) {
                    continue
                }
                try {
                    serde.validateSerializableType(returnType)
                } catch (e: Exception) {
                    errors.add("Action method ${actions.name}.${method.name} return type is not serializable: ${e.message}")
                }
            }
            errors
        }

        private fun getClassFromType(type: Type): Class<*> {
            return if (type is Class<*>) {
                type
            } else if (type is ParameterizedType) {
                type.rawType as Class<*>
            } else {
                throw IllegalArgumentException("Type must be a Class or ParameterizedType")
            }
        }

        /**
         * Validates that the execute method does not have more than one compensation method.
         */
        private val executeMethodMustHaveAtMostOnceCompensationMethod = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val errors = mutableListOf<String>()
            for (executeMethod in executeMethods) {
                val compensationMethods = actions.allDeclaredMethods(Actions::class.java).filter {
                    !it.isSynthetic &&
                        it.isAnnotationPresent(Compensate::class.java) &&
                        it.getAnnotation(Compensate::class.java).forExecute == executeMethod.name
                }
                if (compensationMethods.size > 1) {
                    errors.add(
                        "Action class ${actions.name} contains multiple methods with the same name annotated with @Compensate for execute" +
                            " method ${executeMethod.name}"
                    )
                }
            }
            errors
        }

        /**
         * Validates that the action class does not contain a compensation method that points to a non-existent action method.
         */
        private val validateNoOrphanCompensateMethods = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val compensationMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val errors = mutableListOf<String>()
            for (compensationMethod in compensationMethods) {
                val executeMethod = executeMethods.find { it.name == compensationMethod.getAnnotation(Compensate::class.java).forExecute }
                if (executeMethod == null) {
                    errors.add(
                        "Action class ${actions.name} contains a method annotated with @Compensate for execute method " +
                            "${compensationMethod.getAnnotation(
                                Compensate::class.java
                            ).forExecute} but no corresponding method annotated with @Execute"
                    )
                }
            }
            errors
        }

        private val validateMethodLevelRetryStrategy = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val errors = mutableListOf<String>()
            for (executeMethod in executeMethods) {
                val retryStrategy = executeMethod.getAnnotation(Execute::class.java).retryStrategy
                if (retryStrategy.isEmpty()) {
                    continue
                }
                val retryStrategyField = actions.allDeclaredFields(Actions::class.java).find { it.name == retryStrategy }
                if (retryStrategyField == null) {
                    errors.add("$retryStrategy is not a valid field name in action ${actions.name}#${executeMethod.name}")
                } else if (!RetryStrategy::class.java.isAssignableFrom(retryStrategyField.type)) {
                    errors.add(
                        "retryStrategy field $retryStrategy must implement RetryStrategy interface"
                    )
                }
            }
            errors
        }

        private val validateMethodLevelExceptionClassifier = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val errors = mutableListOf<String>()
            for (executeMethod in executeMethods) {
                val exceptionClassifier = executeMethod.getAnnotation(Execute::class.java).exceptionClassifier
                if (exceptionClassifier.isEmpty()) {
                    continue
                }
                val exceptionClassifierField = actions.allDeclaredFields(Actions::class.java).find { it.name == exceptionClassifier }
                if (exceptionClassifierField == null) {
                    errors.add("$exceptionClassifier is not a valid field name in action ${actions.name}#${executeMethod.name}")
                } else if (!com.airbnb.skipper.internal.ExceptionClassifier::class.java.isAssignableFrom(exceptionClassifierField.type)) {
                    errors.add(
                        "exceptionClassifier field $exceptionClassifier must implement ExceptionClassifier interface"
                    )
                }
            }
            errors
        }

        /**
         * Validates that compensable action methods have exactly 1 parameter and it's serializable.
         */
        private val validateCompensableActionParameters = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val compensationMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val errors = mutableListOf<String>()

            // Build map of execute method name to compensation methods for efficiency
            val executeToCompensationMap = compensationMethods.groupBy {
                it.getAnnotation(Compensate::class.java).forExecute
            }

            // Validate compensable action methods
            for (executeMethod in executeMethods) {
                val compensationMethodsForExecute = executeToCompensationMap[executeMethod.name]
                if (compensationMethodsForExecute != null) {
                    // Rule: Compensable action methods must have exactly 1 user parameter.
                    // Use getUserParameterCount to exclude the Continuation parameter that
                    // the Kotlin compiler appends to suspend functions.
                    val userParamCount = SuspendSupport.getUserParameterCount(executeMethod)
                    if (userParamCount != 1) {
                        errors.add(
                            "Action method ${actions.name}.${executeMethod.name} has compensation but must have exactly 1 input parameter," +
                                " found $userParamCount"
                        )
                    } else {
                        // Rule: Compensable action input parameters must be serializable.
                        // Use getUserParameterTypes to get only user-visible parameter types.
                        val parameterType = SuspendSupport.getUserParameterTypes(executeMethod)[0]
                        try {
                            serde.validateSerializableType(parameterType)
                        } catch (e: Exception) {
                            errors.add(
                                "Action method ${actions.name}.${executeMethod.name} input parameter type is not serializable: ${e.message}"
                            )
                        }
                    }
                }
            }
            errors
        }

        /**
         * Validates that compensation methods return void.
         */
        private val validateCompensationMethodReturnType = CreationRule { actions ->
            val compensationMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val errors = mutableListOf<String>()

            for (compensationMethod in compensationMethods) {
                val effectiveReturnType = SuspendSupport.getEffectiveReturnType(compensationMethod) ?: continue
                if (effectiveReturnType != Void.TYPE && effectiveReturnType != Unit::class.java) {
                    errors.add(
                        "Compensation method ${actions.name}.${compensationMethod.name} must return void, found ${effectiveReturnType.name}"
                    )
                }
            }
            errors
        }

        /**
         * Validates that compensation methods have 1 or 2 parameters.
         */
        private val validateCompensationMethodParameterCount = CreationRule { actions ->
            val compensationMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val errors = mutableListOf<String>()

            for (compensationMethod in compensationMethods) {
                // Use getUserParameterCount to exclude the Continuation parameter that the
                // Kotlin compiler appends to suspend functions.
                val userParamCount = SuspendSupport.getUserParameterCount(compensationMethod)
                if (userParamCount < 1 || userParamCount > 2) {
                    errors.add(
                        "Compensation method ${actions.name}.${compensationMethod.name} must have 1 or 2 input parameters," +
                            " found $userParamCount"
                    )
                }
            }
            errors
        }

        /**
         * Validates that compensation method parameter types match their corresponding action method.
         */
        private val validateCompensationMethodParameterTypes = CreationRule { actions ->
            val executeMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Execute::class.java) }
            val compensationMethods = actions.allDeclaredMethods(
                Actions::class.java
            ).filter { !it.isSynthetic && it.isAnnotationPresent(Compensate::class.java) }
            val errors = mutableListOf<String>()

            for (compensationMethod in compensationMethods) {
                val executeMethodName = compensationMethod.getAnnotation(Compensate::class.java).forExecute
                val executeMethod = executeMethods.find { it.name == executeMethodName }

                // Use getUserParameterCount/getUserParameterTypes to exclude Continuation params
                // that the Kotlin compiler appends to suspend functions.
                val executeUserParamCount = executeMethod?.let { SuspendSupport.getUserParameterCount(it) } ?: 0
                val compensateUserParamCount = SuspendSupport.getUserParameterCount(compensationMethod)

                if (executeMethod != null && executeUserParamCount == 1 && compensateUserParamCount >= 1) {
                    // Rule: First compensation method parameter type must match action method input type
                    val executeParamType = SuspendSupport.getUserParameterTypes(executeMethod)[0]
                    val compensateParamType = SuspendSupport.getUserParameterTypes(compensationMethod)[0]

                    if (executeParamType != compensateParamType) {
                        errors.add(
                            "Compensation method ${actions.name}.${compensationMethod.name} first parameter type ${compensateParamType.name} must" +
                                " match action method $executeMethodName input type ${executeParamType.name}"
                        )
                    }

                    // Rule: If compensation method has 2 user parameters, second parameter must match action method return type
                    // For CompletableFuture<T> return types, the compensation parameter should be T, not CompletableFuture<T>
                    if (compensateUserParamCount == 2) {
                        val executeReturnType = when {
                            executeMethod.returnType == CompletableFuture::class.java -> {
                                with(executeMethod.genericReturnType as ParameterizedType) {
                                    getClassFromType(actualTypeArguments[0])
                                }
                            }
                            SuspendSupport.isSuspendFunction(executeMethod) ->
                                SuspendSupport.getEffectiveReturnType(executeMethod)
                            else -> executeMethod.returnType
                        }

                        val compensateSecondParamType = SuspendSupport.getUserParameterTypes(compensationMethod)[1]

                        // Skip the check when metadata is unavailable (executeReturnType == null)
                        if (executeReturnType != null &&
                            executeReturnType != compensateSecondParamType &&
                            executeReturnType != Void.TYPE &&
                            executeReturnType != Void::class.java &&
                            executeReturnType != Unit::class.java
                        ) {
                            val returnTypeDescription = when {
                                executeMethod.returnType == CompletableFuture::class.java ->
                                    "CompletableFuture<${executeReturnType.simpleName}> (unwrapped to ${executeReturnType.simpleName})"
                                SuspendSupport.isSuspendFunction(executeMethod) ->
                                    "suspend fun returning ${executeReturnType.simpleName}"
                                else -> executeReturnType.name
                            }

                            errors.add(
                                "Compensation method ${actions.name}.${compensationMethod.name} second parameter type " +
                                    "${compensateSecondParamType.name} must match the unwrapped return type of action method $executeMethodName. " +
                                    "Action method returns $returnTypeDescription, so compensation method should expect ${executeReturnType.name}. " +
                                    "Note: For CompletableFuture<T> return types, the compensation parameter should be T, not CompletableFuture<T>."
                            )
                        }
                    }
                }
            }
            errors
        }

        /**
         * List of rules to validate the creation of an action class.
         */
        private val creationRules = listOf(
            actionMethodOverloadNotAllowed,
            actionMustHaveAtLeastOneExecuteMethod,
            actionMethodReturnTypeMustBeSerializable,
            executeMethodMustHaveAtMostOnceCompensationMethod,
            validateNoOrphanCompensateMethods,
            validateMethodLevelRetryStrategy,
            validateMethodLevelExceptionClassifier,
            validateCompensableActionParameters,
            validateCompensationMethodReturnType,
            validateCompensationMethodParameterCount,
            validateCompensationMethodParameterTypes
        )

        /**
         * Performs validation checks on the action class.
         * @param action The action class to validate
         * @return A list of error messages if any validation checks fail or an empty list if the action class is valid
         */
        fun validateCreation(action: Class<out Actions>): List<String> {
            return creationRules.flatMap { it.evaluate(action) }
        }
    }
