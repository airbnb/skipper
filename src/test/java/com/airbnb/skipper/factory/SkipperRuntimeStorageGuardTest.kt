package com.airbnb.skipper.factory

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.cluster.jdbc.JdbcClusterMembershipManager
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
    fun membershipManagerOnOtherBackendIsRejected() {
        val config = SkipperConfig.forService("guard")
        config.clusterMembershipManager = JdbcClusterMembershipManager.MySqlFactory()
        val error = assertThrows<IllegalStateException> { SkipperRuntime(config) }
        assertTrue(error.message!!.contains("clusterMembershipManager"), error.message)
    }

    @Test
    fun sqliteFactoriesOnDifferentDatabasesAreRejected() {
        // Same dialect, different file: the membership manager would register in a database no other
        // component reads.
        val membership = SkipperConfig.forService("guard")
        membership.workflowStore = SqliteWorkflowStore.Factory("skipper.db")
        membership.scheduler = SqliteScheduler.Factory("skipper.db")
        membership.clusterMembershipManager = JdbcClusterMembershipManager.SqliteFactory()
        val error = assertThrows<IllegalStateException> { SkipperRuntime(membership) }
        assertTrue(error.message!!.contains("different SQLite databases"), error.message)

        val storeVsScheduler = SkipperConfig.forService("guard")
        storeVsScheduler.workflowStore = SqliteWorkflowStore.Factory("a.db")
        storeVsScheduler.scheduler = SqliteScheduler.Factory("b.db")
        assertThrows<IllegalStateException> { SkipperRuntime(storeVsScheduler) }
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
        file.clusterMembershipManager = JdbcClusterMembershipManager.SqliteFactory("ignored.db")
        assertDoesNotThrow { SkipperRuntime(file) }
        val mysqlCluster = SkipperConfig.forService("guard")
        mysqlCluster.workflowStore = MySqlWorkflowStore.Factory()
        mysqlCluster.scheduler = MySqlScheduler.Factory()
        mysqlCluster.clusterMembershipManager = JdbcClusterMembershipManager.MySqlFactory()
        mysqlCluster.mySqlDataSource = mock<DataSource>()
        assertDoesNotThrow { SkipperRuntime(mysqlCluster) }
    }
}
