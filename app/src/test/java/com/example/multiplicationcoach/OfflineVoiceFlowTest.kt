package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVoiceFlowTest {
    @Test
    fun verifiesOneUnambiguousChineseAnswerLocally() {
        val result = LocalAnswerVerifier.verify(expected = 24, transcript = "答案是二十四")

        assertTrue(result.correct)
        assertEquals(24, result.extractedAnswer)
        assertEquals("local", result.checkedBy)
    }

    @Test
    fun rejectsAmbiguousTranscriptLocally() {
        val result = LocalAnswerVerifier.verify(expected = 12, transcript = "三乘四等于十二")

        assertFalse(result.correct)
        assertNull(result.extractedAnswer)
    }

    @Test
    fun acceptsTheFactorsFollowedByTheAnswerForTheCurrentProblem() {
        val result = PracticeAnswerPlanner.plan(Problem(2, 8), "2 8 16").verification!!

        assertTrue(result.correct)
        assertEquals(16, result.extractedAnswer)
    }

    @Test
    fun missingAudioKeepsTextualPracticeFlowUsable() {
        val playback = FixedAudioPlayback.resolve(resourceId = 0, resourceName = "prompt_3_4")

        assertFalse(playback.played)
        assertEquals("音频资源缺失，已改为文字提示。", playback.userMessage)
    }
}
