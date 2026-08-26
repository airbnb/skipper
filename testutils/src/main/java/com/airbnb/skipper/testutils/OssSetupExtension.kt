package com.airbnb.skipper.testutils

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory

/**
 * A JUnit 5 one-shot setup extension for OSS tests.
 *
 * A JUnit 5 extension base that invokes [setup] exactly once before the first test container starts
 * and [close] exactly once after the last test container finishes. Subclasses implement [setup] and
 * [close] to acquire and release a shared, expensive resource (e.g. an in-memory database) across a
 * whole test run.
 *
 * The one-shot semantics are implemented as follows: on the first `beforeAll`, the extension registers
 * itself as a [ExtensionContext.Store.CloseableResource] in the root store (keyed by its concrete
 * class name), so JUnit invokes [close] exactly once when the root context is closed. Later
 * `beforeAll` invocations for additional test classes are no-ops.
 *
 * This is OSS test scaffolding (no Airbnb-internal dependency); it depends only on JUnit 5 and
 * SLF4J. See
 * [the JUnit 5 pattern](https://junit.org/junit5/docs/current/user-guide/#extensions-keeping-state).
 */
abstract class OssSetupExtension :
    BeforeAllCallback,
    ExtensionContext.Store.CloseableResource {
    override fun beforeAll(context: ExtensionContext) {
        // Unique across all usages of a given concrete extension.
        val uniqueKey = this.javaClass.name
        val store = context.root.getStore(ExtensionContext.Namespace.GLOBAL)
        if (store.get(uniqueKey) == null) {
            // First test container invocation: register self so close() runs once at the very end.
            store.put(uniqueKey, this)
            val startMillis = System.currentTimeMillis()
            setup()
            LOG.info("Ran setup for {} took {}ms", this.javaClass, System.currentTimeMillis() - startMillis)
        }
    }

    /** Invoked exactly once before the first test container starts. */
    abstract fun setup()

    /** Invoked exactly once after the last test container finishes (via [CloseableResource]). */
    abstract override fun close()

    companion object {
        private val LOG = LoggerFactory.getLogger(OssSetupExtension::class.java)
    }
}
