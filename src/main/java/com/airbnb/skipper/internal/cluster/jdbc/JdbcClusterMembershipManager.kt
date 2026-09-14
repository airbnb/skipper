package com.airbnb.skipper.internal.cluster.jdbc

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.SkipperAnnotationNames.CLUSTER_MEMBERSHIP_HEARTBEAT_INTERVAL
import com.airbnb.skipper.SkipperAnnotationNames.CLUSTER_MEMBER_NAME
import com.airbnb.skipper.SkipperAnnotationNames.TABLE_PREFIX
import com.airbnb.skipper.SkipperAnnotationNames.TENANT
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.InternalError
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.vavr.collection.List
import java.net.InetAddress
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

/**
 * A JDBC backed [ClusterMembershipManager] shared by the MySQL and SQLite backends.
 *
 * Each running Skipper instance registers itself as a *member* of its tenant's cluster and, once
 * [start]ed, a background thread heartbeats a row in the `<prefix>cluster_members` table every
 * [heartbeatInterval]. [getActiveMemberIds] returns the members whose heartbeat is within
 * [LIVENESS_THRESHOLD], sorted by id, so every instance derives the same ordered member list and
 * [com.airbnb.skipper.internal.cluster.BucketPartitioner] hands each of them a disjoint bucket
 * range. That is what lets `SkipperSchedulerManager` fetch a partition of the ready tasks instead
 * of the whole queue, removing lease contention between instances.
 *
 * This mirrors Airbnb's internal `UdsClusterMembershipManager` method-for-method: the same
 * in-memory registered-member set, the same start/stop life-cycle (`isRegistered` is true only
 * while the heartbeat loop is running *and* at least one member is registered), the same 60 s
 * liveness threshold, and the same heartbeat semantics (update the member's row, or create it on
 * the first beat). A member that stops is not deleted; its row simply ages out of the liveness
 * window. Only the persistence layer differs: plain JDBC over [JdbcTransactionManager], with SQL
 * that is identical for both dialects.
 *
 * The heartbeat thread is a daemon and is interrupted by [stop], so a host that forgets to stop
 * Skipper can still exit and a stopped manager stops promptly rather than after one more sleep.
 *
 * INVARIANT: `owner` and `clusterName` are both bound to [TENANT], i.e. they hold the same string
 * at runtime. A Skipper tenant runs exactly one cluster, and the cluster's identity is its tenant.
 * They exist as separate concepts so that, if multiple clusters per tenant are ever needed, only
 * the `clusterName` binding has to change.
 */
