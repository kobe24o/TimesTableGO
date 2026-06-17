package com.example.multiplicationcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MultiplicationTheme {
                MultiplicationApp()
            }
        }
    }
}

data class Problem(val a: Int, val b: Int) {
    val answer: Int = a * b
    val label: String = "$a x $b"
    val prompt: String = "$a 乘以 $b 等于多少"
}

data class Attempt(
    val a: Int,
    val b: Int,
    val expected: Int,
    val transcript: String,
    val extractedAnswer: Int?,
    val correct: Boolean,
    val checkedBy: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val problemLabel: String = "$a x $b"
}

data class PracticeSession(
    val total: Int,
    val correct: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    val perfect: Boolean = total > 0 && total == correct
}

data class AppSettings(
    val ttsEnabled: Boolean = true,
    val asrProvider: String = "system",
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val azureSpeechKey: String = "",
    val azureEndpoint: String = "https://asia25.cognitiveservices.azure.com",
    val azureRegion: String = "eastasia",
    val llmEnabled: Boolean = false,
    val apiBase: String = "https://maas-api.cn-huabei-1.xf-yun.com/v2",
    val modelId: String = "qwen3.6-35b-a3b",
    val apiKey: String = "",
    val masteryStreakTarget: Int = 5
)

data class UiState(
    val running: Boolean = false,
    val problem: Problem? = null,
    val phase: String = "准备开始",
    val countdown: Int = 0,
    val transcript: String = "",
    val feedback: String = "点击开始，系统会随机出一道 1-9 乘法题。",
    val attempts: List<Attempt> = emptyList(),
    val sessions: List<PracticeSession> = emptyList(),
    val sessionAnswered: Int = 0,
    val sessionCorrect: Int = 0,
    val congratsMessage: String = "",
    val showConfetti: Boolean = false,
    val teacherMessage: String = "",
    val teacherMessageLoading: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val hasAudioPermission: Boolean = false
)

class PracticeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PracticePrefs(application)
    private val verifier = AnswerVerifier()
    private val baiduAsr = BaiduAsrClient(application)
    private val azureAsr = AzureSpeechClient()
    private val speaker = BaiduTtsSpeaker(application)
    private var recognizer: SpeechRecognizer? = null
    private var sessionJob: Job? = null
    private var currentProblem: Problem? = null
    private var sessionStartedAt: Long = 0L
    private var sessionAnswered: Int = 0
    private var sessionCorrect: Int = 0
    private var dueProblems: MutableList<Problem> = mutableListOf()
    private var celebratedMasteryThreshold: Int = prefs.loadCelebratedMasteryThreshold()
    private var stopped = false

    private val _state = MutableStateFlow(
        UiState(
            attempts = prefs.loadAttempts(),
            sessions = prefs.loadSessions(),
            settings = prefs.loadSettings(),
            hasAudioPermission = hasAudioPermission(application)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refreshPermission() {
        _state.value = _state.value.copy(hasAudioPermission = hasAudioPermission(getApplication()))
    }

    fun start() {
        if (_state.value.running) return
        stopped = false
        sessionStartedAt = System.currentTimeMillis()
        sessionAnswered = 0
        sessionCorrect = 0
        dueProblems = ALL_PROBLEMS.toMutableList()
        _state.value = _state.value.copy(
            running = true,
            sessionAnswered = 0,
            sessionCorrect = 0,
            feedback = "练习开始：前 81 题会覆盖整张乘法表，最多练 100 题。"
        )
        sessionJob = viewModelScope.launch {
            while (!stopped && sessionAnswered < MAX_SESSION_PROBLEMS) {
                askOneProblem()
                delay(900)
            }
            if (!stopped && sessionAnswered >= MAX_SESSION_PROBLEMS) {
                finishSession("本次 100 题练习完成，已经保存到成就统计。")
            }
        }
    }

    fun stop() {
        stopped = true
        sessionJob?.cancel()
        recognizer?.cancel()
        speaker.stop()
        finishSession("已停止，本次统计已保存。")
    }

    fun updateSettings(settings: AppSettings) {
        val normalized = settings.copy(masteryStreakTarget = settings.masteryStreakTarget.coerceIn(1, 20))
        prefs.saveSettings(normalized)
        _state.value = _state.value.copy(settings = normalized)
        viewModelScope.launch {
            maybeCelebrateMastery(_state.value.attempts)
        }
    }

    fun clearHistory() {
        prefs.saveAttempts(emptyList())
        prefs.saveSessions(emptyList())
        prefs.saveCelebratedMasteryThreshold(0)
        celebratedMasteryThreshold = 0
        _state.value = _state.value.copy(
            attempts = emptyList(),
            sessions = emptyList(),
            sessionAnswered = 0,
            sessionCorrect = 0,
            congratsMessage = "",
            showConfetti = false,
            teacherMessage = "",
            teacherMessageLoading = false,
            feedback = "历史记录已清空。"
        )
    }

    fun requestTeacherMessage() {
        val snapshot = _state.value
        val settings = snapshot.settings
        if (snapshot.teacherMessageLoading) return
        if (snapshot.attempts.isEmpty()) {
            _state.value = snapshot.copy(teacherMessage = "还没有答题记录。先练几题，老师就能写出更贴合你的鼓励寄语。")
            return
        }
        if (settings.apiKey.isBlank()) {
            val fallback = "请先在设置里填写大模型 API Key。老师寄语会把统计摘要发给配置的大模型生成。"
            _state.value = snapshot.copy(teacherMessage = fallback)
            viewModelScope.launch { speakWithBaidu(fallback) }
            return
        }

        _state.value = snapshot.copy(teacherMessageLoading = true, teacherMessage = "老师正在看你的练习记录...")
        viewModelScope.launch {
            val summary = buildTeacherStatsSummary(snapshot.attempts, snapshot.sessions, settings.masteryStreakTarget)
            val message = verifier.generateTeacherMessage(settings, summary).getOrElse { error ->
                "老师寄语生成失败：${error.message.orEmpty()}。请检查 API Base、Model ID 和 API Key。"
            }
            _state.value = _state.value.copy(teacherMessageLoading = false, teacherMessage = message)
            speakWithBaidu(message)
        }
    }

    private suspend fun askOneProblem() {
        val problem = nextProblem()
        currentProblem = problem
        _state.value = _state.value.copy(
            problem = problem,
            phase = "读题",
            transcript = "",
            countdown = 3,
            feedback = "${problem.label} = ?"
        )

        if (_state.value.settings.ttsEnabled) {
            speakWithBaidu(problem.prompt)
        }

        if (stopped) return

        if (!_state.value.hasAudioPermission) {
            recordAttempt(problem, "", null, false, "local", "缺少麦克风权限，无法语音作答。正确答案：${problem.answer}")
            return
        }

        _state.value = _state.value.copy(phase = "请作答", feedback = "请在 3 秒内说出答案。")
        val speechJob = viewModelScope.launch {
            for (second in 3 downTo 1) {
                _state.value = _state.value.copy(countdown = second)
                delay(1000)
            }
            _state.value = _state.value.copy(countdown = 0)
        }

        val transcript = listenForAnswer(_state.value.settings)
        speechJob.cancel()

        if (stopped) return

        _state.value = _state.value.copy(
            phase = "判题中",
            countdown = 0,
            transcript = transcript.ifBlank { "未识别到语音" },
            feedback = "正在校验答案..."
        )

        val localAnswer = extractNumber(transcript)
        val settings = _state.value.settings
        val result = if (settings.llmEnabled && settings.apiKey.isNotBlank()) {
            verifier.verifyWithLlm(problem, transcript, settings).getOrElse {
                VerificationResult(localAnswer == problem.answer, localAnswer, "local", "云端校验失败，已使用本地数字校验。")
            }
        } else {
            VerificationResult(localAnswer == problem.answer, localAnswer, "local", null)
        }

        val message = if (result.correct) {
            "答对了：${problem.label} = ${problem.answer}"
        } else {
            val heard = result.extractedAnswer?.toString() ?: "未识别"
            "答错了，识别为 $heard；正确答案：${problem.answer}"
        }
        if (result.correct) {
            speakWithBaidu(CORRECT_REPLIES.random())
        } else {
            speakWithBaidu("${problem.a} ${problem.b} ${problem.answer}")
        }
        recordAttempt(problem, transcript, result.extractedAnswer, result.correct, result.checkedBy, result.note ?: message)
    }

    private suspend fun speakWithBaidu(text: String) {
        val settings = _state.value.settings
        if (!settings.ttsEnabled) return
        if (settings.baiduApiKey.isBlank() || settings.baiduSecretKey.isBlank()) {
            _state.value = _state.value.copy(feedback = "请先在设置里填写百度语音 API Key 和 Secret Key。")
            return
        }
        speaker.speak(text, settings).onFailure {
            _state.value = _state.value.copy(feedback = "百度 TTS 播放失败：${it.message.orEmpty()}")
        }
    }

    private suspend fun listenForAnswer(settings: AppSettings): String {
        return if (
            settings.asrProvider == ASR_BAIDU &&
            settings.baiduApiKey.isNotBlank() &&
            settings.baiduSecretKey.isNotBlank()
        ) {
            listenWithBaidu(settings)
        } else if (
            settings.asrProvider == ASR_AZURE &&
            settings.azureSpeechKey.isNotBlank() &&
            (settings.azureEndpoint.isNotBlank() || settings.azureRegion.isNotBlank())
        ) {
            listenWithAzure(settings)
        } else {
            listenWithSystemRecognizer()
        }
    }

    private suspend fun listenWithBaidu(settings: AppSettings): String {
        _state.value = _state.value.copy(transcript = "正在录音...")
        val pcm = PcmRecorder.recordThreeSeconds()
        if (stopped) return ""
        _state.value = _state.value.copy(transcript = "百度识别中...")
        return baiduAsr.transcribe(pcm, settings).getOrElse {
            _state.value = _state.value.copy(feedback = "百度识别失败，已按未识别处理：${it.message.orEmpty()}")
            ""
        }
    }

    private suspend fun listenWithAzure(settings: AppSettings): String {
        _state.value = _state.value.copy(transcript = "正在录音...")
        val pcm = PcmRecorder.recordThreeSeconds()
        if (stopped) return ""
        _state.value = _state.value.copy(transcript = "Azure 识别中...")
        return azureAsr.transcribe(pcm, settings).getOrElse {
            _state.value = _state.value.copy(feedback = "Azure 识别失败，已按未识别处理：${it.message.orEmpty()}")
            ""
        }
    }

    private suspend fun listenWithSystemRecognizer(): String = suspendCancellableCoroutine { continuation ->
        recognizer?.destroy()
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
        recognizer = speechRecognizer
        var resumed = false

        fun finish(value: String) {
            if (!resumed) {
                resumed = true
                speechRecognizer.stopListening()
                continuation.resume(value)
            }
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _state.value = _state.value.copy(transcript = partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = finish("")
            override fun onResults(results: Bundle?) {
                val answer = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                finish(answer)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
        speechRecognizer.startListening(intent)

        viewModelScope.launch {
            delay(3200)
            finish(_state.value.transcript)
        }

        continuation.invokeOnCancellation {
            speechRecognizer.cancel()
        }
    }

    private fun recordAttempt(
        problem: Problem,
        transcript: String,
        extractedAnswer: Int?,
        correct: Boolean,
        checkedBy: String,
        feedback: String
    ) {
        val attempt = Attempt(
            a = problem.a,
            b = problem.b,
            expected = problem.answer,
            transcript = transcript,
            extractedAnswer = extractedAnswer,
            correct = correct,
            checkedBy = checkedBy
        )
        val attempts = (listOf(attempt) + _state.value.attempts).take(300)
        sessionAnswered += 1
        if (correct) sessionCorrect += 1
        prefs.saveAttempts(attempts)
        _state.value = _state.value.copy(
            attempts = attempts,
            sessionAnswered = sessionAnswered,
            sessionCorrect = sessionCorrect,
            phase = if (correct) "答对" else "订正",
            feedback = feedback
        )
        if (correct) {
            viewModelScope.launch {
                maybeCelebrateMastery(attempts)
            }
        }
    }

    private suspend fun maybeCelebrateMastery(attempts: List<Attempt>) {
        val settings = _state.value.settings
        val threshold = settings.masteryStreakTarget.coerceIn(1, 20)
        if (threshold <= celebratedMasteryThreshold) return
        if (!hasMasteredAllProblems(attempts, threshold)) return

        celebratedMasteryThreshold = threshold
        prefs.saveCelebratedMasteryThreshold(threshold)
        val message = generateMasteryMessage(settings, threshold)
        _state.value = _state.value.copy(
            congratsMessage = message,
            showConfetti = true,
            phase = "任务达成",
            feedback = "恭喜你攻克乘法口诀背诵任务！"
        )
        speakWithBaidu(message)
        delay(7000)
        _state.value = _state.value.copy(showConfetti = false)
    }

    private suspend fun generateMasteryMessage(settings: AppSettings, threshold: Int): String {
        val fallback = "恭喜你攻克乘法口诀背诵任务！每一道乘法题都连续答对了 $threshold 次。你已经把九九乘法表练得又稳又快，继续保持这个节奏。"
        if (!settings.llmEnabled || settings.apiKey.isBlank()) return fallback
        return verifier.generateMasteryCongrats(settings, threshold).getOrDefault(fallback)
    }

    private fun finishSession(feedback: String) {
        val existingSessions = _state.value.sessions
        val sessions = if (sessionAnswered > 0) {
            val summary = PracticeSession(
                total = sessionAnswered.coerceAtMost(MAX_SESSION_PROBLEMS),
                correct = sessionCorrect.coerceAtMost(sessionAnswered),
                timestamp = sessionStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            (listOf(summary) + existingSessions).take(100)
        } else {
            existingSessions
        }
        prefs.saveSessions(sessions)
        _state.value = _state.value.copy(
            running = false,
            countdown = 0,
            phase = "已停止",
            sessions = sessions,
            feedback = feedback
        )
        sessionAnswered = 0
        sessionCorrect = 0
        dueProblems.clear()
    }

    override fun onCleared() {
        recognizer?.destroy()
        speaker.shutdown()
        super.onCleared()
    }

    private fun nextProblem(): Problem {
        val last = currentProblem
        if (sessionAnswered < ALL_PROBLEMS.size) {
            if (dueProblems.isEmpty()) dueProblems = ALL_PROBLEMS.toMutableList()
            return pickWeightedProblem(dueProblems, last).also { dueProblems.remove(it) }
        }
        return pickWeightedProblem(ALL_PROBLEMS, last)
    }

    private fun pickWeightedProblem(candidates: List<Problem>, last: Problem?): Problem {
        val choices = if (candidates.size > 1 && last != null) candidates.filterNot { it == last } else candidates
        val weighted = choices.flatMap { problem -> List(problemWeight(problem)) { problem } }
        return weighted.randomOrNull() ?: choices.random()
    }

    private fun problemWeight(problem: Problem): Int {
        val rows = _state.value.attempts.filter { it.a == problem.a && it.b == problem.b }
        val wrongCount = rows.count { !it.correct }
        val recentWrongBonus = rows.firstOrNull()?.takeIf { !it.correct }?.let { 4 } ?: 0
        val neverPracticedBonus = if (rows.isEmpty()) 1 else 0
        return (1 + wrongCount * 3 + recentWrongBonus + neverPracticedBonus).coerceIn(1, 20)
    }

    private fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

data class VerificationResult(
    val correct: Boolean,
    val extractedAnswer: Int?,
    val checkedBy: String,
    val note: String?
)

class AnswerVerifier {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun verifyWithLlm(problem: Problem, transcript: String, settings: AppSettings): Result<VerificationResult> {
        val apiBase = settings.apiBase.trim().trimEnd('/')
        val endpoint = "$apiBase/chat/completions"
        val body = JSONObject()
            .put("model", settings.modelId)
            .put("temperature", 0)
            .put("max_tokens", 80)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你只做小学乘法口算判题。请从学生语音转写里提取一个整数答案，并严格输出 JSON：{\"answer\":数字或null,\"correct\":true或false}。"))
                    .put(JSONObject().put("role", "user").put("content", "题目：${problem.a} x ${problem.b}。正确答案：${problem.answer}。学生语音转写：$transcript"))
            )

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw IOException("LLM HTTP ${response.code}")
            }
            val responseBody = response.body?.string().orEmpty()
            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            parseVerification(content, problem)
        }
    }

    suspend fun generateMasteryCongrats(settings: AppSettings, threshold: Int): Result<String> {
        val apiBase = settings.apiBase.trim().trimEnd('/')
        val endpoint = "$apiBase/chat/completions"
        val body = JSONObject()
            .put("model", settings.modelId)
            .put("temperature", 0.8)
            .put("max_tokens", 120)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是一位温暖、有鼓励感的小学数学教练。只输出一段中文祝贺语，适合语音播报，60字以内，不要 Markdown。"))
                    .put(JSONObject().put("role", "user").put("content", "孩子已经把 1 到 9 的 81 个乘法组合全部连续答对至少 $threshold 次。请祝贺孩子攻克乘法口诀背诵任务，并鼓励继续保持。"))
            )

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) throw IOException("LLM HTTP ${response.code}")
            val responseBody = response.body?.string().orEmpty()
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .take(160)
        }
    }

    suspend fun generateTeacherMessage(settings: AppSettings, statsSummary: String): Result<String> {
        val apiBase = settings.apiBase.trim().trimEnd('/')
        val endpoint = "$apiBase/chat/completions"
        val body = JSONObject()
            .put("model", settings.modelId)
            .put("temperature", 0.7)
            .put("max_tokens", 180)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是一位小学数学老师。根据学生乘法口诀练习统计，写一段正向、具体、温暖的中文寄语，80字以内，适合朗读。先肯定进步，再指出一个小目标，不批评、不羞辱，不要 Markdown。"))
                    .put(JSONObject().put("role", "user").put("content", statsSummary))
            )

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) throw IOException("LLM HTTP ${response.code}")
            val responseBody = response.body?.string().orEmpty()
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .take(220)
        }
    }

    private fun parseVerification(content: String, problem: Problem): VerificationResult {
        val jsonText = Regex("\\{[\\s\\S]*}").find(content)?.value ?: content
        val json = JSONObject(jsonText)
        val extracted = if (json.isNull("answer")) null else json.optInt("answer")
        val correct = json.optBoolean("correct", extracted == problem.answer)
        return VerificationResult(correct, extracted, "llm", null)
    }
}

