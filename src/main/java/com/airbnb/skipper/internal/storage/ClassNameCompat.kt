package com.airbnb.skipper.internal.storage

/**
 * Provides backward-compatible class name resolution to handle Tempo <-> Skipper migrations.
 *
 * During a service migration from `com.airbnb.tempo` to `com.airbnb.skipper` (or a rollback),
 * action checkpoints stored in the backing store contain fully qualified class names. Internal framework classes
 * differ between the two packages (e.g. `com.airbnb.tempo.CheckpointHelpers` vs
 * `com.airbnb.skipper.CheckpointHelpers`). This utility transparently resolves either name so that
 * in-flight workflows created under one package can be resumed after migration, eliminating errors
 * during the rolling deployment window.
 *
 * Only framework-internal classes whose names start with `com.airbnb.tempo.` or
 * `com.airbnb.skipper.` are subject to the fallback. All other class names (e.g. user-defined
 * workflow/action classes) are resolved as-is.
 */
object ClassNameCompat {
    private const val TEMPO_PKG = "com.airbnb.tempo."
    private const val SKIPPER_PKG = "com.airbnb.skipper."

    /**
     * Loads a class by name, falling back to the equivalent class in the other package (tempo <->
     * skipper) if the primary lookup fails.
     *
     * @throws ClassNotFoundException if the class cannot be found in either package
     */
    @JvmStatic
    @Throws(ClassNotFoundException::class)
    fun forName(className: String): Class<*> {
        try {
            return Class.forName(className)
        } catch (primary: ClassNotFoundException) {
            val fallback: String =
                when {
                    className.startsWith(TEMPO_PKG) ->
                        SKIPPER_PKG + className.substring(TEMPO_PKG.length)
                    className.startsWith(SKIPPER_PKG) ->
                        TEMPO_PKG + className.substring(SKIPPER_PKG.length)
                    else -> throw primary
                }
            try {
                return Class.forName(fallback)
            } catch (ignored: ClassNotFoundException) {
                throw primary // throw original (more informative) exception
            }
        }
    }
}
