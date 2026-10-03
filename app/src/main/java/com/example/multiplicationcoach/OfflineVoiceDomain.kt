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
        val candidates = allCandidates(transcript)

        if (candidates.isEmpty() || candidates.any { it !in 1..81 }) return null
        return candidates.distinct().singleOrNull()
    }

    fun containsNumber(transcript: String): Boolean = allCandidates(transcript).any { it in 1..81 }

    fun containsAnswer(transcript: String, answer: Int): Boolean = allCandidates(transcript).any { it == answer }

    /** Accepts a full spoken formula only when its factors match the asked problem. */
    fun parseForProblem(a: Int, b: Int, expectedAnswer: Int, transcript: String): Int? {
        if (containsAnswer(transcript, expectedAnswer)) return expectedAnswer

        parse(transcript)?.let { answer -> if (answer == expectedAnswer) return answer }

        val arabicCandidates = arabicCandidates(transcript)
        val chineseCandidates = chineseCandidates(transcript)
        if (arabicCandidates.endsWithFormula(a, b, expectedAnswer) ||
            chineseCandidates.endsWithFormula(a, b, expectedAnswer)
        ) return expectedAnswer

        val compactNumbers = transcript.filter { it.isDigit() || it in "零〇一二两三四五六七八九十" }
            .replace('〇', '零')
            .replace('两', '二')
        val arabicFormula = "$a$b$expectedAnswer"
        val chineseFormula = chineseText(a) + chineseText(b) + chineseText(expectedAnswer)
        return if (compactNumbers == arabicFormula || compactNumbers == chineseFormula) expectedAnswer else null
    }

    private fun allCandidates(transcript: String): List<Int> = arabicCandidates(transcript) + chineseCandidates(transcript)

    private fun arabicCandidates(transcript: String): List<Int> =
        arabicNumber.findAll(transcript).mapNotNull { match -> match.value.toIntOrNull() }.toList()

    private fun chineseCandidates(transcript: String): List<Int> =
        chineseNumber.findAll(transcript).mapNotNull { match -> parseChineseNumber(match.value) }.toList()

    private fun List<Int>.endsWithFormula(a: Int, b: Int, answer: Int): Boolean =
        size >= 3 && takeLast(3) == listOf(a, b, answer)

    private fun chineseText(number: Int): String {
        require(number in 1..81)
        val digits = "零一二三四五六七八九"
        return when {
            number < 10 -> digits[number].toString()
            number < 20 -> "十" + if (number == 10) "" else digits[number % 10]
            else -> "${digits[number / 10]}十" + if (number % 10 == 0) "" else digits[number % 10]
        }
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
