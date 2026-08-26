package com.airbnb.skipper.internal.cluster

/**
 * Represents a bucket range for simple bucket-based partitioning.
 *
 * Bucket ranges define contiguous segments of buckets (0-999) that are assigned to cluster members
 * for processing. This is much simpler than hash-based ranges and avoids wrap-around complexity.
 *
 * @property startInclusive Start bucket (inclusive).
 * @property endExclusive End bucket (exclusive).
 */
data class BucketRange(val startInclusive: Int, val endExclusive: Int) {
    /**
     * Check if a given bucket falls within this range.
     *
     * @param bucket the bucket to test (0-999)
     * @return true if the bucket is within this range
     */
    fun contains(bucket: Int): Boolean = bucket in startInclusive until endExclusive

    /**
     * Calculate the number of buckets in this range.
     *
     * @return the number of buckets in this range
     */
    fun size(): Int = endExclusive - startInclusive

    override fun toString(): String = String.format("buckets[%d, %d)", startInclusive, endExclusive)
}