class BaiduAsrClient(private val context: Context) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    suspend fun transcribe(pcm: ByteArray, settings: AppSettings): Result<String> = runCatching {
        val token = getAccessToken(settings)
        val payload = JSONObject()
            .put("format", "pcm")
            .put("rate", 16000)
            .put("channel", 1)
            .put("cuid", deviceId())
            .put("token", token)
            .put("speech", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("len", pcm.size)

        val request = Request.Builder()
            .url("https://vop.baidu.com/server_api")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Baidu ASR HTTP ${response.code}")

        val json = JSONObject(body)
        val errNo = json.optInt("err_no", -1)
        if (errNo != 0) {
            throw IOException("Baidu ASR err_no=$errNo ${json.optString("err_msg")}")
        }
        json.optJSONArray("result")?.optString(0).orEmpty()
    }

    private suspend fun getAccessToken(settings: AppSettings): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAtMs }?.let { return it }
        val url = "https://aip.baidubce.com/oauth/2.0/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "client_credentials")
            .addQueryParameter("client_id", settings.baiduApiKey.trim())
            .addQueryParameter("client_secret", settings.baiduSecretKey.trim())
            .build()
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Baidu token HTTP ${response.code}")
        val json = JSONObject(body)
        val token = json.optString("access_token")
        if (token.isBlank()) throw IOException("Baidu token response missing access_token")
        cachedToken = token
        val expiresInSeconds = json.optLong("expires_in", 3600L)
        tokenExpiresAtMs = now + (expiresInSeconds - 300L).coerceAtLeast(60L) * 1000L
        return token
    }

    private fun deviceId(): String =
        "multiplication-coach-${context.packageName}"
}

