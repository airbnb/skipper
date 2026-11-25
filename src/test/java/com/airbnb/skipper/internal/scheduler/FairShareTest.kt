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
}
