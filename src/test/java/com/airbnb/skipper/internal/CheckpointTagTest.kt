package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Workflow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckpointTagTest {
    @Test
    fun testPositionalMatching_sameTag() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        val tag2 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        assertTrue(tag1.matches(tag2))
    }

    @Test
    fun testPositionalMatching_differentIteration() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        val tag2 = tag1.toBuilder().iteration(1).build()
        assertFalse(tag1.matches(tag2))
    }

    @Test
    fun testPositionalMatching_differentMethod() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        val tag2 = tag1.toBuilder().actionMethod("doOther").build()
        assertFalse(tag1.matches(tag2))
    }

    @Test
    fun testNamedMatching_sameName() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .checkpointName("prepare-order")
                .build()
        val tag2 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(5)
                .checkpointName("prepare-order")
                .build()
        assertTrue(tag1.matches(tag2))
    }

    @Test
    fun testNamedMatching_ignoresClassMethodIteration() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .checkpointName("step-a")
                .build()
        val tag2 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Actions::class.java)
                .actionMethod("otherMethod")
                .iteration(99)
                .checkpointName("step-a")
                .build()
        assertTrue(tag1.matches(tag2))
    }

    @Test
    fun testNamedMatching_differentName() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .checkpointName("step-a")
                .build()
        val tag2 = tag1.toBuilder().checkpointName("step-b").build()
        assertFalse(tag1.matches(tag2))
    }

    @Test
    fun testNamedMatching_differentWorkflowId() {
        val tag1 =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .checkpointName("step-a")
                .build()
        val tag2 = tag1.toBuilder().workflowId("wf-2").build()
        assertFalse(tag1.matches(tag2))
    }

    @Test
    fun testMixedMatching_namedVsPositional_neverMatches() {
        val named =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .checkpointName("step-a")
                .build()
        val positional =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        assertFalse(named.matches(positional))
        assertFalse(positional.matches(named))
    }

    @Test
    fun testCheckpointNameDefaultsToNull() {
        val tag =
            CheckpointTag.builder()
                .workflowId("wf-1")
                .actionClass(Workflow::class.java)
                .actionMethod("doWork")
                .iteration(0)
                .build()
        assertNull(tag.checkpointName)
    }
}