class AzureSpeechClient {
    private val client = OkHttpClient()

    suspend fun transcribe(pcm: ByteArray, settings: AppSettings): Result<String> = runCatching {
        val host = settings.azureEndpoint.trim().trimEnd('/').ifBlank {
            "https://${settings.azureRegion.trim()}.stt.speech.microsoft.com"
        }
        val endpoint = "$host/stt/speech/recognition/conversation/cognitiveservices/v1?language=zh-CN&format=detailed"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Ocp-Apim-Subscription-Key", settings.azureSpeechKey.trim())
            .addHeader("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
            .addHeader("Accept", "application/json")
            .post(PcmRecorder.toWav(pcm).toRequestBody("audio/wav".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Azure Speech HTTP ${response.code}")
        val json = JSONObject(body)
        json.optString("DisplayText")
            .ifBlank {
                json.optJSONArray("NBest")
                    ?.optJSONObject(0)
                    ?.optString("Display")
                    .orEmpty()
            }
    }
}

object PcmRecorder {
    private const val SAMPLE_RATE = 16000

    @SuppressLint("MissingPermission")
    suspend fun recordThreeSeconds(): ByteArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize, SAMPLE_RATE)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        val targetBytes = SAMPLE_RATE * 2 * 3
        val output = ByteArray(targetBytes)
        var offset = 0
        val buffer = ByteArray(bufferSize)
        try {
            audioRecord.startRecording()
            while (offset < targetBytes) {
                val read = audioRecord.read(buffer, 0, minOf(buffer.size, targetBytes - offset))
                if (read > 0) {
                    buffer.copyInto(output, offset, 0, read)
                    offset += read
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
        output
    }

    fun toWav(pcm: ByteArray): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = SAMPLE_RATE * 2
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        putString(0, "RIFF")
        putInt(4, totalDataLen)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, byteRate)
        putShort(32, 2)
        putShort(34, 16)
        putString(36, "data")
        putInt(40, pcm.size)
        return header + pcm
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

class BaiduTtsSpeaker(private val context: Context) {
    private val client = OkHttpClient()
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L
    private var currentPlayer: MediaPlayer? = null

    suspend fun speak(text: String, settings: AppSettings): Result<Unit> = runCatching {
        val audioFile = audioFileFor(text, settings)
        play(audioFile)
    }

    private suspend fun audioFileFor(text: String, settings: AppSettings): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "baidu_tts").apply { mkdirs() }
        val target = File(cacheDir, "${cacheKey(text)}.mp3")
        if (target.exists() && target.length() > 0L) return@withContext target

        val token = getAccessToken(settings)
        val body = FormBody.Builder()
            .add("tex", text)
            .add("tok", token)
            .add("cuid", deviceId())
            .add("ctp", "1")
            .add("lan", "zh")
            .add("spd", "5")
            .add("pit", "5")
            .add("vol", "7")
            .add("per", "0")
            .add("aue", "3")
            .build()
        val request = Request.Builder()
            .url("https://tsn.baidu.com/text2audio")
            .post(body)
            .build()

        val bytes = client.newCall(request).await().use { response ->
            val contentType = response.body?.contentType()?.toString().orEmpty()
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw IOException("Baidu TTS HTTP ${response.code}")
            }
            if (contentType.contains("json", ignoreCase = true) || responseBytes.firstOrNull() == '{'.code.toByte()) {
                val errorBody = responseBytes.toString(Charsets.UTF_8)
                val json = runCatching { JSONObject(errorBody) }.getOrNull()
                throw IOException(json?.optString("err_msg")?.ifBlank { errorBody } ?: errorBody)
            }
            responseBytes
        }

        val temp = File.createTempFile("tts-", ".mp3", cacheDir)
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        target
    }

    private suspend fun getAccessToken(settings: AppSettings): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAtMs }?.let { return it }
        val url = "https://aip.baidubce.com/oauth/2.0/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "client_credentials")
            .addQueryParameter("client_id", settings.baiduApiKey.trim())
            .addQueryParameter("client_secret", settings.baiduSecretKey.trim())
            .build()
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Baidu token HTTP ${response.code}")
        val json = JSONObject(body)
        val token = json.optString("access_token")
        if (token.isBlank()) throw IOException("Baidu token response missing access_token")
        cachedToken = token
        val expiresInSeconds = json.optLong("expires_in", 3600L)
        tokenExpiresAtMs = now + (expiresInSeconds - 300L).coerceAtLeast(60L) * 1000L
        return token
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        stop()
        val player = MediaPlayer()
        currentPlayer = player
        fun finish() {
            if (currentPlayer == player) currentPlayer = null
            runCatching { player.release() }
        }
        player.setOnCompletionListener {
            finish()
            if (continuation.isActive) continuation.resume(Unit)
        }
        player.setOnErrorListener { _, what, extra ->
            finish()
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(IOException("MediaPlayer error what=$what extra=$extra")))
            }
            true
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
        } catch (error: Exception) {
            finish()
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
        continuation.invokeOnCancellation {
            if (currentPlayer == player) currentPlayer = null
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    fun stop() {
        currentPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        currentPlayer = null
    }

    fun shutdown() = stop()

    private fun cacheKey(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun deviceId(): String =
        "multiplication-coach-${context.packageName}"
}

class PracticePrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("practice", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings = AppSettings(
        ttsEnabled = prefs.getBoolean("ttsEnabled", true),
        asrProvider = prefs.getString("asrProvider", "system").orEmpty(),
        baiduApiKey = prefs.getString("baiduApiKey", "").orEmpty(),
        baiduSecretKey = prefs.getString("baiduSecretKey", "").orEmpty(),
        azureSpeechKey = prefs.getString("azureSpeechKey", "").orEmpty(),
        azureEndpoint = prefs.getString("azureEndpoint", "https://asia25.cognitiveservices.azure.com").orEmpty(),
        azureRegion = prefs.getString("azureRegion", "eastasia").orEmpty(),
        llmEnabled = prefs.getBoolean("llmEnabled", false),
        apiBase = prefs.getString("apiBase", "https://maas-api.cn-huabei-1.xf-yun.com/v2").orEmpty(),
        modelId = prefs.getString("modelId", "qwen3.6-35b-a3b").orEmpty(),
        apiKey = prefs.getString("apiKey", "").orEmpty(),
        masteryStreakTarget = prefs.getInt("masteryStreakTarget", 5).coerceIn(1, 20)
    )

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean("ttsEnabled", settings.ttsEnabled)
            .putString("asrProvider", settings.asrProvider)
            .putString("baiduApiKey", settings.baiduApiKey)
            .putString("baiduSecretKey", settings.baiduSecretKey)
            .putString("azureSpeechKey", settings.azureSpeechKey)
            .putString("azureEndpoint", settings.azureEndpoint)
            .putString("azureRegion", settings.azureRegion)
            .putBoolean("llmEnabled", settings.llmEnabled)
            .putString("apiBase", settings.apiBase)
            .putString("modelId", settings.modelId)
            .putString("apiKey", settings.apiKey)
            .putInt("masteryStreakTarget", settings.masteryStreakTarget.coerceIn(1, 20))
            .apply()
    }

    fun loadCelebratedMasteryThreshold(): Int =
        prefs.getInt("celebratedMasteryThreshold", 0)

    fun saveCelebratedMasteryThreshold(threshold: Int) {
        prefs.edit().putInt("celebratedMasteryThreshold", threshold.coerceAtLeast(0)).apply()
    }

    fun loadAttempts(): List<Attempt> {
        val raw = prefs.getString("attempts", "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Attempt(
                    a = item.getInt("a"),
                    b = item.getInt("b"),
                    expected = item.getInt("expected"),
                    transcript = item.optString("transcript"),
                    extractedAnswer = if (item.isNull("extractedAnswer")) null else item.getInt("extractedAnswer"),
                    correct = item.getBoolean("correct"),
                    checkedBy = item.optString("checkedBy", "local"),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveAttempts(attempts: List<Attempt>) {
        val array = JSONArray()
        attempts.forEach { attempt ->
            array.put(
                JSONObject()
                    .put("a", attempt.a)
                    .put("b", attempt.b)
                    .put("expected", attempt.expected)
                    .put("transcript", attempt.transcript)
                    .put("extractedAnswer", attempt.extractedAnswer)
                    .put("correct", attempt.correct)
                    .put("checkedBy", attempt.checkedBy)
                    .put("timestamp", attempt.timestamp)
            )
        }
        prefs.edit().putString("attempts", array.toString()).apply()
    }

    fun loadSessions(): List<PracticeSession> {
        val raw = prefs.getString("sessions", "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PracticeSession(
                    total = item.optInt("total", 0),
                    correct = item.optInt("correct", 0),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }.filter { it.total > 0 }
        }.getOrDefault(emptyList())
    }

    fun saveSessions(sessions: List<PracticeSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("total", session.total)
                    .put("correct", session.correct)
                    .put("timestamp", session.timestamp)
            )
        }
        prefs.edit().putString("sessions", array.toString()).apply()
    }
}

