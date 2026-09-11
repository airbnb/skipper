package com.airbnb.skipper.internal

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import javax.inject.Inject

/**
 * Helper class to map errors that occur during action execution to [SkipperError] instances.
 */
class ActionErrorMapper
    @Inject
    constructor(private val exceptionClassifier: ExceptionClassifier) {
        /**
         * Maps an action execution error to a [RetryableError] or a [NonRetryableError] that contains
         * the underlying cause. This method assumes that `cause` was thrown while executing a method
         * through reflection via the `method.invoke()` method, therefore handles the possible errors
         * that can be thrown by this method. See
         * [Method.invoke](https://docs.oracle.com/javase/8/docs/api/java/lang/reflect/Method.html#invoke-java.lang.Object-java.lang.Object...-)
         *
         * @param error The underlying cause of the error.
         * @param request The action execution request.
         * @param actionExceptionClassifier The action-level exception classifier to use. If null, the
         *   global exception classifier will be used.
         * @return A [RuntimeException] that wraps the underlying cause.
         */
        fun mapActionExecutionError(
            error: Throwable,
            request: ActionExecutor.ExecuteActionRequest,
            actionExceptionClassifier: ExceptionClassifier?,
        ): SkipperError {
            if (error is InvocationTargetException) {
                // The underlying method threw an exception
                return convertToSkipperError(error.cause, request, actionExceptionClassifier)
            }
            if (error is IllegalAccessException) {
                // The underlying method is not accessible
                val newError =
                    NonRetryableError(
                        String.format(
                            "action method %s is not accessible",
                            request.actionMethodName,
                        ),
                        ApplicationError.fromException(error),
                    )
                newError.setStackTrace(error.stackTrace)
                return newError
            }
            if (error is IllegalArgumentException) {
                // The underlying method was passed an invalid argument, typically the argument doesn't
                // match the method signature etc.
                val newError =
                    NonRetryableError(
                        String.format(
                            "action method %s was passed an invalid argument %s",
                            request.actionMethodName,
                            request.arg,
                        ),
                        ApplicationError.fromException(error),
                    )
                newError.setStackTrace(error.stackTrace)
                return newError
            }
            // Bug in the execution code. This should be very rare
            val newError =
                RetryableError(
                    "unexpected error while executing action",
                    ApplicationError.fromException(error),
                    UNEXPECTED_ERROR_DELAY,
                )
            newError.setStackTrace(error.stackTrace)
            return newError
        }

        private fun wrapInvocationException(
            cause: Throwable,
            retryStrategy: RetryStrategy,
            retryCount: Int,
            methodName: String,
            classifier: ExceptionClassifier?,
        ): SkipperError {
            if (cause is RetryableError) {
                // Preserves an explicit retryDelay the action set on the thrown error (e.g. a delay
                // computed from an external rate limiter's response) instead of silently discarding it
                // in favor of a delay computed purely from retryCount. Exhaustion is still decided from
                // the action's configured retryStrategy + retryCount, unaffected by this delay.
                val newError =
                    RetryableError(cause.message, cause.cause, retryStrategy, retryCount, cause.retryDelay)
                newError.setStackTrace(cause.stackTrace)
                return newError
            }
            if (cause is SkipperError) {
                return cause
            }
            // Use the provided classifier if available, otherwise fall back to the global one
            val effectiveClassifier = classifier ?: exceptionClassifier
            if (effectiveClassifier.isRetryable(cause)) {
                val newError =
                    RetryableError(
                        cause.message,
                        ApplicationError.fromException(cause),
                        retryStrategy,
                        retryCount,
                    )
                newError.setStackTrace(cause.stackTrace)
                return newError
            }
            // All unexpected errors are to be considered NonRetryable!
            val nonRetryableError =
                NonRetryableError(
                    String.format(
                        "action method '%s' threw an unexpected exception: %s",
                        methodName,
                        cause.message,
                    ),
                    ApplicationError.fromException(cause),
                )
            nonRetryableError.setStackTrace(cause.stackTrace)
            return nonRetryableError
        }

        /**
         * Converts a generic method invocation error to a [SkipperError].
         *
         * @param error The error as thrown by the Action method.
         * @param request The action execution request.
         * @param actionExceptionClassifier The action-level exception classifier to use. If null, the
         *   global exception classifier will be used.
         * @return A [SkipperError] that wraps the underlying cause. It can be a [RetryableError] or a
         *   [NonRetryableError] if the error is not retryable or if the retry attempts have been
         *   exhausted.
         */
        fun convertToSkipperError(
            error: Throwable?,
            request: ActionExecutor.ExecuteActionRequest,
            actionExceptionClassifier: ExceptionClassifier?,
        ): SkipperError {
            // Get the real cause, this will unwrap errors that are wrapped in CompletionException
            // or ExecutionException which are caused by joining futures, which could happen when the
            // action implementation itself performs the join.
            // Faithful to the Java baseline: unwrapError may return null and the next line dereferences
            // it unguarded (NPE iff null) — never null in practice for an invocation-thrown cause.
            val cause = unwrapError(error)!!
            // One line at WARN: an action failing is expected traffic (it is what retries and
            // compensation are for) and the stack trace is persisted with the error and visible in
            // the admin UI. The trace goes to DEBUG for anyone who wants it in the log too.
            log.warn(
                "action method {}.{} threw an exception. workflowId={}, exceptionClass={}," +
                    " errorMessage={}",
                request.baseActionClass.simpleName,
                request.actionMethodName,
                request.executionContext.workflow.workflowId,
                cause.javaClass.simpleName,
                cause.message,
            )
            log.debug("stack trace of the failed action method {}", request.actionMethodName, cause)
            var mappedError =
                wrapInvocationException(
                    cause,
                    request.retryStrategy,
                    request.getRetryCount(),
                    request.actionMethodName,
                    actionExceptionClassifier,
                )
            if (mappedError is RetryableError) {
                // If retryable error, check if we have exhausted the retry attempts
                mappedError = convertRetryableErrorIfNecessary(mappedError, request)
            }
            return mappedError
        }

        private fun convertRetryableErrorIfNecessary(
            error: RetryableError,
            request: ActionExecutor.ExecuteActionRequest,
        ): SkipperError {
            val retryStrategy = error.retryStrategy.orElse(DEFAULT_RETRY_STRATEGY)
            if (!retryStrategy.nextRetryDelay(request.getRetryCount()).isPresent) {
                if (retryStrategy.isPersistentRetry) {
                    return PersistentRetryableError(error)
                }
                val newError =
                    NonRetryableError(
                        String.format(
                            "action %s has exhausted all retry attempts",
                            request.actionMethodName,
                        ),
                        // In this case cause will be the RetryableError, so we need to get the
                        // real cause
                        if (error.cause != null) error.cause else error,
                    )
                newError.setStackTrace(error.stackTrace)
                return newError
            }
            return error
        }

        // Nullable param/return faithfully mirrors the Java baseline (Throwable unwrapError(Throwable)):
        // it returns e.getCause() (possibly null) for wrapped futures, else e, and never throws on a
        // null cause. The JVM descriptor is unchanged ((Throwable)Throwable) — nullability is metadata
        // only. Only same-package callers use it.
        fun unwrapError(e: Throwable?): Throwable? {
            if (e is ExecutionException || e is CompletionException) {
                return e.cause
            }
            return e
        }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(ActionErrorMapper::class.java)

            private val UNEXPECTED_ERROR_DELAY = Duration.ofSeconds(10)
            private val DEFAULT_RETRY_STRATEGY: RetryStrategy =
                FixedRetryStrategy(Duration.ofSeconds(1), 5)
        }
    }
