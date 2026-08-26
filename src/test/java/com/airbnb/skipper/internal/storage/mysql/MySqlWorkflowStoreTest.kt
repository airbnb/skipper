package com.airbnb.skipper.internal.storage.mysql

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.BaseWorkflowStoreTest
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.testutils.MySqlTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

// Note: BaseWorkflowStoreTest.TEST_REQUEST_CONTEXT_SERDE provides the round-trip behaviour;
// this test deliberately does not import any Airbnb-specific serde to keep the OSS storage
// suite self-contained.
@ExtendWith(MySqlTestSetupExtension::class)
class MySqlWorkflowStoreTest : BaseWorkflowStoreTest() {
    private lateinit var workflowStore: WorkflowStore
    private lateinit var transactionManager: JdbcTransactionManager
    private lateinit var metrics: Metrics
    private lateinit var serde: Serde
    private lateinit var simplePojoSerde: SimplePojoSerde
    private lateinit var clock: Clock

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.config.mySqlDataSource = MySqlTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = MySqlWorkflowStore.Factory()
        deps.config.scheduler = MySqlScheduler.Factory()
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
        transactionManager = JdbcTransactionManager(deps.config.mySqlDataSource!!)
    }

    override fun workflowStore(): WorkflowStore {
        return workflowStore
    }

    override fun createWorkflowStore(owner: String): WorkflowStore {
        return MySqlWorkflowStore(
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
