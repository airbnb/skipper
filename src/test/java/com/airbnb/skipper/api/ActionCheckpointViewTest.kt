package com.airbnb.skipper.api

import com.airbnb.skipper.api.ActionCheckpointView.ActionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ActionCheckpointViewTest {
    @Nested
    inner class ActionResultTests {
        @Test
        fun testSuccessReturnsCorrectValues() {
            val result = ActionResult.success("test value")

            assertEquals(true, result.isSuccess)
            assertEquals(false, result.isError)
            assertEquals("test value", result.getOrNull())
            assertNull(result.errorOrNull())
        }

        @Test
        fun testErrorReturnsCorrectValues() {
            val error = RuntimeException("test error")
            val result = ActionResult.error(error)

            assertEquals(false, result.isSuccess)
            assertEquals(true, result.isError)
            assertNull(result.getOrNull())
            assertEquals(error, result.errorOrNull())
        }

        @Test
        fun testSuccessWithNullValue() {
            val result = ActionResult.success(null)

            assertEquals(true, result.isSuccess)
            assertNull(result.getOrNull())
        }

        @Test
        fun testSuccessDataClass() {
            val result = ActionResult.Success("test")

            assertEquals("test", result.value)
            assertEquals(true, result.isSuccess)
        }

        @Test
        fun testErrorDataClass() {
            val error = IllegalArgumentException("invalid argument")
            val result = ActionResult.Error(error)

            assertEquals(error, result.error)
            assertEquals(true, result.isError)
        }
    }
}
