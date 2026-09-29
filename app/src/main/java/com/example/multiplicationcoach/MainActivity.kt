package com.example.multiplicationcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { MultiplicationApp() } }
    }
}

data class Problem(val a: Int, val b: Int) {
    val answer: Int = a * b
    val label: String = a.toString() + " × " + b
}

data class Attempt(
    val a: Int,
    val b: Int,
    val expected: Int,
    val transcript: String,
    val extractedAnswer: Int?,
    val correct: Boolean,
    val checkedBy: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AppSettings(
    val promptAudioEnabled: Boolean = true,
    val asrProvider: String = ASR_LOCAL,
    val answerTimeLimitSeconds: Int = 3,
)

data class UiState(
    val running: Boolean = false,
    val problem: Problem? = null,
    val phase: String = "准备开始",
    val countdown: Int = 0,
    val transcript: String = "",
    val feedback: String = "点击开始，随机练习九九乘法表。",
    val attempts: List<Attempt> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val hasAudioPermission: Boolean = false,
    val localAsrReady: Boolean = false,
    val localAsrReason: String? = "正在检查离线识别资源…",
)

class PracticeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PracticePrefs(application)
    private val localAsr = LocalAsrEngine()
    private val player = FixedAudioPlayer(application)
    private var recognizer: SpeechRecognizer? = null
    private var practiceJob: Job? = null
    private var stopped = false

    private val _state = MutableStateFlow(
        UiState(
            attempts = prefs.loadAttempts(),
            settings = prefs.loadSettings(),
            hasAudioPermission = hasAudioPermission(application),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            localAsr.initialize(application).fold(
                onSuccess = {
                    _state.value = _state.value.copy(localAsrReady = true, localAsrReason = null)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        localAsrReason = "离线识别不可用：" + error.message.orEmpty() + "。可切换系统识别。",
                    )
                },
            )
        }
    }

    fun refreshPermission() {
        _state.value = _state.value.copy(hasAudioPermission = hasAudioPermission(getApplication()))
    }

    fun updateSettings(settings: AppSettings) {
        val normalized = settings.copy(
            asrProvider = if (settings.asrProvider == ASR_SYSTEM) ASR_SYSTEM else ASR_LOCAL,
            answerTimeLimitSeconds = settings.answerTimeLimitSeconds.coerceIn(1, 15),
        )
        prefs.saveSettings(normalized)
        _state.value = _state.value.copy(settings = normalized)
    }

    fun start() {
        if (_state.value.running) return
        if (_state.value.settings.asrProvider == ASR_LOCAL && !_state.value.localAsrReady) {
            _state.value = _state.value.copy(feedback = _state.value.localAsrReason.orEmpty())
            return
        }
        stopped = false
        _state.value = _state.value.copy(running = true, feedback = "练习开始：语音、转写和判题都在本机完成。")
        practiceJob = viewModelScope.launch {
            while (!stopped) {
                askOneProblem()
                delay(800)
            }
        }
    }

    fun stop() {
        stopped = true
        practiceJob?.cancel()
        recognizer?.cancel()
        player.stop()
        _state.value = _state.value.copy(running = false, countdown = 0, phase = "已停止", feedback = "已停止练习。")
    }

    fun clearHistory() {
        prefs.saveAttempts(emptyList())
        _state.value = _state.value.copy(attempts = emptyList(), feedback = "历史记录已清空。")
    }

    private suspend fun askOneProblem() {
        val problem = allProblems.random()
        val seconds = _state.value.settings.answerTimeLimitSeconds
        _state.value = _state.value.copy(
            problem = problem, phase = "读题", transcript = "", countdown = seconds,
            feedback = problem.label + " = ?",
        )
        if (_state.value.settings.promptAudioEnabled) {
            player.playProblem(problem).userMessage?.let { message ->
                _state.value = _state.value.copy(feedback = message)
            }
        }
        if (!_state.value.hasAudioPermission) {
            save(problem, "", null, false, "local", "缺少麦克风权限。正确答案：" + problem.answer)
            return
        }
        _state.value = _state.value.copy(phase = "请作答", feedback = "请在 " + seconds + " 秒内说出答案。")
        val timer = viewModelScope.launch {
            for (second in seconds downTo 1) {
                _state.value = _state.value.copy(countdown = second)
                delay(1000)
            }
        }
        val transcript = if (_state.value.settings.asrProvider == ASR_SYSTEM) systemAsr(seconds) else localAsr(seconds)
        timer.cancel()
        if (stopped) return
        val checkedBy = if (_state.value.settings.asrProvider == ASR_SYSTEM) "system" else "local"
        val result = LocalAnswerVerifier.verify(problem.answer, transcript, checkedBy)
        val message = if (result.correct) {
            "答对了：" + problem.label + " = " + problem.answer
        } else {
            "答错了，正确答案：" + problem.answer
        }
        _state.value = _state.value.copy(
            phase = if (result.correct) "答对" else "订正",
            countdown = 0,
            transcript = transcript.ifBlank { "未识别到语音" },
            feedback = message,
        )
        if (_state.value.settings.promptAudioEnabled) {
            player.playFeedback(if (result.correct) FixedFeedback.Correct else FixedFeedback.Incorrect)
        }
        save(problem, transcript, result.extractedAnswer, result.correct, result.checkedBy, message)
    }

    private suspend fun localAsr(seconds: Int): String {
        _state.value = _state.value.copy(transcript = "正在本地录音…")
        val pcm = withContext(Dispatchers.IO) { PcmRecorder.recordSeconds(seconds) }
        if (stopped) return ""
        _state.value = _state.value.copy(transcript = "正在本地识别…")
        return localAsr.transcribe(pcm).getOrElse { error ->
            _state.value = _state.value.copy(feedback = "离线识别失败：" + error.message.orEmpty())
            ""
        }
    }

    private suspend fun systemAsr(seconds: Int): String = suspendCancellableCoroutine { continuation ->
        recognizer?.destroy()
        val systemRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication())
        recognizer = systemRecognizer
        var completed = false
        fun finish(value: String) {
            if (completed) return
            completed = true
            systemRecognizer.stopListening()
            continuation.resume(value)
        }
        systemRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = finish("")
            override fun onPartialResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial ->
                    _state.value = _state.value.copy(transcript = partial)
                }
            }
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
        })
        systemRecognizer.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        viewModelScope.launch {
            delay(seconds * 1000L + 200)
            finish(_state.value.transcript.takeUnless { it.endsWith("…") }.orEmpty())
        }
        continuation.invokeOnCancellation { systemRecognizer.cancel() }
    }

    private fun save(
        problem: Problem, transcript: String, answer: Int?, correct: Boolean, checkedBy: String, feedback: String,
    ) {
        val attempts = (listOf(Attempt(problem.a, problem.b, problem.answer, transcript, answer, correct, checkedBy)) + _state.value.attempts).take(300)
        prefs.saveAttempts(attempts)
        _state.value = _state.value.copy(attempts = attempts, feedback = feedback)
    }

    override fun onCleared() {
        recognizer?.destroy()
        player.stop()
        localAsr.release()
        super.onCleared()
    }

    private fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == 0
}

