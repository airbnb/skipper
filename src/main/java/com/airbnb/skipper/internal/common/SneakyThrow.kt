package com.airbnb.skipper.internal.common

/**
 * Lombok-free replacement for the sneaky-throw helper that delombok emits when expanding
 * `@SneakyThrows`.
 *
 * It rethrows the given throwable while bypassing the compiler's checked-exception analysis,
 * exactly as `@SneakyThrows` did: the *original* throwable propagates unchanged (it is never
 * wrapped), and a `null` argument yields a [NullPointerException]. Call sites keep the `throw`
 * keyword (e.g. `throw SneakyThrow.sneakyThrow(ex);`) so the compiler still treats the following
 * code as unreachable, mirroring the original generated code.
 */
object SneakyThrow {
    /**
     * Rethrows [t] without declaring it, returning [RuntimeException] only so callers can write
     * `throw SneakyThrow.sneakyThrow(t);`. This method never returns normally.
     */
    @JvmStatic
    fun sneakyThrow(t: Throwable): RuntimeException {
        return sneakyThrow0<RuntimeException>(t)
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(Throwable::class)
    private fun <T : Throwable> sneakyThrow0(t: Throwable): T {
        throw t as T
    }
}
