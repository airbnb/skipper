package com.airbnb.skipper.internal.cluster

import io.vavr.collection.List
import java.util.ArrayList
import java.util.HashSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BucketPartitionerTest {
    private val partitioner = BucketPartitioner()

    @Test
    fun testSingleMemberGetsFullRange() {
        val members = List.of("member1")
        val range = partitioner.getBucketRangeForMember("member1", members)

        assertEquals(FULL_RANGE.startInclusive, range.startInclusive)
        assertEquals(FULL_RANGE.endExclusive, range.endExclusive)
    }

    @Test
    fun testTwoMembersSplitRange() {
        val members = List.of("member1", "member2")

        val range1 = partitioner.getBucketRangeForMember("member1", members)
        val range2 = partitioner.getBucketRangeForMember("member2", members)

        // Ranges should be different
        assertNotEquals(range1, range2)
        assertEquals(0, range1.startInclusive)
        assertEquals(500, range1.endExclusive)
        assertEquals(500, range2.startInclusive)
        assertEquals(1000, range2.endExclusive)

        // Both ranges should have buckets
        assertTrue(range1.size() > 0)
        assertTrue(range2.size() > 0)

        // Total buckets should equal 1000
        assertEquals(1000, range1.size() + range2.size())
    }

    @Test
    fun testThreeMembersSplitRange() {
        val members = List.of("member1", "member2", "member3")

        val range1 = partitioner.getBucketRangeForMember("member1", members)
        val range2 = partitioner.getBucketRangeForMember("member2", members)
        val range3 = partitioner.getBucketRangeForMember("member3", members)

        // With 1000 buckets and 3 members: 1000 ÷ 3 = 333 remainder 1
        // So member1 gets 334 buckets (the extra one), member2 and member3 get 333 each

        // Verify exact ranges
        assertEquals(0, range1.startInclusive, "Member1 should start at bucket 0")
        assertEquals(334, range1.endExclusive, "Member1 should end before bucket 334")
        assertEquals(334, range1.size(), "Member1 should have 334 buckets")

        assertEquals(334, range2.startInclusive, "Member2 should start at bucket 334")
        assertEquals(667, range2.endExclusive, "Member2 should end before bucket 667")
        assertEquals(333, range2.size(), "Member2 should have 333 buckets")

        assertEquals(667, range3.startInclusive, "Member3 should start at bucket 667")
        assertEquals(1000, range3.endExclusive, "Member3 should end before bucket 1000")
        assertEquals(333, range3.size(), "Member3 should have 333 buckets")

        // Verify no gaps and no overlaps
        assertEquals(
            range1.endExclusive,
            range2.startInclusive,
            "No gap between member1 and member2"
        )
        assertEquals(
            range2.endExclusive,
            range3.startInclusive,
            "No gap between member2 and member3"
        )

        // Total buckets should equal 1000
        assertEquals(1000, range1.size() + range2.size() + range3.size())

        // Verify ranges are contiguous and cover all buckets
        assertEquals(0, range1.startInclusive, "Should start at bucket 0")
        assertEquals(1000, range3.endExclusive, "Should end at bucket 1000")
    }

    @Test
    fun testHashToBucketConsistency() {
        val taskId = "test-task-123"
        val bucket1 = partitioner.hashToBucket(taskId)
        val bucket2 = partitioner.hashToBucket(taskId)

        assertEquals(bucket1, bucket2, "Bucket should be consistent for same input")
        assertTrue(bucket1 >= 0 && bucket1 < 1000, "Bucket should be in range [0, 999]")
    }

    @Test
    fun testBucketDistribution() {
        val buckets = HashSet<Int>()

        // Generate buckets for different task IDs
        for (i in 0 until 10000) {
            val taskId = "task-$i"
            val bucket = partitioner.hashToBucket(taskId)
            buckets.add(bucket)
            assertTrue(bucket >= 0 && bucket < 1000, "Bucket should be in range [0, 999]")
        }

        // Should have good distribution across buckets
        assertTrue(
            buckets.size > 800,
            "Bucket distribution should be good (got " + buckets.size + " unique buckets)"
        )
    }

    @Test
    fun testMemberNotFound() {
        val members = List.of("member1", "member2")

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            partitioner.getBucketRangeForMember("member3", members)
        }
    }

    @Test
    fun testEmptyMembersList() {
        val members = List.empty<String>()
        val range = partitioner.getBucketRangeForMember("member1", members)
        assertEquals(FULL_RANGE, range, "With no members, should return full range")
    }

    @Test
    fun testBucketRangeContains() {
        val range = BucketRange(100, 200)

        assertTrue(range.contains(100)) // start inclusive
        assertTrue(range.contains(150)) // middle
        assertFalse(range.contains(200)) // end exclusive
        assertFalse(range.contains(50)) // before
        assertFalse(range.contains(250)) // after
    }

    @Test
    fun testMemberAssignment() {
        val members = List.of("member1", "member2", "member3")

        // Test that each bucket is assigned to exactly one member by checking all partitions
        val bucketsCovered = BooleanArray(1000)

        for (member in members) {
            val range = partitioner.getBucketRangeForMember(member, members)

            // Mark all buckets in this member's range as covered
            for (bucket in range.startInclusive until range.endExclusive) {
                assertFalse(
                    bucketsCovered[bucket],
                    "Bucket $bucket should not be assigned to multiple members"
                )
                bucketsCovered[bucket] = true
            }
        }

        // Verify all buckets are covered
        for (bucket in 0 until 1000) {
            assertTrue(bucketsCovered[bucket], "Bucket $bucket should be assigned to a member")
        }
    }

    @Test
    fun testNoOverlapBetweenMembers() {
        val members = List.of("member1", "member2", "member3", "member4")

        // Get all ranges
        val ranges = ArrayList<BucketRange>()
        for (member in members) {
            ranges.add(partitioner.getBucketRangeForMember(member, members))
        }

        // Check no overlaps
        for (i in 0 until ranges.size) {
            for (j in i + 1 until ranges.size) {
                val range1 = ranges[i]
                val range2 = ranges[j]
                assertFalse(
                    rangesOverlap(range1, range2),
                    "Ranges should not overlap: $range1 and $range2"
                )
            }
        }
    }

    /** Check if two bucket ranges overlap. */
    private fun rangesOverlap(
        range1: BucketRange,
        range2: BucketRange
    ): Boolean {
        // Ranges overlap if one starts before the other ends
        return range1.startInclusive < range2.endExclusive &&
            range2.startInclusive < range1.endExclusive
    }

    companion object {
        private val FULL_RANGE = BucketRange(0, 1000)
    }
}
