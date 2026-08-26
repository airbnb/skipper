package com.airbnb.skipper

import com.airbnb.skipper.SkipperAnnotationNames.DEFAULT_RETRY_STRATEGY
import javax.inject.Inject
import javax.inject.Named

/**
 * Serves as the base class for all action classes within the workflow system. This abstract class must be extended
 * by any class that is intended to perform as Skipper workflow's actions. Extending this class ensures that the workflow
 * system can appropriately manage and execute the actions as part of its process orchestration.
 *
 * Subclasses are expected to define specific actions that can be executed during a workflow's life cycle, annotated
 * with [Execute] or [Compensate] to indicate their role within the workflow execution and compensation strategies.
 *
 * ### Key Points:
 * - **Subclassing**: Only open (non-final) classes with a zero-argument constructor should extend this class to
 *   ensure compatibility with proxy generation and workflow management tools.
 * - **Method Design**: Methods within subclasses should be designed to execute discrete units of work that form
 *   part of a larger business process.
 *
 * ### Example:
 * Here is how you might define a simple action class that extends `Actions`:
 *
 * ```kotlin
 * open class UserActions : Actions() {
 *     @Execute
 *     suspend fun registerUser(userData: UserData) {
 *         // Implementation for registering a user
 *     }
 *
 *     @Compensate(forExecute = "registerUser")
 *     suspend fun rollbackRegistration(userData: UserData) {
 *         // Compensate the user registration in case of failure
 *     }
 * }
 * ```
 *
 * This class structure ensures that all action-specific methods are managed under a unified framework, allowing for
 * sophisticated workflow management and monitoring.
 */
@SkipperOpen
abstract class Actions {
    /**
     * The retry strategy to use when executing this action. The concrete implementation can override this property if needed.
     */
    @Inject
    // Use-site target `field:` is required: javax.inject.Named has no @Target, so without it
    // Kotlin would place the qualifier on the property (not the backing field) and Guice — which
    // reads the Java field — would see an unqualified RetryStrategy and fail to resolve the
    // @Named binding. (com.google.inject.name.Named was @Target(FIELD) so it landed on the field
    // automatically; javax.inject.Named is not, hence the explicit target.)
    @field:Named(DEFAULT_RETRY_STRATEGY)
    open lateinit var retryStrategy: RetryStrategy

    /**
     * Transient, single-use slot holding the checkpoint name set by [named] for the very next
     * action invocation on this proxy. The proxy handler reads this value when dispatching the
     * call and clears it immediately, so it only affects the one invocation that follows
     * `named(...)`. Not part of the persisted workflow state.
     */
    @Transient
    @JvmField
    var pendingCheckpointName: String? = null

    /**
     * Sets a checkpoint name for the next action invocation on this proxy. The name is consumed
     * (cleared) by the proxy handler after a single invocation.
     *
     * Java callers must pass the concrete action class to get a properly-typed return:
     * ```java
     * bookingActions.named(BookingActions.class, "prepare-order").prepareOrder(input);
     * ```
     *
     * Kotlin callers should use the reified extension instead: `actions.named("name")`.
     */
    fun <T : Actions> named(
        type: Class<T>,
        checkpointName: String
    ): T {
        this.pendingCheckpointName = checkpointName
        return type.cast(this)
    }

    /**
     * The retry strategy to use when executing this action. Java clients cannot override the `retryStrategy` property, so if a java client
     * wants to override the default retry strategy, they can override this method instead.
     *
     * ### Example:
     * ```java
     * class MyActions extends Actions {
     *   @Override
     *   public RetryStrategy retryStrategyProvider() {
     *     return new FixedRetryStrategy(5, Duration.ofSeconds(5));
     *   }
     * }
     * ```
     */
    open fun retryStrategyProvider(): RetryStrategy {
        return retryStrategy
    }
}

/**
 * Kotlin-idiomatic extension for invocation-site checkpoint naming. Reified type parameter
 * removes the class-token ceremony required by the Java overload.
 *
 * ```kotlin
 * bookingActions.named("prepare-order").prepareOrder(input)
 * ```
 */
inline fun <reified T : Actions> T.named(checkpointName: String): T {
    this.pendingCheckpointName = checkpointName
    return this
}