object PcmRecorder {
    @SuppressLint("MissingPermission")
    fun recordSeconds(seconds: Int): ByteArray {
        val sampleRate = 16_000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(sampleRate / 5)
        val output = ByteArray(seconds.coerceIn(1, 15) * sampleRate * 2)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        try {
            recorder.startRecording()
            var offset = 0
            while (offset < output.size) {
                val read = recorder.read(output, offset, minOf(bufferSize, output.size - offset))
                if (read <= 0) break
                offset += read
            }
            return output.copyOf(offset)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

class PracticePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("practice", Context.MODE_PRIVATE)
    fun loadSettings(): AppSettings = AppSettings(
        promptAudioEnabled = prefs.getBoolean("promptAudioEnabled", true),
        asrProvider = if (prefs.getString("asrProvider", ASR_LOCAL) == ASR_SYSTEM) ASR_SYSTEM else ASR_LOCAL,
        answerTimeLimitSeconds = prefs.getInt("answerTimeLimitSeconds", 3).coerceIn(1, 15),
    )
    fun saveSettings(settings: AppSettings) {
        prefs.edit().putBoolean("promptAudioEnabled", settings.promptAudioEnabled)
            .putString("asrProvider", settings.asrProvider)
            .putInt("answerTimeLimitSeconds", settings.answerTimeLimitSeconds).apply()
    }
    fun loadAttempts(): List<Attempt> = runCatching {
        val array = JSONArray(prefs.getString("attempts", "[]"))
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            Attempt(value.getInt("a"), value.getInt("b"), value.getInt("expected"), value.optString("transcript"),
                if (value.isNull("extractedAnswer")) null else value.getInt("extractedAnswer"),
                value.getBoolean("correct"), value.optString("checkedBy", "local"), value.optLong("timestamp"))
        }
    }.getOrDefault(emptyList())
    fun saveAttempts(attempts: List<Attempt>) {
        val array = JSONArray()
        attempts.forEach { attempt ->
            array.put(JSONObject().put("a", attempt.a).put("b", attempt.b).put("expected", attempt.expected)
                .put("transcript", attempt.transcript).put("extractedAnswer", attempt.extractedAnswer)
                .put("correct", attempt.correct).put("checkedBy", attempt.checkedBy).put("timestamp", attempt.timestamp))
        }
        prefs.edit().putString("attempts", array.toString()).apply()
    }
}

const val ASR_LOCAL = "local"
const val ASR_SYSTEM = "system"
private val allProblems = (1..9).flatMap { a -> (1..9).map { b -> Problem(a, b) } }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MultiplicationApp(viewModel: PracticeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != 0) permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (showSettings) "设置" else "乘法口诀背诵") }) }) { padding ->
        if (showSettings) SettingsScreen(state, viewModel::updateSettings, { showSettings = false })
        else PracticeScreen(state, viewModel::start, viewModel::stop, viewModel::clearHistory, { showSettings = true }, Modifier.padding(padding))
    }
}

