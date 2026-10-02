package com.example.multiplicationcoach

/** Converts a final ASR transcript into one unambiguous multiplication answer. */
object AnswerTranscriptParser {
    private val arabicNumber = Regex("\\d+")
    private val chineseNumber = Regex("[零〇一二两三四五六七八九十百千万]+")
    private val chineseDigits = mapOf(
        '零' to 0,
        '〇' to 0,
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )

    fun parse(transcript: String): Int? {
        val candidates = buildList {
            arabicNumber.findAll(transcript).forEach { match ->
                match.value.toIntOrNull()?.let(::add)
            }
            chineseNumber.findAll(transcript).forEach { match ->
                parseChineseNumber(match.value)?.let(::add)
            }
        }

        if (candidates.isEmpty() || candidates.any { it !in 1..81 }) return null
        return candidates.distinct().singleOrNull()
    }

    private fun parseChineseNumber(token: String): Int? {
        if (token.any { it == '百' || it == '千' || it == '万' }) return null

        val tenIndex = token.indexOf('十')
        if (tenIndex >= 0) {
            if (token.count { it == '十' } != 1 || tenIndex > 1 || token.length > tenIndex + 2) {
                return null
            }
            val tens = if (tenIndex == 0) 1 else chineseDigits[token.first()] ?: return null
            val units = when (token.length - tenIndex - 1) {
                0 -> 0
                1 -> chineseDigits[token.last()] ?: return null
                else -> return null
            }
            return tens * 10 + units
        }

        return token.map { chineseDigits[it] ?: return null }
            .joinToString(separator = "")
            .toIntOrNull()
    }
}

enum class FixedFeedback {
    Correct,
    Incorrect,
    Listen,
    NoSpeech,
}

object PromptAudioIndex {
    fun problemResourceName(a: Int, b: Int): String {
        require(a in 1..9 && b in 1..9) { "Multiplication operands must be in 1..9" }
        return "prompt_${a}_${b}"
    }

    fun feedbackResourceName(feedback: FixedFeedback): String = when (feedback) {
        FixedFeedback.Correct -> "feedback_correct"
        FixedFeedback.Incorrect -> "feedback_incorrect"
        FixedFeedback.Listen -> "feedback_listen"
        FixedFeedback.NoSpeech -> "feedback_no_speech"
    }

    fun correctionResourceName(a: Int, b: Int): String {
        require(a in 1..9 && b in 1..9) { "Multiplication operands must be in 1..9" }
        return "correction_${a}_${b}"
    }
}
