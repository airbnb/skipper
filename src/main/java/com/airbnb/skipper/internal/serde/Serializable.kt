package com.airbnb.skipper.internal.serde

/**
 * Annotation to indicate that a class is serializable.
 *
 * Classes annotated with this will be assumed to be serializable by the [Serde] implementations,
 * and therefore will bypass the validation checks. This is useful for classes that lose information
 * when serialized, but are still considered serializable, for instance date times and durations.
 */
@Target(AnnotationTarget.CLASS) // Restricts usage to classes
@Retention(AnnotationRetention.RUNTIME) // Annotation is retained at runtime
annotation class Serializable
