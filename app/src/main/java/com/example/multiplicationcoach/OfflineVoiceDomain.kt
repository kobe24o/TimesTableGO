package com.example.multiplicationcoach

/** Derives answer time remaining from the instant microphone capture really began. */
object AnswerWindowClock {
    fun remainingSeconds(totalSeconds: Int, recordingStartedAtMs: Long, nowMs: Long): Int {
        require(totalSeconds in 1..15)
        val remainingMs = (recordingStartedAtMs + totalSeconds * 1_000L - nowMs)
            .coerceIn(0L, totalSeconds * 1_000L)
        return if (remainingMs == 0L) 0 else ((remainingMs + 999L) / 1_000L).toInt()
    }
}

/** Converts a final ASR transcript into one unambiguous multiplication answer. */
object AnswerTranscriptParser {
    private val arabicNumber = Regex("\\d+")
    private val chineseNumber = Regex("[零〇一二两三四五六七八九十百千万]+")
    private val mixedArabicChineseTensNumber = Regex("[0-9]十[零〇一二两三四五六七八九]?")
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

    private val englishOnes = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val englishTens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val englishNumberPattern = Regex(
        "(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|nineteen|eighteen|seventeen|sixteen|" +
            "fifteen|fourteen|thirteen|twelve|eleven|ten|nine|eight|seven|six|five|four|three|two|one|zero)",
    )

    /** Extracts English number words (1..81) such as "twenty one" or "seventy". */
    private fun englishCandidates(transcript: String): List<Int> {
        val tokens = englishNumberPattern.findAll(transcript.lowercase()).map { it.value }.toList()
        val result = mutableListOf<Int>()
        var pendingTen = 0
        for (tok in tokens) {
            when {
                tok in englishTens -> pendingTen = englishTens[tok]!!
                tok in englishOnes -> {
                    val v = englishOnes[tok]!!
                    if (pendingTen > 0) {
                        result += pendingTen + v
                        pendingTen = 0
                    } else {
                        result += v
                    }
                }
            }
        }
        if (pendingTen > 0) result += pendingTen
        return result.filter { it in 1..81 }
    }

    /** Collapses English number words to digits so a spoken formula can be compared digit-wise. */
    private fun englishToDigits(transcript: String): String {
        val words = listOf(
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
            "nineteen", "eighteen", "seventeen", "sixteen", "fifteen", "fourteen", "thirteen",
            "twelve", "eleven", "ten", "nine", "eight", "seven", "six", "five", "four", "three", "two", "one", "zero",
        )
        var out = transcript.lowercase()
        for (w in words) {
            out = out.replace(Regex("\\b$w\\b")) { (englishOnes[w] ?: englishTens.getValue(w)).toString() }
        }
        return out.filter { it.isDigit() }
    }

    fun parse(transcript: String, language: AppLanguage = AppLanguage.ZH): Int? {
        val candidates = allCandidates(transcript, language)

        if (candidates.isEmpty() || candidates.any { it !in 1..81 }) return null
        return candidates.distinct().singleOrNull()
    }

    fun containsNumber(transcript: String, language: AppLanguage = AppLanguage.ZH): Boolean =
        allCandidates(transcript, language).any { it in 1..81 }

    fun containsAnswer(transcript: String, answer: Int, language: AppLanguage = AppLanguage.ZH): Boolean =
        allCandidates(transcript, language).any { it == answer }

    /** Accepts a full spoken formula only when its factors match the asked problem. */
    fun parseForProblem(a: Int, b: Int, expectedAnswer: Int, transcript: String, language: AppLanguage = AppLanguage.ZH): Int? {
        if (containsAnswer(transcript, expectedAnswer, language)) return expectedAnswer

        parse(transcript, language)?.let { answer -> if (answer == expectedAnswer) return answer }

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
        if (compactNumbers == arabicFormula || compactNumbers == chineseFormula) return expectedAnswer
        // English spoken formula, e.g. "three times four equals twelve".
        if (language == AppLanguage.EN && englishToDigits(transcript) == arabicFormula) return expectedAnswer
        return null
    }

    private fun allCandidates(transcript: String, language: AppLanguage): List<Int> =
        arabicCandidates(transcript) + chineseCandidates(transcript) + mixedArabicChineseTensCandidates(transcript) +
            if (language == AppLanguage.EN) englishCandidates(transcript) else emptyList()

    private fun arabicCandidates(transcript: String): List<Int> =
        arabicNumber.findAll(transcript).mapNotNull { match -> match.value.toIntOrNull() }.toList()

    private fun chineseCandidates(transcript: String): List<Int> =
        chineseNumber.findAll(transcript).mapNotNull { match -> parseChineseNumber(match.value) }.toList()

    private fun mixedArabicChineseTensCandidates(transcript: String): List<Int> =
        mixedArabicChineseTensNumber.findAll(transcript).map { match ->
            val tens = match.value.first().digitToInt()
            val units = match.value.last().takeIf { it != '十' }?.let { chineseDigits[it] } ?: 0
            tens * 10 + units
        }.toList()

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
    fun problemResourceName(a: Int, b: Int, language: AppLanguage = AppLanguage.ZH): String {
        require(a in 1..9 && b in 1..9) { "Multiplication operands must be in 1..9" }
        return "prompt_${a}_${b}" + if (language == AppLanguage.EN) "_en" else ""
    }

    fun feedbackResourceName(feedback: FixedFeedback, language: AppLanguage = AppLanguage.ZH): String = when (feedback) {
        FixedFeedback.Correct -> "feedback_correct" + if (language == AppLanguage.EN) "_en" else ""
        FixedFeedback.Incorrect -> "feedback_incorrect" + if (language == AppLanguage.EN) "_en" else ""
        FixedFeedback.Listen -> "feedback_listen" + if (language == AppLanguage.EN) "_en" else ""
        FixedFeedback.NoSpeech -> "feedback_no_speech" + if (language == AppLanguage.EN) "_en" else ""
    }

    fun correctionResourceName(a: Int, b: Int, language: AppLanguage = AppLanguage.ZH): String {
        require(a in 1..9 && b in 1..9) { "Multiplication operands must be in 1..9" }
        return "correction_${a}_${b}" + if (language == AppLanguage.EN) "_en" else ""
    }
}
