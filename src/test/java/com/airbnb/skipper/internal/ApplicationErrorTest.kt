package com.airbnb.skipper.internal

import com.airbnb.skipper.ApplicationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationErrorTest {
    @Test
    fun testFromExceptionWithRetryDelay() {
        val internalCause = IllegalArgumentException("invalid input!")
        val error = IllegalStateException("illegal state!", internalCause)
        val appError = ApplicationError.fromException(error)!!
        assertEquals("illegal state!", appError.message)
        assertEquals(error.javaClass.name, appError.type)
        assertEquals(internalCause.javaClass.name, appError.cause!!.type)
        assertEquals("invalid input!", appError.cause!!.message)
    }
}