fun extractNumber(text: String): Int? {
    if ('十' in text) {
        chineseNumberToInt(text)?.let { return it }
    }
    Regex("\\d+").find(text)?.value?.toIntOrNull()?.let { return it }
    val normalized = text
        .replace("零", "0")
        .replace("〇", "0")
        .replace("一", "1")
        .replace("二", "2")
        .replace("两", "2")
        .replace("三", "3")
        .replace("四", "4")
        .replace("五", "5")
        .replace("六", "6")
        .replace("七", "7")
        .replace("八", "8")
        .replace("九", "9")
    Regex("\\d+").find(normalized)?.value?.toIntOrNull()?.let { return it }
    return chineseNumberToInt(text)
}

fun chineseNumberToInt(text: String): Int? {
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val tenIndex = text.indexOf('十')
    if (tenIndex >= 0) {
        val tens = text.getOrNull(tenIndex - 1)?.let { digits[it] } ?: 1
        val ones = text.getOrNull(tenIndex + 1)?.let { digits[it] } ?: 0
        return tens * 10 + ones
    }
    return text.firstNotNullOfOrNull { digits[it] }
}

const val ASR_SYSTEM = "system"
const val ASR_BAIDU = "baidu"
const val ASR_AZURE = "azure"
const val MAX_SESSION_PROBLEMS = 100

private val ALL_PROBLEMS: List<Problem> = (1..9).flatMap { a ->
    (1..9).map { b -> Problem(a, b) }
}

private val CORRECT_REPLIES = listOf(
    "答对了",
    "很好，答对了",
    "真棒，答对了",
    "完全正确"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplicationApp(viewModel: PracticeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.Practice) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermission()
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("乘法口诀背诵") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F7F2))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = tab == AppTab.Practice,
                    onClick = { tab = AppTab.Practice },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("练习") }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Stats,
                    onClick = { tab = AppTab.Stats },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("统计") }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Settings,
                    onClick = { tab = AppTab.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF7F7F2))
                .padding(padding)
        ) {
            when (tab) {
                AppTab.Practice -> PracticeScreen(state, viewModel::start, viewModel::stop)
                AppTab.Stats -> StatsScreen(state, viewModel::clearHistory, viewModel::requestTeacherMessage)
                AppTab.Settings -> SettingsScreen(state.settings, viewModel::updateSettings)
            }
        }
    }
}

enum class AppTab { Practice, Stats, Settings }

@Composable
fun PracticeScreen(state: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusCard(state)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = onStart,
                enabled = !state.running,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF246B45)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始")
            }
            Button(
                onClick = onStop,
                enabled = state.running,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB23A48)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止")
            }
        }
        if (!state.hasAudioPermission) {
            Text("需要麦克风权限才能语音作答。", color = Color(0xFFB23A48), textAlign = TextAlign.Center)
        }
        RecentAttempts(state.attempts.take(5))
    }
}

