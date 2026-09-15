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
import java.util.concurrent.TimeUnit
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
 * This mirrors Airbnb's internal `UdsClusterMembershipManager`: the same in-memory registered-member
 * set, `isRegistered` true only while the heartbeat loop is running *and* at least one member is
 * registered, the same 60 s liveness threshold, and the same heartbeat semantics (update the
 * member's row, or create it on the first beat). Only the persistence layer differs: plain JDBC
 * over [JdbcTransactionManager], with SQL that is identical for both dialects.
 *
 * Deliberate departures from the UDS life-cycle, all in the direction of what the
 * [ClusterMembershipManager] contract already promises:
 * - [start] performs the first heartbeat synchronously, so the moment [isRegistered] turns true
 *   this member is already in every instance's [getActiveMemberIds] (UDS has a window until the
 *   first asynchronous beat lands, during which the partitioner cannot find the member).
 * - [stop] and [unregisterMember] delete the member's row, handing its bucket range to the
 *   survivors immediately instead of after the liveness window (UDS lets the row age out). A
 *   crashed member still ages out; this only affects graceful shutdown.
 * - [stop] waits for the heartbeat loop to exit, so [isRegistered] is false when it returns, and a
 *   stopped manager can be [start]ed again. The loop thread is a daemon.
 * - [getActiveMemberIds] serves a list cached for one [heartbeatInterval] (refreshed by every beat,
 *   or on demand via [refreshMembershipCache]) rather than querying on every call, because the
 *   scheduler manager calls it before every fetch and from its metrics gauges. Liveness is judged
 *   on a 60 s window, so a list at most one interval old loses nothing.
 * - Routine heartbeat refreshes are logged at debug rather than info.
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

        /** The last member list read from the store, with the wall-clock time it was read at. */
        @Volatile
        private var cachedMembers: CachedMembers? = null

        private class CachedMembers(val members: List<String>, val readAtNanos: Long)

        override fun getClusterName(): String = clusterName

        override fun getActiveMemberIds(): List<String> {
            val cached = cachedMembers
            if (cached != null && System.nanoTime() - cached.readAtNanos < heartbeatInterval.toNanos()) {
                return cached.members
            }
            return refreshAndGetActiveMemberIds()
        }

        override fun refreshMembershipCache() {
            refreshAndGetActiveMemberIds()
        }

        private fun refreshAndGetActiveMemberIds(): List<String> {
            val members = transactionManager.retryOnTransientLockContention { readActiveMemberIds() }
            cachedMembers = CachedMembers(members, System.nanoTime())
            return members
        }

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
            deleteMemberRows(listOf(memberId))
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
            // First beat runs on the caller's thread so this member is visible to the cluster (and to
            // its own partition computation) by the time isRegistered() reports true.
            heartbeatQuietly()
            val loop =
                Executors.newSingleThreadExecutor(
                    ThreadFactoryBuilder().setNameFormat("skipper-jdbcCluster-%d").setDaemon(true).build(),
                )
            executor = loop
            loop.submit {
                while (!shouldStop.get()) {
                    try {
                        Thread.sleep(heartbeatInterval.toMillis())
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (shouldStop.get()) {
                        break
                    }
                    heartbeatQuietly()
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

        private fun heartbeatQuietly() {
            try {
                heartbeat()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                log.error("Error during heartbeat", e)
            }
        }

        /**
         * Refreshes `last_heartbeat_at` for every registered member, inserting the member's row on its
         * first beat, then refreshes the cached member list. Each member is handled independently so
         * one failure cannot starve the others.
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
            refreshAndGetActiveMemberIds()
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
                log.info("Creating new member for owner:{}, clusterName:{}, memberId:{}", owner, clusterName, memberId)
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

        /**
         * Best-effort removal of the given members' rows so the survivors redistribute their buckets
         * at once. A failure is logged, not thrown: the rows age out of the liveness window anyway.
         */
        private fun deleteMemberRows(memberIds: Collection<String>) {
            if (memberIds.isEmpty()) {
                return
            }
            val sql = "DELETE FROM $clusterMembersTable WHERE owner = ? AND cluster_name = ? AND member_id = ?"
            for (memberId in memberIds) {
                try {
                    transactionManager.retryOnTransientLockContention {
                        transactionManager.getConnection().use { conn ->
                            conn.prepareStatement(sql).use { ps ->
                                ps.setString(1, owner)
                                ps.setString(2, clusterName)
                                ps.setString(3, memberId)
                                ps.executeUpdate()
                            }
                        }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    log.warn(
                        "Failed to remove cluster member row for owner:{}, clusterName:{}, memberId:{}; it will age out",
                        owner,
                        clusterName,
                        memberId,
                        e,
                    )
                }
            }
            cachedMembers = null
        }

        @Synchronized
        override fun stop() {
            shouldStop.set(true)
            val loop = executor
            executor = null
            if (loop != null) {
                // Interrupt the sleeping loop and wait for it to exit so no beat can land after we return.
                loop.shutdownNow()
                try {
                    if (!loop.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        log.warn("Heartbeat loop did not exit within {}", STOP_TIMEOUT)
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            running.set(false)
            deleteMemberRows(registeredMemberIds.keys.toList())
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
         * [SkipperConfig.sqliteDataSource], otherwise an ephemeral in-memory database. Pair it with the
         * SQLite store and scheduler built from the same path / DataSource (`SkipperRuntime` rejects a
         * mismatch). Note that each in-memory default is a distinct database, so with neither a path nor
         * a DataSource the manager only ever sees itself — which is also all a single-process in-memory
         * deployment could ever be.
         */
        class SqliteFactory
            @JvmOverloads
            constructor(
                /** The on-disk database this factory targets, or `null` for [SkipperConfig.sqliteDataSource]. */
                val path: String? = null,
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

            /** How long [stop] waits for the heartbeat loop to exit. */
            private val STOP_TIMEOUT: Duration = Duration.ofSeconds(5)

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
