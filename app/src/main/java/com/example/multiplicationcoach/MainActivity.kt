package com.example.multiplicationcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
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
import com.example.multiplicationcoach.update.AppUpdateUiState
import com.example.multiplicationcoach.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val answerTimeLimitSeconds: Int = 3,
)

data class UiState(
    val running: Boolean = false,
    val problem: Problem? = null,
    val revealedAnswer: Int? = null,
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

fun UiState.questionText(): String = problem?.let { problem ->
    "${problem.a} × ${problem.b} = ${revealedAnswer ?: "?"}"
} ?: "准备好了吗？"

class PracticeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PracticePrefs(application)
    private val localAsr = LocalAsrEngine()
    private val player = FixedAudioPlayer(application)
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
                        localAsrReason = "离线识别不可用：" + error.message.orEmpty(),
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
            answerTimeLimitSeconds = settings.answerTimeLimitSeconds.coerceIn(1, 15),
        )
        prefs.saveSettings(normalized)
        _state.value = _state.value.copy(settings = normalized)
    }

    fun start() {
        if (_state.value.running) return
        if (!_state.value.localAsrReady) {
            _state.value = _state.value.copy(feedback = _state.value.localAsrReason.orEmpty())
            return
        }
        stopped = false
        _state.value = _state.value.copy(running = true, feedback = "练习开始：语音、转写和判题都在本机完成。")
        practiceJob = viewModelScope.launch {
            var retryProblem: Problem? = null
            while (!stopped) {
                val problem = retryProblem ?: allProblems.random()
                retryProblem = if (askOneProblem(problem)) problem else null
                delay(800)
            }
        }
    }

    fun stop() {
        stopped = true
        practiceJob?.cancel()
        player.stop()
        _state.value = _state.value.copy(running = false, countdown = 0, phase = "已停止", feedback = "已停止练习。")
    }

    fun clearHistory() {
        prefs.saveAttempts(emptyList())
        _state.value = _state.value.copy(attempts = emptyList(), feedback = "历史记录已清空。")
    }

    private suspend fun askOneProblem(problem: Problem): Boolean {
        val seconds = _state.value.settings.answerTimeLimitSeconds
        _state.value = _state.value.copy(
            problem = problem, revealedAnswer = null, phase = "读题", transcript = "", countdown = seconds,
            feedback = problem.label + " = ?",
        )
        if (!_state.value.hasAudioPermission) {
            save(problem, "", null, false, "local", "缺少麦克风权限。正确答案：" + problem.answer)
            return false
        }
        // Initialize the microphone before the question ends so an immediate answer
        // cannot lose its first syllable while AudioRecord is being created.
        val recorder = runCatching { withContext(Dispatchers.IO) { PcmRecorder.prepare() } }
            .getOrElse { error ->
                _state.value = _state.value.copy(
                    phase = "录音失败",
                    countdown = 0,
                    feedback = "麦克风录音失败：" + error.message.orEmpty(),
                )
                return false
            }
        try {
            if (_state.value.settings.promptAudioEnabled) {
                player.playProblemAndWait(problem).userMessage?.let { message ->
                    _state.value = _state.value.copy(feedback = message)
                }
            }
        _state.value = _state.value.copy(phase = "请作答", feedback = "请在 " + seconds + " 秒内说出答案。")
        val recognition = localAsr(seconds, recorder, problem.answer)
        if (stopped) return false
        val transcript = recognition.getOrElse { error ->
            _state.value = _state.value.copy(
                phase = "录音失败",
                countdown = 0,
                transcript = "",
                feedback = "麦克风录音失败：" + error.message.orEmpty(),
            )
            return false
        }
        val plan = PracticeAnswerPlanner.plan(problem, transcript)
        if (plan.repeatProblem) {
            _state.value = _state.value.copy(
                phase = "未识别",
                countdown = 0,
                transcript = "未识别到语音",
                feedback = "没有识别到答案，请再说一次。",
            )
            if (_state.value.settings.promptAudioEnabled && plan.waitForAudioCompletion) {
                player.playResourceAndWait(plan.audioResourceName)
            }
            return true
        }
        val result = checkNotNull(plan.verification)
        val message = if (result.correct) {
            "答对了"
        } else {
            "答错了，正确答案：" + problem.answer
        }
        _state.value = _state.value.copy(
            phase = if (result.correct) "答对" else "订正",
            countdown = 0,
            revealedAnswer = problem.answer,
            transcript = transcript.ifBlank { "未识别到语音" },
            feedback = message,
        )
        if (_state.value.settings.promptAudioEnabled && plan.waitForAudioCompletion) {
            player.playResourceAndWait(plan.audioResourceName)
        }
        save(problem, transcript, result.extractedAnswer, result.correct, result.checkedBy, message)
        return false
        } finally {
            recorder.release()
        }
    }

    private suspend fun localAsr(
        seconds: Int,
        recorder: PcmRecorder.PreparedRecorder,
        expectedAnswer: Int,
    ): Result<String> = coroutineScope {
        _state.value = _state.value.copy(transcript = "正在本地录音…")
        val recordingStartedAt = kotlinx.coroutines.CompletableDeferred<Long>()
        val capture = async(Dispatchers.IO) {
            recorder.recordSeconds(seconds) {
                recordingStartedAt.complete(SystemClock.elapsedRealtime())
            }
        }
        val countdown = try {
            val startedAt = recordingStartedAt.await()
            launch {
                while (true) {
                    val remaining = AnswerWindowClock.remainingSeconds(
                        totalSeconds = seconds,
                        recordingStartedAtMs = startedAt,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    _state.value = _state.value.copy(countdown = remaining)
                    if (remaining == 0) break
                    delay(100)
                }
            }
        } catch (error: Throwable) {
            return@coroutineScope Result.failure(error)
        }
        val pcm = try {
            capture.await()
        } catch (error: Throwable) {
            countdown.cancel()
            return@coroutineScope Result.failure(error)
        }
        countdown.cancel()
        if (stopped) return@coroutineScope Result.success("")
        _state.value = _state.value.copy(transcript = "正在本地识别…")
        localAsr.transcribe(pcm, expectedAnswer).onFailure { error ->
            _state.value = _state.value.copy(feedback = "离线识别失败：" + error.message.orEmpty())
        }
    }

    private fun save(
        problem: Problem, transcript: String, answer: Int?, correct: Boolean, checkedBy: String, feedback: String,
    ) {
        val attempts = (listOf(Attempt(problem.a, problem.b, problem.answer, transcript, answer, correct, checkedBy)) + _state.value.attempts).take(300)
        prefs.saveAttempts(attempts)
        _state.value = _state.value.copy(attempts = attempts, feedback = feedback)
    }

    override fun onCleared() {
        player.stop()
        localAsr.release()
        super.onCleared()
    }

    private fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == 0
}

