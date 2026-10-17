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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

enum class AppTab { Practice, Stats, Settings }

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
    var tab by remember { mutableStateOf(AppTab.Practice) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != 0) permission.launch(Manifest.permission.RECORD_AUDIO)
        updateViewModel.checkForUpdates(automatic = true)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF246B45), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when (tab) {
                        AppTab.Practice -> "乘法口诀背诵"
                        AppTab.Stats -> "学习统计"
                        AppTab.Settings -> "设置"
                    },
                )
            }
        })
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(
                selected = tab == AppTab.Practice,
                onClick = { tab = AppTab.Practice },
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                label = { Text("练习") },
            )
            NavigationBarItem(
                selected = tab == AppTab.Stats,
                onClick = { tab = AppTab.Stats },
                icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                label = { Text("统计") },
            )
            NavigationBarItem(
                selected = tab == AppTab.Settings,
                onClick = { tab = AppTab.Settings },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("设置") },
            )
        }
    }) { padding ->
        when (tab) {
            AppTab.Practice -> PracticeScreen(state, viewModel::start, viewModel::stop, viewModel::clearHistory, { tab = AppTab.Settings }, Modifier.padding(padding))
            AppTab.Stats -> StatsScreen(state, Modifier.padding(padding))
            AppTab.Settings -> SettingsScreen(
                state = state,
                update = viewModel::updateSettings,
                updateState = updateState,
                checkForUpdates = { updateViewModel.checkForUpdates(automatic = false) },
                downloadUpdate = updateViewModel::downloadAvailableUpdate,
                installUpdate = updateViewModel::installDownloadedUpdate,
                close = { tab = AppTab.Practice },
            )
        }
    }
}

@Composable
fun PracticeScreen(state: UiState, start: () -> Unit, stop: () -> Unit, clear: () -> Unit, settings: () -> Unit, modifier: Modifier) {
    val total = state.settings.answerTimeLimitSeconds.coerceAtLeast(1)
    Column(modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF246B45), Color(0xFF2F6F8F))), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp)) {
                        Text(state.phase, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 14.sp)
                    }
                    Text(state.questionText(), fontSize = 46.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (state.countdown > 0) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = state.countdown / total.toFloat(),
                                modifier = Modifier.size(64.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f),
                                strokeWidth = 6.dp
                            )
                            Text(state.countdown.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                    }
                    Text(state.feedback, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.92f))
                    if (state.transcript.isNotBlank()) Text("识别：" + state.transcript, color = Color.White.copy(alpha = 0.85f))
                    if (!state.localAsrReady) Text(state.localAsrReason.orEmpty(), color = Color(0xFFFFDAD6), textAlign = TextAlign.Center)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = start, enabled = !state.running,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("开始")
            }
            Button(
                onClick = stop, enabled = state.running,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB23A48)),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("停止")
            }
            OutlinedButton(
                onClick = settings,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("设置")
            }
        }
        if (!state.hasAudioPermission) Text("需要麦克风权限才能语音作答。", color = Color(0xFFB23A48))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                "已保存 " + state.attempts.size + " 条本地记录。",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.attempts.isNotEmpty()) {
            TextButton(onClick = clear) { Text("清空历史", color = Color(0xFFB23A48)) }
        }
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
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("音频、答题节奏与离线更新都在本机完成。", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("题目音频", fontWeight = FontWeight.Bold); Text("内置中文音频，不联网。") }
                    Switch(checked = draft.promptAudioEnabled, onCheckedChange = { value -> draft = draft.copy(promptAudioEnabled = value); update(draft) })
                }
            }
        }
        item { Text(if (state.localAsrReady) "本地 ASR 已就绪：语音不会上传。" else state.localAsrReason.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                OutlinedTextField(
                    value = draft.answerTimeLimitSeconds.toString(),
                    onValueChange = { text ->
                        draft = draft.copy(answerTimeLimitSeconds = text.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 15) ?: 3)
                        update(draft)
                    },
                    label = { Text("答题时间（秒）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item { Text("应用更新", fontWeight = FontWeight.Bold) }
        item { Text("当前版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）") }
        item {
            when (updateState) {
                AppUpdateUiState.Idle -> Button(onClick = checkForUpdates, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("检查更新") }
                AppUpdateUiState.Checking -> Text("正在安全检查更新…")
                AppUpdateUiState.UpToDate -> Button(onClick = checkForUpdates, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("已是最新版本，重新检查") }
                is AppUpdateUiState.Available -> {
                    val suffix = if (updateState.mobileData) "当前为移动数据，需你确认后下载。" else "已发现可用更新。"
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("发现新版本 " + updateState.manifest.version.versionName + "。" + suffix)
                        Button(onClick = downloadUpdate, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("下载更新") }
                    }
                }
                is AppUpdateUiState.Downloading -> Text(
                    "正在下载更新：" + updateState.receivedBytes / 1024 / 1024 + " / " + updateState.totalBytes / 1024 / 1024 + " MB",
                )
                is AppUpdateUiState.ReadyToInstall -> Button(onClick = installUpdate, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("安装已验证的更新") }
                is AppUpdateUiState.NeedsInstallPermission -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请先允许本应用安装更新，再返回点此继续。")
                    Button(onClick = installUpdate, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("允许安装并继续") }
                }
                AppUpdateUiState.Installing -> Text("已交给 Android 系统安装器确认安装。")
                is AppUpdateUiState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(updateState.message, color = Color(0xFFB23A48))
                    Button(onClick = checkForUpdates, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("重试检查") }
                }
            }
        }
        item { Button(onClick = close, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text("返回练习") } }
    }
}

