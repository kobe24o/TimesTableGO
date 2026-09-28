package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptAudioIndexTest {
    @Test
    fun mapsProblemToRawResourceName() {
        assertEquals("prompt_3_4", PromptAudioIndex.problemResourceName(3, 4))
    }

    @Test
    fun mapsCorrectFeedbackToRawResourceName() {
        assertEquals("feedback_correct", PromptAudioIndex.feedbackResourceName(FixedFeedback.Correct))
    }

    @Test
    fun rejectsOutOfRangeProblemOperands() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptAudioIndex.problemResourceName(0, 4)
        }
    }
}