class JdbcClusterMembershipManager
    @Inject
    constructor(
        private val transactionManager: JdbcTransactionManager,
        @param:Named(TENANT) private val owner: String,
        @param:Named(TENANT) private val clusterName: String,
        private val metrics: Metrics,
        @param:Named(UTC_CLOCK) private val clock: Clock,
        @param:Named(CLUSTER_MEMBERSHIP_HEARTBEAT_INTERVAL) private val heartbeatInterval: Duration,
        @param:Named(CLUSTER_MEMBER_NAME) private val clusterMemberName: String,
        @param:Named(TABLE_PREFIX) tablePrefix: String,
    ) : ClusterMembershipManager {
        // Derived from the configured prefix (the single source of truth shared with the Flyway DDL).
        private val clusterMembersTable: String = tablePrefix + "cluster_members"

        private val registeredMemberIds: MutableMap<String, Boolean> = ConcurrentHashMap()
        private val running = AtomicBoolean(false)
        private val shouldStop = AtomicBoolean(false)

        /** Runs the heartbeat loop; created by [start] and torn down by [stop]. */
        @Volatile
        private var executor: ExecutorService? = null

        override fun getClusterName(): String = clusterName

        override fun getActiveMemberIds(): List<String> = transactionManager.retryOnTransientLockContention { readActiveMemberIds() }

        private fun readActiveMemberIds(): List<String> {
            val sql =
                "SELECT member_id FROM $clusterMembersTable " +
                    "WHERE owner = ? AND cluster_name = ? AND last_heartbeat_at >= ? " +
                    "ORDER BY member_id ASC LIMIT ?"
            metrics.timer(METRICS_COMPONENT, "getActiveMembers").time().use { _ ->
                val livenessLimit = clock.instant().minus(LIVENESS_THRESHOLD)
                try {
                    transactionManager.getConnection().use { conn ->
                        conn.prepareStatement(sql).use { ps ->
                            var i = 0
                            ps.setString(++i, owner)
                            ps.setString(++i, clusterName)
                            ps.setTimestamp(++i, Timestamp.from(livenessLimit))
                            ps.setInt(++i, MAX_MEMBERS)
                            ps.executeQuery().use { rs ->
                                val members = ArrayList<String>()
                                while (rs.next()) {
                                    members.add(rs.getString("member_id"))
                                }
                                return List.ofAll(members)
                            }
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError("unable to read active cluster members", e)
                }
            }
        }

        override fun registerMember(memberId: String) {
            registeredMemberIds[memberId] = true
        }

        override fun unregisterMember(memberId: String) {
            registeredMemberIds.remove(memberId)
        }

        override fun getCurrentMemberId(): String = registeredMemberIds.keys.firstOrNull() ?: throw IllegalStateException("No registered member ID")

        override fun isRegistered(): Boolean = running.get() && registeredMemberIds.isNotEmpty()

        @Synchronized
        override fun start() {
            if (running.get()) {
                log.warn("JdbcClusterMembershipManager is already running, cannot start again")
                return
            }
            registerMember(clusterMemberName)
            log.info("Starting JdbcClusterMembershipManager for owner:{} and clusterName:{}", owner, clusterName)
            shouldStop.set(false)
            val loop =
                Executors.newSingleThreadExecutor(
                    ThreadFactoryBuilder().setNameFormat("skipper-jdbcCluster-%d").setDaemon(true).build(),
                )
            executor = loop
            loop.submit {
                while (!shouldStop.get()) {
                    try {
                        heartbeat()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception
                    ) {
                        log.error("Error during heartbeat", e)
                    }
                    try {
                        Thread.sleep(heartbeatInterval.toMillis())
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                running.set(false)
                log.info(
                    "JdbcClusterMembershipManager for owner:{} and clusterName:{} has stopped",
                    owner,
                    clusterName,
                )
            }
            running.set(true)
            log.info("JdbcClusterMembershipManager for owner:{} and clusterName:{} has started", owner, clusterName)
        }

        /**
         * Refreshes `last_heartbeat_at` for every registered member, inserting the member's row on its
         * first beat. Each member is handled independently so one failure cannot starve the others.
         */
        private fun heartbeat() {
            metrics.timer(METRICS_COMPONENT, "heartbeat").time().use { _ ->
                val now = clock.instant()
                for (memberId in registeredMemberIds.keys) {
                    try {
                        transactionManager.retryOnTransientLockContention {
                            transactionManager.getConnection().use { conn -> beat(conn, memberId, now) }
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception
                    ) {
                        log.error(
                            "Failed to heartbeat for member for owner:{}, clusterName:{}, memberId:{}",
                            owner,
                            clusterName,
                            memberId,
                            e,
                        )
                    }
                }
            }
        }

        /** One heartbeat for one member: refresh its row, or create it on the first beat. */
        @Throws(SQLException::class)
        private fun beat(
            conn: Connection,
            memberId: String,
            now: Instant,
        ) {
            if (updateHeartbeat(conn, memberId, now)) {
                log.debug(
                    "Updated heartbeat for existing member for owner:{}, clusterName:{}, memberId:{}",
                    owner,
                    clusterName,
                    memberId,
                )
            } else {
                log.info(
                    "Creating new member for owner:{}, clusterName:{}, memberId:{}",
                    owner,
                    clusterName,
                    memberId,
                )
                insertMember(conn, memberId, now)
            }
        }

        /** Returns `true` if the member already had a row (which is now refreshed). */
        @Throws(SQLException::class)
        private fun updateHeartbeat(
            conn: Connection,
            memberId: String,
            now: Instant,
        ): Boolean {
            val sql =
                "UPDATE $clusterMembersTable SET last_heartbeat_at = ?, updated_at = ? " +
                    "WHERE owner = ? AND cluster_name = ? AND member_id = ?"
            conn.prepareStatement(sql).use { ps ->
                var i = 0
                ps.setTimestamp(++i, Timestamp.from(now))
                ps.setTimestamp(++i, Timestamp.from(now))
                ps.setString(++i, owner)
                ps.setString(++i, clusterName)
                ps.setString(++i, memberId)
                return ps.executeUpdate() == 1
            }
        }

        @Throws(SQLException::class)
        private fun insertMember(
            conn: Connection,
            memberId: String,
            now: Instant,
        ) {
            val sql =
                "INSERT INTO $clusterMembersTable " +
                    "(owner, cluster_name, member_id, last_heartbeat_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            conn.prepareStatement(sql).use { ps ->
                var i = 0
                ps.setString(++i, owner)
                ps.setString(++i, clusterName)
                ps.setString(++i, memberId)
                ps.setTimestamp(++i, Timestamp.from(now))
                ps.setTimestamp(++i, Timestamp.from(now))
                ps.setTimestamp(++i, Timestamp.from(now))
                ps.executeUpdate()
            }
        }

        @Synchronized
        override fun stop() {
            shouldStop.set(true)
            // Interrupt the sleeping heartbeat loop so it observes shouldStop now, not after one more
            // heartbeatInterval; the loop itself flips `running` to false on the way out.
            executor?.shutdownNow()
            executor = null
        }

        /**
         * Builds a [JdbcClusterMembershipManager] over the MySQL [javax.sql.DataSource] on
         * [SkipperConfig.mySqlDataSource]. Pair it with `MySqlWorkflowStore` / `MySqlScheduler`.
         */
        class MySqlFactory : ComponentFactory<ClusterMembershipManager> {
            override fun create(config: SkipperConfig): ClusterMembershipManager = build(JdbcTransactionManager.MySqlFactory().create(config), config)
        }

        /**
         * Builds a [JdbcClusterMembershipManager] over the SQLite database Skipper uses, resolved the
         * same way `SqliteScheduler.Factory` resolves it: the given file [path] when set, otherwise
         * [SkipperConfig.sqliteDataSource], otherwise the ephemeral in-memory database. Pair it with the
         * SQLite store and scheduler built from the same path / DataSource.
         */
        class SqliteFactory
            @JvmOverloads
            constructor(
                private val path: String? = null,
            ) : ComponentFactory<ClusterMembershipManager> {
                override fun create(config: SkipperConfig): ClusterMembershipManager {
                    val dataSource = path?.let { JdbcTransactionManager.SqliteFactory.fileDataSource(it) }
                    return build(JdbcTransactionManager.SqliteFactory(dataSource).create(config), config)
                }
            }

        companion object {
            private val log = LoggerFactory.getLogger(JdbcClusterMembershipManager::class.java)

            private const val METRICS_COMPONENT = "jdbcClusterMembershipManager"

            /** A member whose last heartbeat is older than this is considered gone. */
            @JvmField
            val LIVENESS_THRESHOLD: Duration = Duration.ofSeconds(60)

            /** Upper bound on the members returned by [getActiveMemberIds]. */
            private const val MAX_MEMBERS = 1000

            private fun build(
                transactionManager: JdbcTransactionManager,
                config: SkipperConfig,
            ): JdbcClusterMembershipManager =
                JdbcClusterMembershipManager(
                    transactionManager,
                    config.tenant,
                    config.tenant,
                    config.metrics.create(config),
                    config.utcClock,
                    config.clusterHeartBeatInterval,
                    config.clusterMemberName ?: localHostName(),
                    config.tablePrefix,
                )

            private fun localHostName(): String =
                try {
                    InetAddress.getLocalHost().hostName
                } catch (e: UnknownHostException) {
                    throw IllegalStateException(
                        "Unable to get hostname, can't initiate JdbcClusterMembershipManager; " +
                            "set SkipperConfig.clusterMemberName explicitly",
                        e,
                    )
                }
        }
    }
