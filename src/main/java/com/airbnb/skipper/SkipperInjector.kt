package com.airbnb.skipper

/**
 * Abstraction over dependency injection for Skipper internals. Skipper core uses this interface
 * instead of depending on any specific DI framework (e.g. Guice). Implementations can delegate to
 * Guice, a simple reflection-based injector, or any other DI mechanism.
 *
 * Two methods are required:
 * - [getInstance] -- create or look up an instance of the given class.
 * - [injectMembers] -- populate injectable fields on an already-created instance.
 */
interface SkipperInjector {
    /**
     * Returns an instance of the given class, creating it if necessary.
     *
     * @param clazz the class to instantiate
     * @param T the type of the instance
     * @return a fully constructed instance of [clazz]
     */
    fun <T> getInstance(clazz: Class<T>): T

    /**
     * Injects dependencies into the fields of an already-created instance.
     *
     * @param instance the object whose injectable fields should be populated
     */
    fun injectMembers(instance: Any)
}