@Composable
fun StatusCard(state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(state.phase, color = Color(0xFF6C6F75), fontSize = 14.sp)
            Text(
                text = state.problem?.let { "${it.a} x ${it.b} = ?" } ?: "准备好了吗？",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF182027),
                textAlign = TextAlign.Center
            )
            if (state.countdown > 0) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color(0xFFE9F1EC), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${state.countdown}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF246B45))
                }
            }
            Text(state.feedback, textAlign = TextAlign.Center, color = Color(0xFF29323A))
            if (state.congratsMessage.isNotBlank()) {
                Surface(color = Color(0xFFE9F1EC), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        state.congratsMessage,
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFF246B45),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (state.showConfetti) {
                ConfettiCelebration()
            }
            if (state.running || state.sessionAnswered > 0) {
                Text(
                    "本局 ${state.sessionAnswered}/$MAX_SESSION_PROBLEMS 题，答对 ${state.sessionCorrect} 题；前 81 题覆盖整张九九表",
                    color = Color(0xFF59636E),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
            if (state.transcript.isNotBlank()) {
                Text("识别：${state.transcript}", color = Color(0xFF59636E), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ConfettiCelebration() {
    var tick by remember { mutableStateOf(0) }
    val pieces = remember {
        List(52) { index ->
            Triple(
                Random.nextFloat(),
                Random.nextFloat(),
                (index % 5) + 2
            )
        }
    }
    LaunchedEffect(Unit) {
        repeat(90) {
            delay(70)
            tick += 1
        }
    }
    val colors = listOf(
        Color(0xFF246B45),
        Color(0xFF2F6F8F),
        Color(0xFFE6C65A),
        Color(0xFFB23A48),
        Color(0xFF7A5CDE)
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Color(0xFFFFFBEC), RoundedCornerShape(8.dp))
    ) {
        pieces.forEachIndexed { index, piece ->
            val x = piece.first * size.width
            val y = ((piece.second * size.height) + tick * piece.third * 2.8f) % size.height
            val radius = 4f + (index % 3) * 2f
            drawCircle(colors[index % colors.size], radius = radius, center = Offset(x, y))
        }
    }
}

@Composable
fun RecentAttempts(attempts: List<Attempt>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("最近记录", fontWeight = FontWeight.SemiBold, color = Color(0xFF29323A))
        if (attempts.isEmpty()) {
            Text("还没有答题记录。", color = Color(0xFF7A8188))
        } else {
            attempts.forEach { attempt ->
                AttemptRow(attempt)
            }
        }
    }
}

@Composable
fun AttemptRow(attempt: Attempt) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("${attempt.problemLabel} = ${attempt.expected}", fontWeight = FontWeight.SemiBold)
            Text("作答：${attempt.transcript.ifBlank { "未识别" }}", color = Color(0xFF69727C), fontSize = 13.sp)
        }
        Text(if (attempt.correct) "正确" else "订正", color = if (attempt.correct) Color(0xFF246B45) else Color(0xFFB23A48), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatsScreen(state: UiState, onClear: () -> Unit, onTeacherMessage: () -> Unit) {
    val attempts = state.attempts
    val total = attempts.size
    val correct = attempts.count { it.correct }
    val accuracy = if (total == 0) 0 else (correct * 100 / total)
    val coverage = attempts.map { it.a to it.b }.toSet().size
    val achievements = buildAchievements(attempts, state.sessions)
    val target = state.settings.masteryStreakTarget.coerceIn(1, 20)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("总题数", total.toString(), Modifier.weight(1f))
                MetricCard("正确率", "$accuracy%", Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("老师寄语", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
                            Text("发送当前答题统计给配置的大模型，生成鼓励和小目标。", color = Color(0xFF6C6F75), fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onTeacherMessage,
                            enabled = attempts.isNotEmpty() && !state.teacherMessageLoading,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF246B45))
                        ) {
                            Text(if (state.teacherMessageLoading) "生成中" else "老师寄语")
                        }
                    }
                    if (state.teacherMessage.isNotBlank()) {
                        Surface(color = Color(0xFFE9F1EC), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                state.teacherMessage,
                                modifier = Modifier.padding(14.dp),
                                color = Color(0xFF29323A),
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("九九表覆盖", "$coverage/81", Modifier.weight(1f))
                MetricCard("练习局数", state.sessions.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            MistakeMatrix(attempts, target)
        }
        item {
            Text("学习成就", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
        }
        items(achievements) { achievement ->
            AchievementCard(achievement)
        }
        item {
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("正确率曲线", fontWeight = FontWeight.Bold)
                    AccuracyChart(attempts.take(30).reversed())
                }
            }
        }
        item {
            Text("高频错题", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
        }
        val mistakes = attempts
            .filterNot { it.correct }
            .groupBy { it.problemLabel }
            .map { (label, rows) -> label to rows.size }
            .sortedByDescending { it.second }
            .take(10)
        if (mistakes.isEmpty()) {
            item { Text("暂时没有错题。", color = Color(0xFF7A8188)) }
        } else {
            items(mistakes) { (label, count) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text("$count 次", color = Color(0xFFB23A48), fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Button(
                onClick = onClear,
                enabled = attempts.isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF59636E))
            ) {
                Text("清空统计")
            }
        }
    }
}

data class Achievement(
    val title: String,
    val value: String,
    val subtitle: String,
    val unlocked: Boolean
)

data class ProblemMastery(
    val problem: Problem,
    val wrongCount: Int,
    val recentCorrectStreak: Int,
    val mastered: Boolean
)

@Composable
fun MistakeMatrix(attempts: List<Attempt>, target: Int) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("高频错题矩阵", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
            Text(
                "绿色表示掌握，红色表示错得多；最近连续答对会逐步退红。目标：每格连续答对 $target 次。",
                color = Color(0xFF6C6F75),
                fontSize = 13.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..9).forEach { a ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        (1..9).forEach { b ->
                            val mastery = problemMastery(Problem(a, b), attempts, target)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .background(masteryColor(mastery, target), RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${a}x$b",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(0xFF246B45), RoundedCornerShape(3.dp)))
                Text("已掌握", fontSize = 12.sp, color = Color(0xFF59636E))
                Box(Modifier.size(14.dp).background(Color(0xFFE6C65A), RoundedCornerShape(3.dp)))
                Text("练习中", fontSize = 12.sp, color = Color(0xFF59636E))
                Box(Modifier.size(14.dp).background(Color(0xFFB23A48), RoundedCornerShape(3.dp)))
                Text("重点复习", fontSize = 12.sp, color = Color(0xFF59636E))
            }
        }
    }
}

@Composable
fun AchievementCard(achievement: Achievement) {
    val accent = if (achievement.unlocked) Color(0xFF246B45) else Color(0xFF59636E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(achievement.title, fontWeight = FontWeight.Bold, color = Color(0xFF182027))
            Text(achievement.subtitle, color = Color(0xFF6C6F75), fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(achievement.value, color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

fun buildAchievements(attempts: List<Attempt>, sessions: List<PracticeSession>): List<Achievement> {
    val coverage = attempts.map { it.a to it.b }.toSet().size
    val currentCorrect = attempts.takeWhile { it.correct }.size
    val bestCorrect = bestCorrectStreak(attempts)
    val currentDayStreak = currentPracticeDayStreak(attempts)
    val bestDayStreak = bestPracticeDayStreak(attempts)
    val perfectSessions = sessions.takeWhile { it.perfect }.size
    val bestPerfectSessions = bestPerfectSessionStreak(sessions)
    val lastSession = sessions.firstOrNull()
    val totalCorrect = attempts.count { it.correct }
    val mistakeFixed = attempts
        .groupBy { it.a to it.b }
        .count { (_, rows) -> rows.any { !it.correct } && rows.first().correct }

    return listOf(
        Achievement(
            title = "连续练习",
            value = "${currentDayStreak}天",
            subtitle = "最高连续 ${bestDayStreak} 天；每天完成至少 1 题就算打卡。",
            unlocked = currentDayStreak >= 3
        ),
        Achievement(
            title = "连续答对",
            value = "${currentCorrect}题",
            subtitle = "历史最高连续答对 ${bestCorrect} 题。",
            unlocked = currentCorrect >= 10
        ),
        Achievement(
            title = "满分连胜",
            value = "${perfectSessions}次",
            subtitle = "一次练习最多 100 题；历史最高满分连胜 ${bestPerfectSessions} 次。",
            unlocked = perfectSessions >= 2
        ),
        Achievement(
            title = "九九表探险家",
            value = "$coverage/81",
            subtitle = if (coverage >= 81) "81 个组合都练过了。" else "还差 ${81 - coverage} 个组合就集齐整张表。",
            unlocked = coverage >= 81
        ),
        Achievement(
            title = "百题挑战",
            value = lastSession?.let { "${it.correct}/${it.total}" } ?: "0/100",
            subtitle = "完成一局 100 题练习后，这里会显示最近战绩。",
            unlocked = lastSession?.total == MAX_SESSION_PROBLEMS
        ),
        Achievement(
            title = "错题清扫",
            value = "${mistakeFixed}个",
            subtitle = "曾经答错、最近一次已经答对的题目数量。",
            unlocked = mistakeFixed >= 5
        ),
        Achievement(
            title = "正确小达人",
            value = "${totalCorrect}题",
            subtitle = "累计答对 50、100、300 题都值得庆祝。",
            unlocked = totalCorrect >= 50
        )
    )
}

fun hasMasteredAllProblems(attempts: List<Attempt>, target: Int): Boolean =
    ALL_PROBLEMS.all { problemMastery(it, attempts, target).mastered }

fun buildTeacherStatsSummary(attempts: List<Attempt>, sessions: List<PracticeSession>, target: Int): String {
    val total = attempts.size
    val correct = attempts.count { it.correct }
    val accuracy = if (total == 0) 0 else correct * 100 / total
    val coverage = attempts.map { it.a to it.b }.toSet().size
    val currentCorrect = attempts.takeWhile { it.correct }.size
    val bestCorrect = bestCorrectStreak(attempts)
    val currentDayStreak = currentPracticeDayStreak(attempts)
    val mastered = ALL_PROBLEMS.count { problemMastery(it, attempts, target).mastered }
    val recentSession = sessions.firstOrNull()?.let { "${it.correct}/${it.total}" } ?: "暂无完整练习局"
    val weakProblems = ALL_PROBLEMS
        .map { problemMastery(it, attempts, target) }
        .filter { it.wrongCount > 0 && !it.mastered }
        .sortedWith(compareByDescending<ProblemMastery> { it.wrongCount }.thenBy { it.recentCorrectStreak })
        .take(6)
        .joinToString("、") { "${it.problem.label}错${it.wrongCount}次/连对${it.recentCorrectStreak}" }
        .ifBlank { "暂无明显薄弱题" }

    return """
        学生乘法口诀练习统计：
        总答题 $total 题，答对 $correct 题，正确率 $accuracy%。
        九九表覆盖 $coverage/81，达到连续答对 $target 次的组合 $mastered/81。
        当前连续答对 $currentCorrect 题，历史最高连续答对 $bestCorrect 题。
        当前连续练习 $currentDayStreak 天，最近一局成绩：$recentSession。
        重点复习题：$weakProblems。
        请生成正向鼓励寄语，肯定努力，并给一个下一步小目标。
    """.trimIndent()
}

fun buildLocalTeacherMessage(attempts: List<Attempt>): String {
    val total = attempts.size
    val correct = attempts.count { it.correct }
    val accuracy = if (total == 0) 0 else correct * 100 / total
    val currentCorrect = attempts.takeWhile { it.correct }.size
    val weak = attempts
        .filterNot { it.correct }
        .groupBy { it.problemLabel }
        .maxByOrNull { it.value.size }
        ?.key
    return if (weak == null) {
        "你今天练得很稳，已经答了 $total 题，正确率 $accuracy%。继续保持专注，下一步试试把连续答对提高到 ${currentCorrect + 3} 题。"
    } else {
        "你已经完成 $total 题，正确率 $accuracy%，很棒。下一步重点照顾一下 $weak，把它多练几遍，连续答对会越来越轻松。"
    }
}

fun problemMastery(problem: Problem, attempts: List<Attempt>, target: Int): ProblemMastery {
    val rows = attempts.filter { it.a == problem.a && it.b == problem.b }
    val wrongCount = rows.count { !it.correct }
    val recentCorrectStreak = rows.takeWhile { it.correct }.size
    return ProblemMastery(
        problem = problem,
        wrongCount = wrongCount,
        recentCorrectStreak = recentCorrectStreak,
        mastered = recentCorrectStreak >= target.coerceIn(1, 20)
    )
}

fun masteryColor(mastery: ProblemMastery, target: Int): Color {
    if (mastery.mastered) return Color(0xFF246B45)
    val progress = (mastery.recentCorrectStreak.toFloat() / target.coerceIn(1, 20)).coerceIn(0f, 1f)
    val heat = ((mastery.wrongCount + 1).toFloat() / 6f).coerceIn(0f, 1f)
    val redPressure = (heat * (1f - progress * 0.75f)).coerceIn(0f, 1f)
    return blendColor(Color(0xFF6DAA57), Color(0xFFB23A48), redPressure)
}

fun blendColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = 1f
    )
}

fun bestCorrectStreak(attempts: List<Attempt>): Int {
    var best = 0
    var current = 0
    attempts.asReversed().forEach { attempt ->
        if (attempt.correct) {
            current += 1
            best = max(best, current)
        } else {
            current = 0
        }
    }
    return best
}

fun currentPracticeDayStreak(attempts: List<Attempt>): Int {
    val days = practiceDays(attempts)
    if (days.isEmpty()) return 0
    val today = LocalDate.now()
    if (days.first() != today && days.first() != today.minusDays(1)) return 0
    var streak = 1
    var day = days.first()
    while (days.contains(day.minusDays(1))) {
        streak += 1
        day = day.minusDays(1)
    }
    return streak
}

fun bestPracticeDayStreak(attempts: List<Attempt>): Int {
    val days = practiceDays(attempts).sorted()
    var best = 0
    var current = 0
    var previous: LocalDate? = null
    days.forEach { day ->
        current = if (previous?.plusDays(1) == day) current + 1 else 1
        best = max(best, current)
        previous = day
    }
    return best
}

fun bestPerfectSessionStreak(sessions: List<PracticeSession>): Int {
    var best = 0
    var current = 0
    sessions.asReversed().forEach { session ->
        if (session.perfect) {
            current += 1
            best = max(best, current)
        } else {
            current = 0
        }
    }
    return best
}

fun practiceDays(attempts: List<Attempt>): List<LocalDate> {
    val zone = ZoneId.systemDefault()
    return attempts
        .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        .toSet()
        .sortedDescending()
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Color(0xFF6C6F75), fontSize = 13.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Color(0xFF182027))
        }
    }
}

@Composable
fun AccuracyChart(attempts: List<Attempt>) {
    val points = attempts.mapIndexed { index, _ ->
        val window = attempts.take(index + 1)
        window.count { it.correct }.toFloat() / max(1, window.size)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFFF3F4F0), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        val axisColor = Color(0xFFD1D6D1)
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        drawLine(axisColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
        if (points.isEmpty()) return@Canvas
        val step = if (points.size == 1) size.width else size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - value * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(Color(0xFF246B45), radius = 5f, center = Offset(x, y))
        }
        drawPath(path, Color(0xFF246B45), style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

@Composable
fun SettingsScreen(settings: AppSettings, onUpdate: (AppSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingSwitch(
                title = "百度 TTS",
                subtitle = "使用百度语音合成读题和反馈，音频会缓存在本机。",
                checked = draft.ttsEnabled,
                onCheckedChange = {
                    draft = draft.copy(ttsEnabled = it)
                    onUpdate(draft)
                }
            )
        }
        item {
            Text("语音识别", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        draft = draft.copy(asrProvider = ASR_SYSTEM)
                        onUpdate(draft)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (draft.asrProvider == ASR_SYSTEM) Color(0xFF246B45) else Color(0xFF59636E)
                    )
                ) {
                    Text("系统 ASR")
                }
                Button(
                    onClick = {
                        draft = draft.copy(asrProvider = ASR_BAIDU)
                        onUpdate(draft)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (draft.asrProvider == ASR_BAIDU) Color(0xFF246B45) else Color(0xFF59636E)
                    )
                ) {
                    Text("百度 ASR")
                }
            }
        }
        item {
            Button(
                onClick = {
                    draft = draft.copy(asrProvider = ASR_AZURE)
                    onUpdate(draft)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (draft.asrProvider == ASR_AZURE) Color(0xFF246B45) else Color(0xFF59636E)
                )
            ) {
                Text("Azure Speech")
            }
        }
        item {
            OutlinedTextField(
                value = draft.baiduApiKey,
                onValueChange = {
                    draft = draft.copy(baiduApiKey = it)
                    onUpdate(draft)
                },
                label = { Text("百度语音 API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            OutlinedTextField(
                value = draft.azureSpeechKey,
                onValueChange = {
                    draft = draft.copy(azureSpeechKey = it)
                    onUpdate(draft)
                },
                label = { Text("Azure Speech Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            OutlinedTextField(
                value = draft.azureRegion,
                onValueChange = {
                    draft = draft.copy(azureRegion = it)
                    onUpdate(draft)
                },
                label = { Text("Azure Region") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.azureEndpoint,
                onValueChange = {
                    draft = draft.copy(azureEndpoint = it)
                    onUpdate(draft)
                },
                label = { Text("Azure Endpoint") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.baiduSecretKey,
                onValueChange = {
                    draft = draft.copy(baiduSecretKey = it)
                    onUpdate(draft)
                },
                label = { Text("百度语音 Secret Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            Text("大模型", fontWeight = FontWeight.Bold, color = Color(0xFF29323A))
        }
        item {
            OutlinedTextField(
                value = draft.masteryStreakTarget.toString(),
                onValueChange = { value ->
                    val target = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 20) ?: 1
                    draft = draft.copy(masteryStreakTarget = target)
                    onUpdate(draft)
                },
                label = { Text("攻克目标连续答对次数") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            SettingSwitch(
                title = "大模型校验",
                subtitle = "开启后会把语音转写和当前题目发到配置的 OpenAI 兼容接口。",
                checked = draft.llmEnabled,
                onCheckedChange = {
                    draft = draft.copy(llmEnabled = it)
                    onUpdate(draft)
                }
            )
        }
        item {
            OutlinedTextField(
                value = draft.apiBase,
                onValueChange = {
                    draft = draft.copy(apiBase = it)
                    onUpdate(draft)
                },
                label = { Text("API Base") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.modelId,
                onValueChange = {
                    draft = draft.copy(modelId = it)
                    onUpdate(draft)
                },
                label = { Text("Model ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = {
                    draft = draft.copy(apiKey = it)
                    onUpdate(draft)
                },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            Surface(color = Color(0xFFE9F1EC), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF246B45))
                    Spacer(Modifier.width(10.dp))
                    Text("题目会先播放再开始等待作答；答对反馈使用固定短句缓存，答错会念出题目和正确答案。", color = Color(0xFF29323A))
                }
            }
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF182027))
            Text(subtitle, color = Color(0xFF6C6F75), fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun MultiplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF246B45),
            secondary = Color(0xFF2F6F8F),
            tertiary = Color(0xFFB23A48),
            background = Color(0xFFF7F7F2),
            surface = Color.White
        ),
        content = content
    )
}
