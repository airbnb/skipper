package com.airbnb.skipper.internal.cluster

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.cluster.jdbc.JdbcClusterMembershipManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/**
 * Base test suite for all ClusterMembershipManager implementations.
 *
 * All ClusterMembershipManager implementations must adhere to the specification defined by these
 * tests. Tests that are specific to every implementation should be defined in the concrete
 * implementation test class.
 *
 * This is a port of the internal `BaseClusterMembershipManagerTest` that specifies Airbnb's UDS
 * backed manager: same tests, same assertions. Where the original reads the member record back out
 * of UDS, this reads it out of the `<prefix>cluster_members` table via [readMember]. The two
 * cross-instance tests at the end are additions: they pin the property the scheduler relies on
 * (every member derives the same ordered member list, hence disjoint task partitions).
 */
abstract class BaseClusterMembershipManagerTest {
    @JvmField
    protected val owner = "test-owner"

    @JvmField
    protected val mockClock: Clock = mock(Clock::class.java)

    @JvmField
    protected val heartbeatInterval: Duration = Duration.ofMillis(100)

    @JvmField
    protected val defaultMemberName = "member-1"

    /** The manager under test; one instance per test, constructed by the concrete class. */
    protected abstract fun clusterManager(): ClusterMembershipManager

    /**
     * A second, independent manager over the same store registered under [memberName], standing in
     * for another Skipper instance. Started managers handed out here are stopped by [tearDown].
     */
    protected abstract fun newClusterManager(memberName: String): ClusterMembershipManager

    /** The DataSource the managers under test write to, for reading rows back. */
    protected abstract fun dataSource(): DataSource

    private val extraManagers = ArrayList<ClusterMembershipManager>()

    /** The persisted state of one cluster member, as read back from the `cluster_members` table. */
    protected data class MemberRow(
        val owner: String,
        val clusterName: String,
        val memberId: String,
        val lastHeartbeatAt: Instant,
    )

