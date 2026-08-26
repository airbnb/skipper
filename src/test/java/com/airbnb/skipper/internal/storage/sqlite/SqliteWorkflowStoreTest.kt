package com.airbnb.skipper.internal.storage.sqlite

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.BaseWorkflowStoreTest
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Runs the full [BaseWorkflowStoreTest] suite against the SQLite-backed [WorkflowStore], swapping
 * the server-backed JDBC types for their SQLite analogues.
 *
 * This backend needs **no external infrastructure**: [SqliteTestSetupExtension] owns a shared,
 * named in-memory SQLite database (held alive by a keep-alive connection) and exposes it as
 * [SqliteTestSetupExtension.DB_DATA_SOURCE]. That single DataSource is the one database that the
 * runtime-built store ([SqliteWorkflowStore.Factory]), the directly-constructed
 * [JdbcTransactionManager], and the `createWorkflowStore(owner)` stores all talk to.
 *
 * Sharing across owners comes from that **common DataSource**, not from reusing a single
 * transaction-manager instance: [createWorkflowStore] builds a fresh [SqliteWorkflowStore] but
 * hands it the [transactionManager] that wraps [SqliteTestSetupExtension.DB_DATA_SOURCE], so a
 * second owner (e.g. `"test-owner2"`) sees the same in-memory database — this is what lets
 * `BaseWorkflowStoreTest`'s multi-owner case pass.
 *
 * Note: `BaseWorkflowStoreTest.TEST_REQUEST_CONTEXT_SERDE` provides the round-trip behaviour; this
 * test deliberately does not import any Airbnb-specific serde to keep the OSS storage suite
 * self-contained.
 */
@ExtendWith(SqliteTestSetupExtension::class)
class SqliteWorkflowStoreTest : BaseWorkflowStoreTest() {
    private lateinit var workflowStore: WorkflowStore
    private lateinit var transactionManager: JdbcTransactionManager
    private lateinit var metrics: Metrics
    private lateinit var serde: Serde
    private lateinit var simplePojoSerde: SimplePojoSerde
    private lateinit var clock: Clock

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        // Override the runtime's default serde so the OSS storage round-trip uses a payload-
        // agnostic String <-> bytes serde (defined in BaseWorkflowStoreTest) instead of any
        // Airbnb-specific encoding.
        deps.config.requestContextSerde = ComponentFactory { TEST_REQUEST_CONTEXT_SERDE }

        val runtime = deps.runtime
        workflowStore = runtime.workflowStore.get()
        metrics = runtime.metrics.get()
        serde = runtime.serde.get()
        simplePojoSerde = SimplePojoSerde()
        clock = deps.clock
        transactionManager = JdbcTransactionManager.SqliteFactory().create(deps.config)
    }

    override fun workflowStore(): WorkflowStore {
        return workflowStore
    }

    override fun createWorkflowStore(owner: String): WorkflowStore {
        return SqliteWorkflowStore(
            transactionManager,
            metrics,
            serde,
            simplePojoSerde,
            TEST_REQUEST_CONTEXT_SERDE,
            clock,
            owner,
            SkipperConfig.DEFAULT_TABLE_PREFIX
        )
    }
}
