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
    fun mapsAllFixedFeedbackToRawResourceNames() {
        assertEquals("feedback_incorrect", PromptAudioIndex.feedbackResourceName(FixedFeedback.Incorrect))
        assertEquals("feedback_listen", PromptAudioIndex.feedbackResourceName(FixedFeedback.Listen))
        assertEquals("feedback_no_speech", PromptAudioIndex.feedbackResourceName(FixedFeedback.NoSpeech))
    }

    @Test
    fun rejectsOutOfRangeProblemOperands() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptAudioIndex.problemResourceName(0, 4)
        }
    }
}
