package com.airbnb.skipper.internal.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassNameCompatTest {
    // Non-Airbnb class loads directly with no fallback logic involved
    @Test
    @Throws(ClassNotFoundException::class)
    fun forName_existingClass_returnsClass() {
        assertEquals(String::class.java, ClassNameCompat.forName("java.lang.String"))
    }

    // Skipper class on classpath loads directly without needing the fallback
    @Test
    @Throws(ClassNotFoundException::class)
    fun forName_skipperClass_loadsDirectly() {
        val result = ClassNameCompat.forName("com.airbnb.skipper.CheckpointHelpers")
        assertEquals("com.airbnb.skipper.CheckpointHelpers", result.name)
    }

    // Rollback scenario: Skipper pod reads "com.airbnb.tempo.CheckpointHelpers" written by a
    // Tempo pod. Tempo is not on the Skipper classpath, so ClassNameCompat falls back to
    // "com.airbnb.skipper.CheckpointHelpers" and the workflow resumes without error.
    @Test
    @Throws(ClassNotFoundException::class)
    fun forName_tempoCheckpointHelpers_fallsBackToSkipperEquivalent() {
        val result = ClassNameCompat.forName("com.airbnb.tempo.CheckpointHelpers")
        assertEquals("com.airbnb.skipper.CheckpointHelpers", result.name)
    }

    // Tempo-prefixed class not found in either package — throws the original exception
    // so the error message names the class that was actually requested
    @Test
    fun forName_tempoClassNotFoundInEither_throwsOriginalException() {
        val e =
            assertThrows(ClassNotFoundException::class.java) {
                ClassNameCompat.forName("com.airbnb.tempo.nonexistent.ClassThatDoesNotExist")
            }
        assertTrue(e.message!!.contains("com.airbnb.tempo.nonexistent.ClassThatDoesNotExist"))
    }

    // Skipper-prefixed class not found in either package — throws the original exception
    @Test
    fun forName_skipperClassNotFoundInEither_throwsOriginalException() {
        val e =
            assertThrows(ClassNotFoundException::class.java) {
                ClassNameCompat.forName("com.airbnb.skipper.nonexistent.ClassThatDoesNotExist")
            }
        assertTrue(e.message!!.contains("com.airbnb.skipper.nonexistent.ClassThatDoesNotExist"))
    }

    // Class with an unrecognised prefix — no fallback is attempted, fails immediately
    @Test
    fun forName_unknownPrefixNotFound_throwsClassNotFoundException() {
        assertThrows(ClassNotFoundException::class.java) {
            ClassNameCompat.forName("com.example.nonexistent.Foo")
        }
    }
}
