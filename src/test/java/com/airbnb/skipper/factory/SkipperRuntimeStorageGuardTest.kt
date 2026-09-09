package com.airbnb.skipper.factory

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

/** The default store moved from MySQL to embedded SQLite; configs written for the old default must fail loudly. */
class SkipperRuntimeStorageGuardTest {
    @Test
    fun mySqlDataSourceWithDefaultSqliteFactoriesIsRejected() {
        val config = SkipperConfig.forService("guard")
        config.mySqlDataSource = mock<DataSource>()
        val error = assertThrows<IllegalStateException> { SkipperRuntime(config) }
        assertTrue(error.message!!.contains("MySqlWorkflowStore.Factory()"), error.message)
    }

    @Test
    fun sqliteDataSourceWithMySqlFactoriesIsRejected() {
        val config = SkipperConfig.forService("guard")
        config.workflowStore = MySqlWorkflowStore.Factory()
        config.scheduler = MySqlScheduler.Factory()
        config.mySqlDataSource = mock<DataSource>()
        config.sqliteDataSource = mock<DataSource>()
        val error = assertThrows<IllegalStateException> { SkipperRuntime(config) }
        assertTrue(error.message!!.contains("sqliteDataSource"), error.message)
    }

    @Test
    fun mixedBackendsAreRejected() {
        val config = SkipperConfig.forService("guard")
        config.workflowStore = MySqlWorkflowStore.Factory()
        config.scheduler = SqliteScheduler.Factory()
        config.mySqlDataSource = mock<DataSource>()
        assertThrows<IllegalStateException> { SkipperRuntime(config) }
    }

    @Test
    fun consistentConfigsAreAccepted() {
        assertDoesNotThrow { SkipperRuntime(SkipperConfig.forService("guard")) }
        val mysql = SkipperConfig.forService("guard")
        mysql.workflowStore = MySqlWorkflowStore.Factory()
        mysql.scheduler = MySqlScheduler.Factory()
        mysql.mySqlDataSource = mock<DataSource>()
        assertDoesNotThrow { SkipperRuntime(mysql) } // construction is lazy; nothing touches the DataSource
        val file = SkipperConfig.forService("guard")
        file.workflowStore = SqliteWorkflowStore.Factory("ignored.db")
        file.scheduler = SqliteScheduler.Factory("ignored.db")
        assertDoesNotThrow { SkipperRuntime(file) }
    }
}
