package com.airbnb.skipper.internal.scheduler

import kotlin.random.Random

/**
 * Divides scheduler slots between fair-share queues (see [Task.queue]), so that no queue can take
 * the slots of another however many tasks it has ready.
 *
 * A queue's weight says how large its share is next to the others: a queue of weight 10 gets about
 * ten times the slots of a queue of weight 1 while both have work. Weights only matter between
 * queues that have work at the same moment; a queue with nothing ready takes no share.
 */
internal object FairShare {
    /** The weight of a queue that was given none. */
    const val DEFAULT_WEIGHT = 1

    /**
     * Splits [slots] between [queues], which all have work ready.
     *
     * 1. With at least one slot per queue, every queue gets one slot first, whatever its weight. The
     *    slots left over are split in proportion to the weights. This first slot is what keeps a
     *    low-weight queue moving while a heavy one is flooded.
     * 2. With fewer slots than queues, each slot goes to a different queue, drawn at random with a
     *    chance in proportion to its weight. Every queue has a chance in every split, so over
     *    repeated splits every queue is served.
     *
     * @return the slots of each queue that got any; they add up to [slots] at most.
     */
    fun split(
        slots: Int,
        queues: Collection<String>,
        weightOf: (String) -> Int,
        random: Random,
    ): Map<String, Int> {
        val contenders = queues.distinct()
        if (slots <= 0 || contenders.isEmpty()) {
            return emptyMap()
        }
        val weights = contenders.associateWith { weightOf(it).coerceAtLeast(1).toLong() }
        if (slots < contenders.size) {
            return drawDistinct(slots, weights, random).associateWith { 1 }
        }
        val spare = (slots - contenders.size).toLong()
        val totalWeight = weights.values.sum()
        val shares = contenders.associateWithTo(LinkedHashMap()) { 1 + (spare * weights.getValue(it) / totalWeight).toInt() }
        // Whole slots lost to rounding down go to the queues that were rounded down the most. Ties
        // are broken at random so that the same queue does not win them every time.
        val unassigned = slots - shares.values.sum()
        contenders
            .shuffled(random)
            .sortedByDescending { spare * weights.getValue(it) % totalWeight }
            .take(unassigned)
            .forEach { shares[it] = shares.getValue(it) + 1 }
        return shares
    }

    /** Draws [count] different queues, each draw with a chance in proportion to the queue's weight. */
    private fun drawDistinct(
        count: Int,
        weights: Map<String, Long>,
        random: Random,
    ): List<String> {
        val remaining = LinkedHashMap(weights)
        val drawn = ArrayList<String>(count)
        repeat(count) {
            var ticket = random.nextLong(remaining.values.sum())
            var winner = remaining.keys.last()
            for ((queue, weight) in remaining) {
                if (ticket < weight) {
                    winner = queue
                    break
                }
                ticket -= weight
            }
            remaining.remove(winner)
            drawn.add(winner)
        }
        return drawn
    }
}
