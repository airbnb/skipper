package com.airbnb.skipper.internal.serde

import com.fasterxml.jackson.databind.cfg.PackageVersion

/**
 * Conditions for tests that pin behaviour of the Jackson release Skipper compiles against (2.9).
 * The build can run the suite on a newer Jackson (`-PjacksonVersion`, see build.gradle.kts) to prove
 * the runtime works there; a few pinned expectations are specific to 2.9's output and are skipped.
 */
object JacksonVersions {
    @JvmStatic
    fun isCompiledAgainstVersion(): Boolean = PackageVersion.VERSION.let { it.majorVersion == 2 && it.minorVersion == 9 }
}
