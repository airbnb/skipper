package com.airbnb.skipper.integtest

import com.airbnb.skipper.testutils.TestRuntime

/**
 * SQLite-backed run of [BaseWorkflowIntegTest].
 *
 * The [TestRuntime] already defaults to the self-bootstrapping SQLite store + scheduler (an
 * ephemeral, per-runtime in-memory database), so the full base suite — compensation, checkpointing,
 * timeout, and request-context propagation — runs SQLite-native here with zero external
 * infrastructure and no internal dependencies.
 */
class SqliteWorkflowIntegTest : BaseWorkflowIntegTest() {
    override fun createTestRuntime(): TestRuntime = TestRuntime()
}
