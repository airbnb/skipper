package com.airbnb.skipper.internal.cluster.jdbc

import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.cluster.BaseClusterMembershipManagerTest
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import javax.sql.DataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(SqliteTestSetupExtension::class)
class SqliteClusterMembershipManagerTest : BaseClusterMembershipManagerTest() {
    private lateinit var clusterManager: JdbcClusterMembershipManager

    @BeforeEach
    fun setUpClusterManager() {
        clusterManager = newClusterManager(defaultMemberName) as JdbcClusterMembershipManager
    }

    override fun clusterManager(): ClusterMembershipManager = clusterManager

    override fun newClusterManager(memberName: String): ClusterMembershipManager =
        JdbcClusterMembershipManager(
            JdbcTransactionManager(dataSource()),
            owner,
            owner,
            NoOpMetrics.INSTANCE,
            mockClock,
            heartbeatInterval,
            memberName,
            SkipperConfig.DEFAULT_TABLE_PREFIX,
        )

    override fun dataSource(): DataSource = SqliteTestSetupExtension.DB_DATA_SOURCE!!
}
