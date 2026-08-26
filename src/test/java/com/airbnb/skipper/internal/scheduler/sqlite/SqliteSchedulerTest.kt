package com.airbnb.skipper.internal.scheduler.sqlite

import com.airbnb.skipper.internal.scheduler.BaseSchedulerTest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(SqliteTestSetupExtension::class)
class SqliteSchedulerTest : BaseSchedulerTest() {
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(mockClock)
        deps.config.schedulerTaskLeaseDuration = leaseDuration
        deps.config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        scheduler = deps.scheduler
    }

    override fun scheduler(): Scheduler = scheduler
}
