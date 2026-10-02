package com.example.multiplicationcoach

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs SenseVoice and Silero VAD fully on the device against bundled, verified assets. */
class LocalAsrEngine {
    private val inferenceLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    @Synchronized
    fun initialize(context: Context): Result<Unit> = runCatching {
        val (_, availability) = OfflineAsrAssets.install(context)
        check(availability.available) { availability.reason ?: "离线识别不可用" }

        recognizer?.release()
        vad?.release()

        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "asr/sensevoice/model.int8.onnx",
                language = "zh",
                useInverseTextNormalization = true,
            ),
            tokens = "asr/sensevoice/tokens.txt",
            numThreads = 2,
        )
        recognizer = OfflineRecognizer(
            context.assets,
            OfflineRecognizerConfig(modelConfig = modelConfig),
        )
        vad = Vad(
            context.assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "asr/vad/silero_vad.int8.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.12f,
                    windowSize = 512,
                    maxSpeechDuration = 12f,
                ),
                sampleRate = PCM_SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    suspend fun transcribe(pcm: ByteArray, expectedAnswer: Int): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            if (pcm.size < 2) return@runCatching ""
            val samples = pcm.toPcmFloatArray()
            synchronized(inferenceLock) {
                val currentVad = checkNotNull(vad) { "离线识别尚未初始化" }
                val currentRecognizer = checkNotNull(recognizer) { "离线识别尚未初始化" }
                currentVad.reset()
                currentVad.acceptWaveform(samples)
                currentVad.flush()

                val vadTranscript = buildList {
                    while (!currentVad.empty()) {
                        val segment = currentVad.front()
                        currentVad.pop()
                        if (segment.samples.isEmpty()) continue
                        val stream = currentRecognizer.createStream()
                        try {
                            stream.acceptWaveform(segment.samples, PCM_SAMPLE_RATE)
                            currentRecognizer.decode(stream)
                            currentRecognizer.getResult(stream).text.trim().takeIf(String::isNotEmpty)?.let(::add)
                        } finally {
                            stream.release()
                        }
                    }
                }.joinToString(separator = "")
                // A short digit answer can be cut by VAD into a plausible but wrong
                // syllable (for example, "二十一" becoming "是"). Decode both the
                // VAD segment and the complete answer window, then select the result
                // that best matches this multiplication question's closed answer set.
                val fullRecordingTranscript = currentRecognizer.decodeWholeRecording(samples)
                LocalAsrTranscriptFallback.choose(
                    vadTranscript = vadTranscript,
                    fullRecordingTranscript = fullRecordingTranscript,
                    expectedAnswer = expectedAnswer,
                )
            }
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
    }

    private fun ByteArray.toPcmFloatArray(): FloatArray = FloatArray(size / 2) { index ->
        val offset = index * 2
        val sample = ((this[offset + 1].toInt() shl 8) or (this[offset].toInt() and 0xff)).toShort()
        sample / 32768f
    }

    private fun OfflineRecognizer.decodeWholeRecording(samples: FloatArray): String {
        val stream = createStream()
        try {
            stream.acceptWaveform(samples, PCM_SAMPLE_RATE)
            decode(stream)
            return getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val PCM_SAMPLE_RATE = 16_000
    }
}

/** Keeps short valid answers recognisable when voice activity detection yields no segment. */
object LocalAsrTranscriptFallback {
    fun choose(
        vadTranscript: String,
        fullRecordingTranscript: String,
        expectedAnswer: Int? = null,
    ): String {
        val vadAnswer = AnswerTranscriptParser.parse(vadTranscript)
        val fullAnswer = AnswerTranscriptParser.parse(fullRecordingTranscript)
        return when {
            expectedAnswer != null && vadAnswer == expectedAnswer -> vadTranscript
            expectedAnswer != null && fullAnswer == expectedAnswer -> fullRecordingTranscript
            vadAnswer != null -> vadTranscript
            fullAnswer != null -> fullRecordingTranscript
            else -> vadTranscript.ifBlank { fullRecordingTranscript }
        }
    }
}
