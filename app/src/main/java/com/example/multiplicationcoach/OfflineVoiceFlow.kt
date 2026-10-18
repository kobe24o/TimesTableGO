package com.example.multiplicationcoach

import android.content.Context
import android.media.MediaPlayer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class LocalVerification(
    val correct: Boolean,
    val extractedAnswer: Int?,
    val checkedBy: String = "local",
)

object LocalAnswerVerifier {
    fun verify(expected: Int, transcript: String, checkedBy: String = "local", language: AppLanguage = AppLanguage.ZH): LocalVerification {
        val answer = AnswerTranscriptParser.parse(transcript, language)
        return LocalVerification(
            correct = answer == expected,
            extractedAnswer = answer,
            checkedBy = checkedBy,
        )
    }

    fun verify(problem: Problem, transcript: String, checkedBy: String = "local", language: AppLanguage = AppLanguage.ZH): LocalVerification {
        val answer = AnswerTranscriptParser.parseForProblem(problem.a, problem.b, problem.answer, transcript, language)
        return LocalVerification(
            correct = answer == problem.answer,
            extractedAnswer = answer,
            checkedBy = checkedBy,
        )
    }
}

data class FixedAudioPlayback(
    val played: Boolean,
    val resourceName: String,
    val userMessage: String? = null,
) {
    companion object {
        fun resolve(resourceId: Int, resourceName: String, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback =
            if (resourceId == 0) {
                FixedAudioPlayback(
                    played = false,
                    resourceName = resourceName,
                    userMessage = if (language == AppLanguage.EN) "Audio missing; showing text instead." else "音频资源缺失，已改为文字提示。",
                )
            } else {
                FixedAudioPlayback(played = true, resourceName = resourceName)
            }
    }
}

/** Plays only packaged raw resources and reports a recoverable result for every failure. */
class FixedAudioPlayer(private val context: Context) {
    private var current: MediaPlayer? = null

    /**
     * A question must finish playing before the answer timer and microphone start.
     * Otherwise a child loses answer time while listening to the question.
     */
    suspend fun playProblemAndWait(problem: Problem, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback =
        playAndWait(PromptAudioIndex.problemResourceName(problem.a, problem.b, language), language)

    fun playFeedback(feedback: FixedFeedback, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback =
        play(PromptAudioIndex.feedbackResourceName(feedback, language), language)

    fun playCorrection(problem: Problem, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback =
        play(PromptAudioIndex.correctionResourceName(problem.a, problem.b, language), language)

    suspend fun playResourceAndWait(resourceName: String, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback =
        playAndWait(resourceName, language)

    fun stop() {
        current?.runCatching { stop() }
        current?.release()
        current = null
    }

    private fun resolveRawResourceId(name: String): Int {
        val direct = context.resources.getIdentifier(name, "raw", context.packageName)
        if (direct != 0) return direct
        if (name.endsWith("_en")) {
            return context.resources.getIdentifier(name.removeSuffix("_en"), "raw", context.packageName)
        }
        return 0
    }

    private fun play(resourceName: String, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback {
        stop()
        val resourceId = resolveRawResourceId(resourceName)
        val resolution = FixedAudioPlayback.resolve(resourceId, resourceName, language)
        if (!resolution.played) return resolution
        return runCatching {
            MediaPlayer.create(context, resourceId)?.also { player ->
                current = player
                player.setOnCompletionListener {
                    if (current === player) current = null
                    player.release()
                }
                player.start()
            } ?: error("MediaPlayer could not open packaged resource")
            resolution
        }.getOrElse {
            stop()
            resolution.copy(played = false, userMessage = if (language == AppLanguage.EN) "Audio playback failed; showing text instead." else "音频播放失败，已改为文字提示。")
        }
    }

    private suspend fun playAndWait(resourceName: String, language: AppLanguage = AppLanguage.ZH): FixedAudioPlayback {
        stop()
        val resourceId = resolveRawResourceId(resourceName)
        val resolution = FixedAudioPlayback.resolve(resourceId, resourceName, language)
        if (!resolution.played) return resolution

        return suspendCancellableCoroutine { continuation ->
            val player = runCatching { MediaPlayer.create(context, resourceId) }.getOrNull()
            if (player == null) {
                continuation.resume(resolution.copy(played = false, userMessage = if (language == AppLanguage.EN) "Audio playback failed; showing text instead." else "音频播放失败，已改为文字提示。"))
                return@suspendCancellableCoroutine
            }

            current = player
            fun finish(result: FixedAudioPlayback) {
                if (current === player) current = null
                player.release()
                if (continuation.isActive) continuation.resume(result)
            }
            player.setOnCompletionListener { finish(resolution) }
            player.setOnErrorListener { _, _, _ ->
                finish(resolution.copy(played = false, userMessage = if (language == AppLanguage.EN) "Audio playback failed; showing text instead." else "音频播放失败，已改为文字提示。"))
                true
            }
            continuation.invokeOnCancellation {
                if (current === player) current = null
                player.runCatching { stop() }
                player.release()
            }
            runCatching { player.start() }.onFailure {
                finish(resolution.copy(played = false, userMessage = if (language == AppLanguage.EN) "Audio playback failed; showing text instead." else "音频播放失败，已改为文字提示。"))
            }
        }
    }
}

/** Decides whether an answer can be scored and makes every feedback cue blocking. */
data class PracticeAnswerPlan(
    val repeatProblem: Boolean,
    val verification: LocalVerification?,
    val audioResourceName: String,
    val waitForAudioCompletion: Boolean = true,
)

object PracticeAnswerPlanner {
    fun plan(problem: Problem, transcript: String, language: AppLanguage = AppLanguage.ZH): PracticeAnswerPlan {
        if (transcript.isBlank() || !AnswerTranscriptParser.containsNumber(transcript, language)) {
            return PracticeAnswerPlan(
                repeatProblem = true,
                verification = null,
                audioResourceName = PromptAudioIndex.feedbackResourceName(FixedFeedback.NoSpeech, language),
            )
        }
        val verification = LocalAnswerVerifier.verify(problem, transcript, language = language)
        return PracticeAnswerPlan(
            repeatProblem = false,
            verification = verification,
            audioResourceName = if (verification.correct) {
                PromptAudioIndex.feedbackResourceName(FixedFeedback.Correct, language)
            } else {
                PromptAudioIndex.correctionResourceName(problem.a, problem.b, language)
            },
        )
    }
}
