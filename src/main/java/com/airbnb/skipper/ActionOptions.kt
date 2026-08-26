package com.airbnb.skipper

import java.time.Duration

/**
 * Configuration class for specifying operational parameters when invoking an action. This class provides
 * settings related to timeouts and retry policies that control how an action is executed.
 *
 * @property executionTimeout Specifies the maximum duration allowed for the completion of an action. This
 * includes both the time the action may spend in the task queue and the time taken to execute the action itself.
 * If null, the system defaults or no timeout policy may apply, potentially leading to indefinitely long execution
 * times depending on system behavior.
 *
 * @property retryOptions Specifies the retry policy for the action, detailing how retries should be handled
 * in case of execution failures. This includes settings such as the initial retry interval, backoff coefficient,
 * maximum number of attempts, and specific non-retryable exception types. If null, retries may not be attempted,
 * or system-default retry policies may apply.
 */
data class ActionOptions(
    val executionTimeout: Duration? = null,
    val retryOptions: RetryOptions? = null
)
