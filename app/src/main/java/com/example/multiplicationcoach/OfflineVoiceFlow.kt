package com.example.multiplicationcoach

import android.content.Context
import android.media.MediaPlayer

data class LocalVerification(
    val correct: Boolean,
    val extractedAnswer: Int?,
    val checkedBy: String = "local",
)

object LocalAnswerVerifier {
    fun verify(expected: Int, transcript: String, checkedBy: String = "local"): LocalVerification {
        val answer = AnswerTranscriptParser.parse(transcript)
        return LocalVerification(
            correct = answer == expected,
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
        fun resolve(resourceId: Int, resourceName: String): FixedAudioPlayback =
            if (resourceId == 0) {
                FixedAudioPlayback(
                    played = false,
                    resourceName = resourceName,
                    userMessage = "音频资源缺失，已改为文字提示。",
                )
            } else {
                FixedAudioPlayback(played = true, resourceName = resourceName)
            }
    }
}

/** Plays only packaged raw resources and reports a recoverable result for every failure. */
class FixedAudioPlayer(private val context: Context) {
    private var current: MediaPlayer? = null

    fun playProblem(problem: Problem): FixedAudioPlayback =
        play(PromptAudioIndex.problemResourceName(problem.a, problem.b))

    fun playFeedback(feedback: FixedFeedback): FixedAudioPlayback =
        play(PromptAudioIndex.feedbackResourceName(feedback))

    fun stop() {
        current?.runCatching { stop() }
        current?.release()
        current = null
    }

    private fun play(resourceName: String): FixedAudioPlayback {
        stop()
        val resourceId = context.resources.getIdentifier(resourceName, "raw", context.packageName)
        val resolution = FixedAudioPlayback.resolve(resourceId, resourceName)
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
            resolution.copy(played = false, userMessage = "音频播放失败，已改为文字提示。")
        }
    }
}
