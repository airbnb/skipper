package com.airbnb.skipper.internal.scheduler

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How [FairShare] divides slots between queues. */
class FairShareTest {
    private val random = Random(42)
    private val equalWeights: (String) -> Int = { 1 }

    @Test
    fun testNothingToSplit() {
        assertEquals(emptyMap<String, Int>(), FairShare.split(0, listOf("a"), equalWeights, random))
        assertEquals(emptyMap<String, Int>(), FairShare.split(5, emptyList(), equalWeights, random))
    }

    @Test
    fun testOneQueueTakesEverySlot() {
        assertEquals(mapOf("a" to 5), FairShare.split(5, listOf("a"), equalWeights, random))
    }

    @Test
    fun testEqualWeightsSplitEvenly() {
        assertEquals(mapOf("a" to 5, "b" to 5), FairShare.split(10, listOf("a", "b"), equalWeights, random))
    }

    @Test
    fun testEveryQueueGetsOneSlotWhateverItsWeight() {
        val weights = mapOf("heavy" to 1_000_000, "light" to 1)
        val shares = FairShare.split(5, listOf("heavy", "light"), weights::getValue, random)
        assertEquals(mapOf("heavy" to 4, "light" to 1), shares)
    }

    @Test
    fun testSpareSlotsFollowTheWeights() {
        val weights = mapOf("a" to 3, "b" to 1)
        // One each, then the 96 spare slots go 72 and 24.
        assertEquals(mapOf("a" to 73, "b" to 25), FairShare.split(98, listOf("a", "b"), weights::getValue, random))
    }

    @Test
    fun testSharesAlwaysAddUpToTheSlots() {
        val queues = listOf("a", "b", "c", "d", "e", "f", "g")
        val weights = mapOf("a" to 7, "b" to 3, "c" to 3, "d" to 2, "e" to 1, "f" to 1, "g" to 1)
        for (slots in 1..200) {
            val shares = FairShare.split(slots, queues, weights::getValue, random)
            assertEquals(slots, shares.values.sum(), "slots=$slots")
            assertTrue(shares.values.all { it >= 1 }, "slots=$slots")
            if (slots >= queues.size) {
                assertEquals(queues.toSet(), shares.keys, "slots=$slots")
            }
        }
    }

    @Test
    fun testWithFewerSlotsThanQueuesEveryQueueIsServedOverTime() {
        val queues = (1..20).map { "q$it" }
        // q1 outweighs each of the others a hundred to one, and there are only two slots per round.
        val weightOf: (String) -> Int = { if (it == "q1") 100 else 1 }
        val served = HashMap<String, Int>()
        repeat(2_000) {
            val shares = FairShare.split(2, queues, weightOf, random)
            assertEquals(2, shares.size)
            shares.keys.forEach { served.merge(it, 1, Int::plus) }
        }
        assertEquals(queues.toSet(), served.keys, "every queue was picked at least once")
        assertTrue(served.getValue("q1") > 10 * served.getValue("q2"), "the heavy queue is picked far more often: $served")
    }

    @Test
    fun testReservedSharesGoOnlyToQueuesBelowTheirShare() {
        // Limit 10, equal weights, two queues: each has 5 reserved. "noisy" runs 12, "quiet" runs 2.
        val shares =
            FairShare.splitReserved(
                slots = 10,
                limit = 10,
                admitted = mapOf("noisy" to 12, "quiet" to 2),
                due = listOf("noisy", "quiet"),
                weightOf = equalWeights,
                random = random,
            )
        assertEquals(mapOf("quiet" to 3), shares)
    }

    @Test
    fun testReservedShareCountsQueuesThatAreOnlyRunning() {
        // "noisy" has nothing ready but fills the process; it still counts when dividing the limit.
        val shares = FairShare.splitReserved(5, 8, mapOf("noisy" to 9), listOf("a", "b", "c"), equalWeights, random)
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 1).values.sorted(), shares.values.sorted())
        assertEquals(setOf("a", "b", "c"), shares.keys)
    }

    @Test
    fun testReservedShareIsAtLeastOne() {
        // 50 queues against a limit of 4: the limit divided by weight rounds to zero, yet each queue
        // that runs nothing is owed one slot.
        val due = (1..50).map { "q$it" }
        val shares = FairShare.splitReserved(50, 4, mapOf("noisy" to 30), due, equalWeights, random)
        assertEquals(due.toSet(), shares.keys)
        assertTrue(shares.values.all { it == 1 })
    }

    @Test
    fun testNoReservedShareWhenEveryQueueHasItsShare() {
        val shares = FairShare.splitReserved(5, 4, mapOf("a" to 2, "b" to 3), listOf("a", "b"), equalWeights, random)
        assertEquals(emptyMap<String, Int>(), shares)
    }
}
