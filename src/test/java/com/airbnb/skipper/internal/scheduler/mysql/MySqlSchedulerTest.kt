package com.airbnb.skipper.internal.scheduler.mysql

import com.airbnb.skipper.internal.scheduler.BaseSchedulerTest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.testutils.MySqlTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MySqlTestSetupExtension::class)
class MySqlSchedulerTest : BaseSchedulerTest() {
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(mockClock)
        deps.config.schedulerTaskLeaseDuration = leaseDuration
        deps.config.mySqlDataSource = MySqlTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = MySqlWorkflowStore.Factory()
        deps.config.scheduler = MySqlScheduler.Factory()
        scheduler = deps.scheduler
    }

    override fun scheduler(): Scheduler = scheduler
}
