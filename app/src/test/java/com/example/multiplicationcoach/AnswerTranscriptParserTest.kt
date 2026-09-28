package com.example.multiplicationcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerTranscriptParserTest {
    @Test
    fun parsesChineseAnswerWords() {
        assertEquals(24, AnswerTranscriptParser.parse("答案是二十四"))
    }

    @Test
    fun parsesSpokenTwoDigitAnswer() {
        assertEquals(24, AnswerTranscriptParser.parse("二四"))
    }

    @Test
    fun rejectsAnExpressionContainingMultipleNumbers() {
        assertNull(AnswerTranscriptParser.parse("三乘四等于十二"))
    }

    @Test
    fun rejectsAnOutOfRangeAnswer() {
        assertNull(AnswerTranscriptParser.parse("八十二"))
    }
}