@Composable
fun StatsScreen(state: UiState, modifier: Modifier) {
    val attempts = state.attempts
    val total = attempts.size
    val correctCount = attempts.count { it.correct }
    val accuracy = if (total > 0) correctCount.toFloat() / total else 0f
    val currentStreak = attempts.takeWhile { it.correct }.size
    val distinctPairs = attempts.map { it.a to it.b }.toSet().size
    val coverage = distinctPairs / 81f
    val achievements = computeAchievements(total, correctCount, accuracy, currentStreak, coverage, distinctPairs)
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("学习统计", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("所有数据都保存在本机，不会上传。", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        if (total == 0) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                        Text("还没有作答记录", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("去「练习」页用语音背诵九九乘法表，这里会显示正确率、掌握度和成就。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("总练习", "$total", "题", Modifier.weight(1f))
                    MetricCard("正确率", "${(accuracy * 100).toInt()}%", null, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("当前连对", "$currentStreak", "题", Modifier.weight(1f))
                    MetricCard("九表覆盖", "${(coverage * 100).toInt()}%", "$distinctPairs / 81", Modifier.weight(1f))
                }
            }
            item { SectionTitle("正确率走势") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    AccuracyChart(attempts, Modifier.padding(14.dp))
                }
            }
            item { SectionTitle("成就") }
            item { AchievementBadges(achievements) }
            item { SectionTitle("九九表掌握度") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(14.dp)) { MistakeMatrix(attempts) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("最近作答")
                    Text("$total 条记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(attempts.take(10)) { AttemptChip(it) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
}

@Composable
fun MetricCard(label: String, value: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
fun AccuracyChart(attempts: List<Attempt>, modifier: Modifier = Modifier) {
    val chrono = attempts.asReversed()
    val points = chrono.mapIndexed { index, _ -> chrono.take(index + 1).count { it.correct }.toFloat() / (index + 1) }
    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Text("开始练习后这里会显示正确率走势。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        val grid = Color(0xFFD5DAD4)
        listOf(0f, 0.5f, 1f).forEach { g ->
            val y = h - g * h
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        val step = if (points.size == 1) 0f else w / (points.size - 1)
        val xy = points.mapIndexed { i, v -> Offset(i * step, h - v * h) }
        val fillPath = Path().apply {
            moveTo(0f, h)
            xy.forEach { lineTo(it.x, it.y) }
            lineTo(w, h)
            close()
        }
        drawPath(fillPath, Brush.verticalGradient(listOf(Color(0x55246B45), Color(0x00246B45))))
        val linePath = Path().apply { xy.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(linePath, Color(0xFF246B45), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFF2F6F8F), radius = 5.dp.toPx(), center = xy.last())
    }
}

data class Achievement(val title: String, val subtitle: String, val unlocked: Boolean)

fun computeAchievements(total: Int, correct: Int, accuracy: Float, streak: Int, coverage: Float, distinct: Int): List<Achievement> = listOf(
    Achievement("初出茅庐", "完成第 1 题", total >= 1),
    Achievement("渐入佳境", "正确率 ≥ 80%", total >= 10 && accuracy >= 0.8f),
    Achievement("连战连胜", "连续答对 10 题", streak >= 10),
    Achievement("稳如老手", "正确率 ≥ 95%", total >= 50 && accuracy >= 0.95f),
    Achievement("九九归一", "练完全部 81 题", distinct >= 81),
    Achievement("百题斩", "累计练习 100 题", total >= 100),
)

@Composable
fun AchievementBadges(achievements: List<Achievement>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(achievements) { ach ->
            Column(
                modifier = Modifier.width(92.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (ach.unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = if (ach.unlocked) 3.dp else 0.dp,
                ) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (ach.unlocked) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = null,
                            tint = if (ach.unlocked) Color(0xFF246B45) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Text(ach.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (ach.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ach.subtitle, fontSize = 10.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 12.sp)
            }
        }
    }
}

fun masteryColor(mastery: Float?): Color = when {
    mastery == null -> Color(0xFFEDF1EC)
    mastery >= 0.9f -> Color(0xFF246B45)
    mastery >= 0.7f -> Color(0xFF5AA46E)
    mastery >= 0.5f -> Color(0xFFE0A93B)
    else -> Color(0xFFB23A48)
}

@Composable
fun MistakeMatrix(attempts: List<Attempt>, modifier: Modifier = Modifier) {
    val correctOf = { a: Int, b: Int -> attempts.count { it.a == a && it.b == b && it.correct } }
    val masteryOf = { a: Int, b: Int ->
        val ca = attempts.filter { it.a == a && it.b == b }
        if (ca.isEmpty()) null else ca.count { it.correct }.toFloat() / ca.size
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.width(28.dp))
            (1..9).forEach { b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(b.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        (1..9).forEach { a ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Text(a.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (1..9).forEach { b ->
                    val m = masteryOf(a, b)
                    val c = correctOf(a, b)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).background(masteryColor(m), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (m != null) Text(c.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple(Color(0xFFEDF1EC), "未练", null), Triple(Color(0xFF5AA46E), "熟练", null), Triple(Color(0xFFE0A93B), "一般", null), Triple(Color(0xFFB23A48), "薄弱", null)).forEach { (color, label, _) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun AttemptChip(attempt: Attempt) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${attempt.a} × ${attempt.b} = ${attempt.expected}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("作答：" + attempt.transcript.ifBlank { "未识别" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Surface(color = if (attempt.correct) Color(0xFFD8EADD) else Color(0xFFFBE3E1), shape = RoundedCornerShape(999.dp)) {
                Text(
                    if (attempt.correct) "正确" else "订正",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = if (attempt.correct) Color(0xFF0C3320) else Color(0xFFB23A48),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF246B45),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8EADD),
        onPrimaryContainer = Color(0xFF0C3320),
        secondary = Color(0xFF2F6F8F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E6EF),
        tertiary = Color(0xFFB23A48),
        error = Color(0xFFB23A48),
        background = Color(0xFFF7F7F2),
        onBackground = Color(0xFF182027),
        surface = Color.White,
        onSurface = Color(0xFF182027),
        surfaceVariant = Color(0xFFEDF1EC),
        onSurfaceVariant = Color(0xFF59636E),
        outline = Color(0xFFD5DAD4),
    ),
    shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(28.dp),
    ),
    content = content,
)