@Composable
fun PracticeScreen(state: UiState, start: () -> Unit, stop: () -> Unit, clear: () -> Unit, settings: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.phase)
                Text(state.problem?.let { it.a.toString() + " × " + it.b + " = ?" } ?: "准备好了吗？", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                if (state.countdown > 0) Text(state.countdown.toString() + " 秒")
                Text(state.feedback, textAlign = TextAlign.Center)
                if (state.transcript.isNotBlank()) Text("识别：" + state.transcript, color = Color.Gray)
                if (!state.localAsrReady) Text(state.localAsrReason.orEmpty(), color = Color(0xFFB23A48), textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = start, enabled = !state.running) { Text("开始") }
            Button(onClick = stop, enabled = state.running, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB23A48))) { Text("停止") }
            Button(onClick = settings) { Text("设置") }
        }
        if (!state.hasAudioPermission) Text("需要麦克风权限才能语音作答。", color = Color(0xFFB23A48))
        Text("已保存 " + state.attempts.size + " 条本地记录。")
        if (state.attempts.isNotEmpty()) Button(onClick = clear) { Text("清空历史") }
    }
}

@Composable
fun SettingsScreen(state: UiState, update: (AppSettings) -> Unit, close: () -> Unit) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("题目音频", fontWeight = FontWeight.Bold); Text("内置中文音频，不联网。") }
                Switch(checked = draft.promptAudioEnabled, onCheckedChange = { value -> draft = draft.copy(promptAudioEnabled = value); update(draft) })
            }
        }
        item { Text("离线 ASR 是默认模式；系统 ASR 仅作显式后备。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { draft = draft.copy(asrProvider = ASR_LOCAL); update(draft) }) { Text("本地 ASR") }
                Button(onClick = { draft = draft.copy(asrProvider = ASR_SYSTEM); update(draft) }) { Text("系统 ASR") }
            }
        }
        item { Text(if (state.localAsrReady) "本地 ASR 已就绪：语音不会上传。" else state.localAsrReason.orEmpty()) }
        item {
            OutlinedTextField(value = draft.answerTimeLimitSeconds.toString(), onValueChange = { text ->
                draft = draft.copy(answerTimeLimitSeconds = text.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 15) ?: 3)
                update(draft)
            }, label = { Text("答题时间（秒）") })
        }
        item { Button(onClick = close) { Text("返回练习") } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = androidx.compose.material3.lightColorScheme(primary = Color(0xFF246B45), background = Color(0xFFF7F7F2)),
    content = content,
)
