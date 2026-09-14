package com.airbnb.skipper.admin

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.testutils.TestRuntime
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.vavr.collection.List
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

/** The `GET /skipper/admin/cluster` view: live members and the bucket range each one owns. */
class AdminResourceClusterTest {
    /** A membership manager with a fixed view of the cluster, standing in for any backend. */
    private class FixedMembership(
        private val members: List<String>,
        private val current: String?,
    ) : ClusterMembershipManager {
        override fun getClusterName(): String = "test-cluster"

        override fun getActiveMemberIds(): List<String> = members

        override fun registerMember(memberId: String) {}

        override fun unregisterMember(memberId: String) {}

        override fun getCurrentMemberId(): String = current ?: throw IllegalStateException("No registered member ID")

        override fun isRegistered(): Boolean = current != null

        override fun start() {}

        override fun stop() {}
    }

    private fun cluster(
        membership: ClusterMembershipManager,
        partitioningEnabled: Boolean = true,
    ): JsonNode {
        val deps = TestRuntime()
        deps.config.clusterMembershipManager = ComponentFactory { membership }
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.TASK_PARTITIONING)).thenReturn(partitioningEnabled)
        val response = deps.runtime.adminResource.get().getCluster()
        assertEquals(200, response.status)
        return ObjectMapper().readTree(response.entity as String)
    }

    @Test
    fun listsLiveMembersWithTheirBucketRanges() {
        val view = cluster(FixedMembership(List.of("member-a", "member-b", "member-c"), "member-b"))

        assertEquals("test-cluster", view["cluster_name"].asText())
        assertEquals("member-b", view["current_member_id"].asText())
        assertTrue(view["registered"].asBoolean())
        assertTrue(view["partitioning_active"].asBoolean())
        assertEquals(1000, view["total_buckets"].asInt())

        val members = view["members"]
        assertEquals(3, members.size())
        // 1000 buckets over three members: the first takes the one extra bucket.
        assertMember(members[0], "member-a", 0, 334, current = false)
        assertMember(members[1], "member-b", 334, 667, current = true)
        assertMember(members[2], "member-c", 667, 1000, current = false)
        assertEquals(1000, members.sumOf { it["bucket_count"].asInt() })
    }

    @Test
    fun singleMemberOwnsEveryBucket() {
        val view = cluster(FixedMembership(List.of("only"), "only"))

        assertMember(view["members"][0], "only", 0, 1000, current = true)
        assertTrue(view["partitioning_active"].asBoolean())
    }

    @Test
    fun unregisteredInstanceReportsInactivePartitioning() {
        val view = cluster(FixedMembership(List.empty(), current = null))

        assertTrue(view["current_member_id"].isNull)
        assertFalse(view["registered"].asBoolean())
        assertFalse(view["partitioning_active"].asBoolean())
        assertEquals(0, view["members"].size())
    }

    @Test
    fun featureGateOffReportsInactivePartitioningButStillListsMembers() {
        val view =
            cluster(FixedMembership(List.of("member-a", "member-b"), "member-a"), partitioningEnabled = false)

        assertFalse(view["partitioning_active"].asBoolean())
        assertEquals(2, view["members"].size())
        assertMember(view["members"][0], "member-a", 0, 500, current = true)
        assertMember(view["members"][1], "member-b", 500, 1000, current = false)
    }

    private fun assertMember(
        node: JsonNode,
        id: String,
        start: Int,
        end: Int,
        current: Boolean,
    ) {
        assertEquals(id, node["member_id"].asText())
        assertEquals(start, node["start_bucket_inclusive"].asInt())
        assertEquals(end, node["end_bucket_exclusive"].asInt())
        assertEquals(end - start, node["bucket_count"].asInt())
        assertEquals(current, node["current"].asBoolean())
    }
}
