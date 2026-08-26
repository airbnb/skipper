package com.airbnb.skipper.internal.cluster

import io.vavr.collection.List
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import javax.inject.Singleton

/**
 * Simple bucket-based partitioner for distributing tasks among cluster members.
 *
 * This class divides tasks into 1000 buckets (0-999) and assigns contiguous bucket ranges to
 * cluster members. This uses simple modulo hashing rather than consistent hashing, which is simpler
 * and sufficient for most use cases.
 *
 * Example with 3 members: - Member 0: buckets 0-333 (334 buckets) - Member 1: buckets 334-666 (333
 * buckets) - Member 2: buckets 667-999 (333 buckets)
 *
 * Maximum supported cluster size is 1000 members, which is far more than any practical deployment
 * would need.
 */
@Singleton
class BucketPartitioner {
    /**
     * Get the bucket range assigned to a specific member within the cluster.
     *
     * The members list must be consistently ordered across all cluster instances to ensure the same
     * member gets the same bucket range.
     *
     * @param memberId the member to get the range for
     * @param allMembers consistently ordered list of all cluster members
     * @return the bucket range assigned to the specified member
     * @throws IllegalArgumentException if memberId is not in the members list
     */
    fun getBucketRangeForMember(
        memberId: String,
        allMembers: List<String>
    ): BucketRange {
        val members = if (allMembers.isEmpty) List.of(memberId) else allMembers
        // Find the index of this member in the sorted list
        var memberIndex = -1
        for (i in 0 until members.size()) {
            if (members[i] == memberId) {
                memberIndex = i
                break
            }
        }
        require(memberIndex != -1) { "Member not found in members list: $memberId" }
        return getBucketRangeForIndex(memberIndex, members.size())
    }

    /**
     * Get the bucket range for a specific index in the member list.
     *
     * @param memberIndex zero-based index of the member
     * @param totalMembers total number of members in the cluster
     * @return the bucket range for the member at the specified index
     */
    private fun getBucketRangeForIndex(
        memberIndex: Int,
        totalMembers: Int
    ): BucketRange {
        require(memberIndex in 0 until totalMembers) {
            "Member index out of bounds: $memberIndex (total: $totalMembers)"
        }
        if (totalMembers == 1) {
            // Single member gets all buckets
            return BucketRange(0, TOTAL_BUCKETS)
        }
        // Divide 1000 buckets evenly among members
        val bucketsPerMember = TOTAL_BUCKETS / totalMembers
        val remainder = TOTAL_BUCKETS % totalMembers
        // First 'remainder' members get one extra bucket
        var startBucket = memberIndex * bucketsPerMember
        startBucket += if (memberIndex < remainder) memberIndex else remainder
        val bucketCount = bucketsPerMember + if (memberIndex < remainder) 1 else 0
        val endBucket = startBucket + bucketCount
        return BucketRange(startBucket, endBucket)
    }

    /**
     * Hash a string value to a bucket (0-999).
     *
     * This method is used to convert task IDs into bucket numbers that can be tested against bucket
     * ranges for partitioning. Uses CRC32 to match MySQL's CRC32 function.
     *
     * @param value the string to hash
     * @return the bucket number (0-999)
     */
    fun hashToBucket(value: String): Int {
        val crc32 = CRC32()
        crc32.update(value.toByteArray(StandardCharsets.UTF_8))
        val hash = crc32.value
        return (hash % TOTAL_BUCKETS).toInt()
    }

    companion object {
        const val TOTAL_BUCKETS = 1000
    }
}
