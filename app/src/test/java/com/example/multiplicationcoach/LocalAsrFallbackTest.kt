package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAsrFallbackTest {
    @Test
    fun usesTheFullRecordingWhenVadProducedNoTranscript() {
        assertEquals(
            "二十四",
            LocalAsrTranscriptFallback.choose(vadTranscript = "", fullRecordingTranscript = "二十四"),
        )
    }

    @Test
    fun keepsTheVadTranscriptWhenItAlreadyContainsSpeech() {
        assertEquals(
            "三十六",
            LocalAsrTranscriptFallback.choose(vadTranscript = "三十六", fullRecordingTranscript = ""),
        )
    }

    @Test
    fun usesTheFullRecordingWhenVadOnlyProducedNonNumericSpeech() {
        assertEquals(
            "二十一",
            LocalAsrTranscriptFallback.choose(vadTranscript = "是", fullRecordingTranscript = "二十一"),
        )
    }

    @Test
    fun prefersTheFullRecordingWhenItMatchesTheExpectedAnswer() {
        assertEquals(
            "二十一",
            LocalAsrTranscriptFallback.choose(
                vadTranscript = "二十",
                fullRecordingTranscript = "二十一",
                expectedAnswer = 21,
            ),
        )
    }
}
