package com.airbnb.skipper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Throwaway: proves the CircleCI required status check actually blocks a merge when the
 * build is red. Delete this file (and its pull request) once the gate is confirmed.
 */
class CiGateVerificationTest {
    @Test
    fun deliberateFailureToExerciseTheMergeGate() {
        assertEquals("green", "red", "intentional failure: verifying the CI merge gate")
    }
}
