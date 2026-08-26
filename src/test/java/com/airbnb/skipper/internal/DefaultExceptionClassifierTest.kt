package com.airbnb.skipper.internal

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultExceptionClassifierTest {
    @Test
    fun testIsRetryable() {
        val classifier = DefaultExceptionClassifier()
        assertFalse(classifier.isRetryable(RuntimeException()))
        assertTrue(classifier.isRetryable(TimeoutException()))
        assertTrue(classifier.isRetryable(ExecutionException(TimeoutException())))
        assertTrue(classifier.isRetryable(CompletionException(TimeoutException())))
        assertFalse(classifier.isRetryable(CompletionException(RuntimeException())))
    }
}
