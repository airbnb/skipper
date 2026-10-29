package com.airbnb.skipper.internal.scheduler.sqlite

import com.airbnb.skipper.internal.scheduler.BaseSchedulerTest
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.whenever

@ExtendWith(SqliteTestSetupExtension::class)
class SqliteSchedulerTest : BaseSchedulerTest() {
    private lateinit var scheduler: Scheduler
    private lateinit var tablePrefix: String

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(mockClock)
        deps.config.schedulerTaskLeaseDuration = leaseDuration
        deps.config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        scheduler = deps.scheduler
        tablePrefix = deps.config.tablePrefix
    }

    override fun scheduler(): Scheduler = scheduler

    @Test
    fun testNewTasksKeepTheLegacyExecutionTimeoutColumnForOlderNodes() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val request =
            ScheduleRequest.builder<String>()
                .id("legacy-timeout")
                .payload("payload")
                .type(Task.Type.WORKFLOW)
                .dedupToken("legacy-timeout")
                .build()
        scheduler.schedule(request)
        assertEquals(Scheduler.LEGACY_EXECUTION_TIMEOUT_SECS, storedExecutionTimeoutSecs("legacy-timeout"))
        // Overwriting the task leaves the column as it was.
        scheduler.schedule(request.toBuilder().payload("payload2").build())
        assertEquals(Scheduler.LEGACY_EXECUTION_TIMEOUT_SECS, storedExecutionTimeoutSecs("legacy-timeout"))
    }

    private fun storedExecutionTimeoutSecs(taskId: String): Long =
        SqliteTestSetupExtension.DB_DATA_SOURCE!!.connection.use { conn ->
            conn.prepareStatement("SELECT execution_timeout_secs FROM ${tablePrefix}scheduler_tasks WHERE task_id = ?").use { ps ->
                ps.setString(1, taskId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
}
