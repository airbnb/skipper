package com.airbnb.skipper

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Workflow.version] argument validation. The named-checkpoint replay semantics
 * are covered by the integ tests in `BaseWorkflowIntegTest.TestVersionGate`.
 */
class WorkflowVersionGateTest {
    /**
     * Test-only subclass that re-exposes the protected [Workflow.version] entry point so the
     * `require(...)` validation can be exercised without standing up the full workflow runtime.
     */
    private open class VersionExposingWorkflow : Workflow() {
        @WorkflowMethod
        fun noop() = Unit

        fun callVersion(
            changeId: String,
            minVersion: Int,
            maxVersion: Int
        ): Int = version(changeId, minVersion, maxVersion)
    }

    @Test
    fun testVersion_rejectsMinVersionBelowOne() {
        val workflow = VersionExposingWorkflow()
        assertThatThrownBy {
            workflow.callVersion("zero-min", minVersion = 0, maxVersion = 1)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("zero-min")
            .hasMessageContaining("minVersion (0)")
    }

    @Test
    fun testVersion_rejectsNegativeMinVersion() {
        val workflow = VersionExposingWorkflow()
        assertThatThrownBy {
            workflow.callVersion("negative-min", minVersion = -1, maxVersion = 2)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun testVersion_rejectsMinVersionGreaterThanMaxVersion() {
        val workflow = VersionExposingWorkflow()
        assertThatThrownBy {
            workflow.callVersion("min-gt-max", minVersion = 3, maxVersion = 2)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("min-gt-max")
            .hasMessageContaining("maxVersion (2)")
    }
}
