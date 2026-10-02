package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeAnswerPlannerTest {
    @Test
    fun blankTranscriptRepeatsTheSameProblemInsteadOfScoringItWrong() {
        val plan = PracticeAnswerPlanner.plan(Problem(3, 4), "")

        assertTrue(plan.repeatProblem)
        assertEquals("feedback_no_speech", plan.audioResourceName)
        assertTrue(plan.waitForAudioCompletion)
    }

    @Test
    fun correctAnswerUsesTheCorrectCueAndWaitsForIt() {
        val plan = PracticeAnswerPlanner.plan(Problem(3, 4), "十二")

        assertFalse(plan.repeatProblem)
        assertTrue(plan.verification!!.correct)
        assertEquals("feedback_correct", plan.audioResourceName)
        assertTrue(plan.waitForAudioCompletion)
    }

    @Test
    fun wrongAnswerUsesTheWholeCorrectionCueAndWaitsForIt() {
        val plan = PracticeAnswerPlanner.plan(Problem(3, 4), "十一")

        assertFalse(plan.repeatProblem)
        assertFalse(plan.verification!!.correct)
        assertEquals("correction_3_4", plan.audioResourceName)
        assertTrue(plan.waitForAudioCompletion)
    }
}
