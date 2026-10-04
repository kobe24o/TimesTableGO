package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeAnswerPlannerTest {
    @Test
    fun answerCountdownUsesTheActualRecordingStartTime() {
        assertEquals(3, AnswerWindowClock.remainingSeconds(3, recordingStartedAtMs = 1_000, nowMs = 1_000))
        assertEquals(3, AnswerWindowClock.remainingSeconds(3, recordingStartedAtMs = 1_000, nowMs = 1_999))
        assertEquals(2, AnswerWindowClock.remainingSeconds(3, recordingStartedAtMs = 1_000, nowMs = 2_000))
    }

    @Test
    fun blankTranscriptRepeatsTheSameProblemInsteadOfScoringItWrong() {
        val plan = PracticeAnswerPlanner.plan(Problem(3, 4), "")

        assertTrue(plan.repeatProblem)
        assertEquals("feedback_no_speech", plan.audioResourceName)
        assertTrue(plan.waitForAudioCompletion)
    }

    @Test
    fun punctuationOnlyTranscriptRepeatsTheSameProblemInsteadOfScoringItWrong() {
        val plan = PracticeAnswerPlanner.plan(Problem(8, 9), "。")

        assertTrue(plan.repeatProblem)
        assertEquals("feedback_no_speech", plan.audioResourceName)
    }

    @Test
    fun mixedArabicAndChineseFortyIsAcceptedAsTheSpokenCorrectAnswer() {
        val plan = PracticeAnswerPlanner.plan(Problem(5, 8), "5 8 4十")

        assertFalse(plan.repeatProblem)
        assertTrue(plan.verification!!.correct)
        assertEquals(40, plan.verification!!.extractedAnswer)
    }

    @Test
    fun correctAnswerUsesTheShortCorrectCueAndWaitsForIt() {
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

    @Test
    fun questionShowsTheCorrectNumberAfterTheAnswerIsChecked() {
        val state = UiState(problem = Problem(3, 7), revealedAnswer = 21)

        assertEquals("3 × 7 = 21", state.questionText())
    }
}