object PcmRecorder {
    class PreparedRecorder internal constructor(
        private val recorder: AudioRecord,
        private val bufferSize: Int,
    ) {
        private var released = false

        fun recordSeconds(seconds: Int, onRecordingStarted: () -> Unit): ByteArray {
            check(!released) { "麦克风录音器已释放" }
            val output = ByteArray(seconds.coerceIn(1, 15) * PCM_SAMPLE_RATE * 2)
            try {
                recorder.startRecording()
                onRecordingStarted()
                var offset = 0
                while (offset < output.size) {
                    val read = recorder.read(
                        output,
                        offset,
                        minOf(bufferSize, output.size - offset),
                        AudioRecord.READ_BLOCKING,
                    )
                    check(read > 0) { "麦克风录音中断：$read" }
                    offset += read
                }
                return output
            } finally {
                release()
            }
        }

        fun release() {
            if (released) return
            released = true
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun prepare(): PreparedRecorder {
        val bufferSize = AudioRecord.getMinBufferSize(
            PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(PCM_SAMPLE_RATE / 5)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风录音不可用" }
        return PreparedRecorder(recorder, bufferSize)
    }

    private const val PCM_SAMPLE_RATE = 16_000
}

class PracticePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("practice", Context.MODE_PRIVATE)
    fun loadSettings(): AppSettings = AppSettings(
        promptAudioEnabled = prefs.getBoolean("promptAudioEnabled", true),
        answerTimeLimitSeconds = prefs.getInt("answerTimeLimitSeconds", 3).coerceIn(1, 15),
    )
    fun saveSettings(settings: AppSettings) {
        prefs.edit().putBoolean("promptAudioEnabled", settings.promptAudioEnabled)
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

private val allProblems = (1..9).flatMap { a -> (1..9).map { b -> Problem(a, b) } }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MultiplicationApp(
    viewModel: PracticeViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != 0) permission.launch(Manifest.permission.RECORD_AUDIO)
        updateViewModel.checkForUpdates(automatic = true)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (showSettings) "设置" else "乘法口诀背诵") }) }) { padding ->
        if (showSettings) SettingsScreen(
            state = state,
            update = viewModel::updateSettings,
            updateState = updateState,
            checkForUpdates = { updateViewModel.checkForUpdates(automatic = false) },
            downloadUpdate = updateViewModel::downloadAvailableUpdate,
            installUpdate = updateViewModel::installDownloadedUpdate,
            close = { showSettings = false },
        )
        else PracticeScreen(state, viewModel::start, viewModel::stop, viewModel::clearHistory, { showSettings = true }, Modifier.padding(padding))
    }
}

