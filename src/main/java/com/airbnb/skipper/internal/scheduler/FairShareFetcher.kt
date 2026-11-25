package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.internal.cluster.BucketRange
import io.vavr.collection.List
import kotlin.random.Random
import org.slf4j.LoggerFactory

/**
 * Fetches tasks from the scheduler one fair-share queue at a time, giving each queue with work
 * ready its share of the slots (see [FairShare]) instead of a random sample of everything ready,
 * which a queue with a huge backlog would fill almost alone.
 *
 * @param weights the weight of each queue by name; a queue not listed has [FairShare.DEFAULT_WEIGHT].
 */
internal class FairShareFetcher(
    private val scheduler: Scheduler,
    private val weights: Map<String, Int>,
    private val random: Random = Random.Default,
) {
    /**
     * Fetches up to [slots] tasks.
     *
     * 1. With no queue or a single queue ready there is nothing to share out, so it is one plain
     *    fetch, the same as without fair share.
     * 2. Otherwise each ready queue is fetched up to its share. Slots a queue did not use, because
     *    it had fewer tasks ready (or none in this [partition]), are then filled from whatever is
     *    ready, so no slot is left idle while there is work.
     */
    fun fetch(
        slots: Int,
        partition: BucketRange?,
    ): List<Task<Any>> {
        val due = scheduler.dueQueues()
        if (due.size() <= 1) {
            return fetchAny(slots, partition)
        }
        return fetchShares(FairShare.split(slots, due.asJava(), this::weightOf, random), partition, slots)
    }

    private fun weightOf(queue: String): Int = weights[queue] ?: FairShare.DEFAULT_WEIGHT

    /**
     * Fetches each queue of [shares] up to its share, then fills up to [slots] tasks from whatever
     * is ready.
     *
     * A fetch leases the tasks it returns, so a failure part of the way must not lose the tasks
     * already leased: they are delivered, and the rest is left for the next round. A failure before
     * anything is leased is thrown.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun fetchShares(
        shares: Map<String, Int>,
        partition: BucketRange?,
        slots: Int,
    ): List<Task<Any>> {
        var tasks = List.empty<Task<Any>>()
        try {
            for ((queue, share) in shares) {
                tasks = tasks.appendAll(scheduler.fetch(share, partition, queue))
            }
            if (tasks.size() < slots) {
                tasks = tasks.appendAll(fetchAny(slots - tasks.size(), partition))
            }
        } catch (e: Exception) {
            if (tasks.isEmpty) {
                throw e
            }
            log.warn("fetch failed part of the way, delivering the {} tasks already leased", tasks.size(), e)
        }
        return tasks
    }

    private fun fetchAny(
        slots: Int,
        partition: BucketRange?,
    ): List<Task<Any>> = if (partition == null) scheduler.fetch(slots) else scheduler.fetch(slots, partition)

    private companion object {
        private val log = LoggerFactory.getLogger(FairShareFetcher::class.java)
    }
}