    @BeforeEach
    fun setUp() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
    }

    @AfterEach
    fun tearDown() {
        // stop() waits for the heartbeat loop to exit, so no in-flight beat can land after the next
        // test's table reset.
        if (clusterManager().isRegistered) {
            clusterManager().stop()
        }
        extraManagers.forEach { it.stop() }
        extraManagers.forEach { m -> assertFalse(m.isRegistered, "manager still registered after stop()") }
        extraManagers.clear()
    }

    @Test
    fun testRegisterMember() {
        val memberId = "member-1"
        clusterManager().registerMember(memberId)

        clusterManager().start()
        assertTrue(clusterManager().isRegistered)
        assertEquals(memberId, clusterManager().currentMemberId)
    }

    @Test
    fun testRegisterMultipleMembers() {
        clusterManager().registerMember("member-1")
        clusterManager().registerMember("member-2")

        clusterManager().start()
        assertTrue(clusterManager().isRegistered)
        // getCurrentMemberId should return the first registered member
        val currentMemberId = clusterManager().currentMemberId
        assertTrue(currentMemberId == "member-1" || currentMemberId == "member-2")
    }

    @Test
    fun testUnregisterMember() {
        clusterManager().start()
        val memberId = "member-1"
        clusterManager().registerMember(memberId)
        clusterManager().registerMember("member-2")

        clusterManager().unregisterMember(memberId)

        assertTrue(clusterManager().isRegistered)
        assertEquals("member-2", clusterManager().currentMemberId)
    }

    @Test
    fun testGetCurrentMemberIdWhenNoMembers() {
        assertThrows(IllegalStateException::class.java) { clusterManager().currentMemberId }
    }

    @Test
    fun testIsRegisteredWhenNotStarted() {
        clusterManager().registerMember("member-1")
        assertFalse(clusterManager().isRegistered)
    }

    @Test
    fun testStartRegistersMemberByDefault() {
        clusterManager().start()
        assertTrue(clusterManager().isRegistered)
    }

    @Test
    fun testIsRegisteredWhenStartedWithMembers() {
        clusterManager().registerMember("member-1")
        clusterManager().start()
        assertTrue(clusterManager().isRegistered)
    }

    @Test
    fun testStartWhenAlreadyRunning() {
        clusterManager().registerMember("member-1")
        clusterManager().start()
        assertTrue(clusterManager().isRegistered)

        // Starting again should not throw an error
        clusterManager().start()
        assertTrue(clusterManager().isRegistered)
    }

    @Test
    fun testGetActiveMemberIdsWithNoMembers() {
        val activeMembers = clusterManager().activeMemberIds
        assertTrue(activeMembers.isEmpty)
    }

    @Test
    fun testGetActiveMemberIdsAfterHeartbeat() {
        val currentTime = Instant.EPOCH.plusSeconds(10)
        whenever(mockClock.instant()).thenReturn(currentTime)

        val memberId = "member-1"
        clusterManager().registerMember(memberId)
        clusterManager().start()

        // Wait for the heartbeat thread to create the member record with polling
        awaitCondition { clusterManager().activeMemberIds.size() == 1 }

        val activeMembers = clusterManager().activeMemberIds
        assertEquals(1, activeMembers.size())
        assertEquals(memberId, activeMembers.get(0))
    }

    @Test
    fun testGetActiveMemberIdsWithExpiredMembers() {
        // A member whose last heartbeat is older than the liveness threshold (as left behind by a
        // crashed instance) is not returned; one inside the window is. Rows are written directly so the
        // stale one cannot be refreshed or removed by a running manager.
        val now = Instant.EPOCH.plus(LIVENESS_THRESHOLD).plusSeconds(30)
        whenever(mockClock.instant()).thenReturn(now)
        insertMemberRow(owner, "expired-member", Instant.EPOCH)
        insertMemberRow(owner, "live-member", now.minus(LIVENESS_THRESHOLD).plusSeconds(1))

        val activeMembers = clusterManager().activeMemberIds
        assertEquals(listOf("live-member"), activeMembers.toJavaList(), "Expired members should not be returned")
    }

    @Test
    fun testHeartbeatCreatesNewMember() {
        val currentTime = Instant.EPOCH.plusSeconds(5)
        whenever(mockClock.instant()).thenReturn(currentTime)

        val memberId = "new-member"
        clusterManager().registerMember(memberId)
        clusterManager().start()

        // Wait for the heartbeat thread to create the member record with polling
        awaitCondition {
            val m = readMember(memberId)
            m != null && memberId == m.memberId
        }

        // Verify the member was created in the store
        val member = readMember(memberId) ?: fail("member row not found")

        assertEquals(owner, member.owner)
        assertEquals(owner, member.clusterName)
        assertEquals(memberId, member.memberId)
        assertEquals(currentTime, member.lastHeartbeatAt)
    }

    @Test
    fun testHeartbeatUpdatesExistingMember() {
        val initialTime = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(initialTime)

        val memberId = "existing-member"
        clusterManager().registerMember(memberId)
        clusterManager().start()

        // Wait for the heartbeat thread to create the initial record with polling
        awaitCondition {
            val m = readMember(memberId)
            m != null && initialTime == m.lastHeartbeatAt
        }

        // Advance the clock and verify the timestamp gets updated
        val updatedTime = initialTime.plusSeconds(30)
        whenever(mockClock.instant()).thenReturn(updatedTime)

        // Wait for the heartbeat thread to update with polling
        awaitCondition {
            val m = readMember(memberId)
            m != null && updatedTime == m.lastHeartbeatAt
        }

        val member = readMember(memberId) ?: fail("member row not found")

        assertEquals(updatedTime, member.lastHeartbeatAt)
    }

    @Test
    fun testHeartbeatWithMultipleMembers() {
        val currentTime = Instant.EPOCH.plusSeconds(15)
        whenever(mockClock.instant()).thenReturn(currentTime)

        clusterManager().registerMember("member-1")
        clusterManager().registerMember("member-2")
        clusterManager().start()

        // Wait for the heartbeat thread to process both members with polling
        awaitCondition {
            val m1 = readMember("member-1")
            val m2 = readMember("member-2")
            m1 != null &&
                "member-1" == m1.memberId &&
                m2 != null &&
                "member-2" == m2.memberId
        }

        // Verify both members were created
        val member1 = readMember("member-1") ?: fail("member-1 row not found")
        val member2 = readMember("member-2") ?: fail("member-2 row not found")

        assertEquals("member-1", member1.memberId)
        assertEquals("member-2", member2.memberId)
        assertEquals(currentTime, member1.lastHeartbeatAt)
        assertEquals(currentTime, member2.lastHeartbeatAt)
    }

    // ========== LIFE-CYCLE TESTS ==========

    @Test
    fun testStartMakesMemberVisibleImmediately() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))

        clusterManager().start()

        // No polling: the first heartbeat runs before start() returns.
        assertTrue(clusterManager().isRegistered)
        assertEquals(listOf(defaultMemberName), clusterManager().activeMemberIds.toJavaList())
        assertTrue(readMember(defaultMemberName) != null)
    }

    @Test
    fun testStopRemovesMemberRowAndUnregisters() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))
        val other = startExtraManager("member-2")
        clusterManager().start()
        awaitCondition { other.activeMemberIds.size() == 2 }

        clusterManager().stop()

        assertFalse(clusterManager().isRegistered)
        assertTrue(readMember(defaultMemberName) == null, "stop() should remove this member's row")
        other.refreshMembershipCache()
        assertEquals(listOf("member-2"), other.activeMemberIds.toJavaList())
    }

    @Test
    fun testUnregisterMemberRemovesItsRow() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))
        clusterManager().registerMember("member-2")
        clusterManager().start()
        awaitCondition { readMember("member-2") != null }

        clusterManager().unregisterMember("member-2")

        assertTrue(readMember("member-2") == null)
        assertTrue(readMember(defaultMemberName) != null)
    }

    @Test
    fun testRestartAfterStop() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))
        clusterManager().start()
        clusterManager().stop()
        assertFalse(clusterManager().isRegistered)

        clusterManager().start()

        assertTrue(clusterManager().isRegistered)
        assertEquals(listOf(defaultMemberName), clusterManager().activeMemberIds.toJavaList())
        // And the loop is alive again: a clock advance is reflected by a later beat.
        val later = Instant.EPOCH.plusSeconds(40)
        whenever(mockClock.instant()).thenReturn(later)
        awaitCondition { readMember(defaultMemberName)?.lastHeartbeatAt == later }
    }

    // ========== CROSS-INSTANCE TESTS ==========

    @Test
    fun testActiveMembersAreSharedAcrossInstancesAndOrdered() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))

        // Register in reverse lexical order to prove the ordering comes from the store, not from
        // registration order.
        val other = startExtraManager("member-b")
        clusterManager().registerMember("member-a")
        clusterManager().start()

        // Each manager serves a list cached for one heartbeat interval, so wait for every view.
        val expected = listOf("member-1", "member-a", "member-b")
        awaitCondition {
            clusterManager().activeMemberIds.toJavaList() == expected && other.activeMemberIds.toJavaList() == expected
        }
    }

    @Test
    fun testMembersDerivePartitionsThatTileTheBucketSpace() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))

        clusterManager().start()
        val second = startExtraManager("member-2")
        val third = startExtraManager("member-3")

        val managers = listOf(clusterManager(), second, third)
        awaitCondition { managers.all { it.activeMemberIds.size() == 3 } }

        val partitioner = BucketPartitioner()
        val ranges =
            managers.map { m ->
                partitioner.getBucketRangeForMember(m.currentMemberId, m.activeMemberIds)
            }
        val covered = BooleanArray(BucketPartitioner.TOTAL_BUCKETS)
        for (range in ranges) {
            for (bucket in range.startInclusive until range.endExclusive) {
                assertFalse(covered[bucket], "bucket $bucket is owned by two members: $ranges")
                covered[bucket] = true
            }
        }
        assertTrue(covered.all { it }, "every bucket must be owned by exactly one member: $ranges")
    }

    @Test
    fun testOtherTenantsMembersAreInvisible() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(20))

        clusterManager().start()
        awaitCondition { clusterManager().activeMemberIds.size() == 1 }

        // A row for a different owner/cluster never shows up in this cluster's member list.
        insertMemberRow("other-owner", "member-1", mockClock.instant())

        clusterManager().refreshMembershipCache()
        assertEquals(listOf("member-1"), clusterManager().activeMemberIds.toJavaList())
    }

    private fun startExtraManager(memberName: String): ClusterMembershipManager {
        val manager = newClusterManager(memberName)
        extraManagers.add(manager)
        manager.start()
        return manager
    }

    /** Writes a member row directly, bypassing any manager, with `owner` doubling as the cluster name. */
    protected fun insertMemberRow(
        rowOwner: String,
        memberId: String,
        heartbeatAt: Instant,
    ) {
        dataSource().connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO ${SkipperConfig.DEFAULT_TABLE_PREFIX}cluster_members " +
                    "(owner, cluster_name, member_id, last_heartbeat_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                val ts = Timestamp.from(heartbeatAt)
                ps.setString(1, rowOwner)
                ps.setString(2, rowOwner)
                ps.setString(3, memberId)
                ps.setTimestamp(4, ts)
                ps.setTimestamp(5, ts)
                ps.setTimestamp(6, ts)
                ps.executeUpdate()
            }
        }
    }

    /** Reads the persisted row for [memberId] in this test's cluster, or `null` if there is none. */
    protected fun readMember(memberId: String): MemberRow? {
        dataSource().connection.use { conn ->
            conn.prepareStatement(
                "SELECT owner, cluster_name, member_id, last_heartbeat_at " +
                    "FROM ${SkipperConfig.DEFAULT_TABLE_PREFIX}cluster_members " +
                    "WHERE owner = ? AND cluster_name = ? AND member_id = ?",
            ).use { ps ->
                ps.setString(1, owner)
                ps.setString(2, owner)
                ps.setString(3, memberId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return MemberRow(
                        owner = rs.getString("owner"),
                        clusterName = rs.getString("cluster_name"),
                        memberId = rs.getString("member_id"),
                        lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at").toInstant(),
                    )
                }
            }
        }
    }

    /** Helper method to wait for a condition with polling and timeout. */
    private fun awaitCondition(condition: Supplier<Boolean>) {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos()
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met within $AWAIT_TIMEOUT")
            }
            sleep(50)
        }
    }

    private fun sleep(millis: Long) {
        LockSupport.parkNanos(millis * 1_000_000)
    }

    companion object {
        private val LIVENESS_THRESHOLD: Duration = JdbcClusterMembershipManager.LIVENESS_THRESHOLD
        private val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