@Composable
fun PracticeScreen(state: UiState, start: () -> Unit, stop: () -> Unit, clear: () -> Unit, settings: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.phase)
                Text(state.questionText(), fontSize = 40.sp, fontWeight = FontWeight.Bold)
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
fun SettingsScreen(
    state: UiState,
    update: (AppSettings) -> Unit,
    updateState: AppUpdateUiState,
    checkForUpdates: () -> Unit,
    downloadUpdate: () -> Unit,
    installUpdate: () -> Unit,
    close: () -> Unit,
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("题目音频", fontWeight = FontWeight.Bold); Text("内置中文音频，不联网。") }
                Switch(checked = draft.promptAudioEnabled, onCheckedChange = { value -> draft = draft.copy(promptAudioEnabled = value); update(draft) })
            }
        }
        item { Text(if (state.localAsrReady) "本地 ASR 已就绪：语音不会上传。" else state.localAsrReason.orEmpty()) }
        item {
            OutlinedTextField(value = draft.answerTimeLimitSeconds.toString(), onValueChange = { text ->
                draft = draft.copy(answerTimeLimitSeconds = text.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 15) ?: 3)
                update(draft)
            }, label = { Text("答题时间（秒）") })
        }
        item { Text("应用更新", fontWeight = FontWeight.Bold) }
        item { Text("当前版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）") }
        item {
            when (updateState) {
                AppUpdateUiState.Idle -> Button(onClick = checkForUpdates) { Text("检查更新") }
                AppUpdateUiState.Checking -> Text("正在安全检查更新…")
                AppUpdateUiState.UpToDate -> Button(onClick = checkForUpdates) { Text("已是最新版本，重新检查") }
                is AppUpdateUiState.Available -> {
                    val suffix = if (updateState.mobileData) "当前为移动数据，需你确认后下载。" else "已发现可用更新。"
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("发现新版本 " + updateState.manifest.version.versionName + "。" + suffix)
                        Button(onClick = downloadUpdate) { Text("下载更新") }
                    }
                }
                is AppUpdateUiState.Downloading -> Text(
                    "正在下载更新：" + updateState.receivedBytes / 1024 / 1024 + " / " + updateState.totalBytes / 1024 / 1024 + " MB",
                )
                is AppUpdateUiState.ReadyToInstall -> Button(onClick = installUpdate) { Text("安装已验证的更新") }
                is AppUpdateUiState.NeedsInstallPermission -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请先允许本应用安装更新，再返回点此继续。")
                    Button(onClick = installUpdate) { Text("允许安装并继续") }
                }
                AppUpdateUiState.Installing -> Text("已交给 Android 系统安装器确认安装。")
                is AppUpdateUiState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(updateState.message, color = Color(0xFFB23A48))
                    Button(onClick = checkForUpdates) { Text("重试检查") }
                }
            }
        }
        item { Button(onClick = close) { Text("返回练习") } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = androidx.compose.material3.lightColorScheme(primary = Color(0xFF246B45), background = Color(0xFFF7F7F2)),
    content = content,
)
