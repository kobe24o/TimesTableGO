package com.example.multiplicationcoach

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.multiplicationcoach.update.*
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : FlutterFragmentActivity() {
    private lateinit var updates: UpdateViewModel
    private var collection: Job? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        updates = ViewModelProvider(this)[UpdateViewModel::class.java]
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, "timestablego/legacy").setMethodCallHandler { call, result ->
            if (call.method != "readPractice") { result.notImplemented(); return@setMethodCallHandler }
            val prefs = getSharedPreferences("practice", MODE_PRIVATE)
            if (prefs.all.isEmpty()) { result.success(null); return@setMethodCallHandler }
            result.success(mapOf(
                "language" to prefs.getString("language", "ZH"),
                "answerTimeLimitSeconds" to prefs.getInt("answerTimeLimitSeconds", 3),
                "promptAudioEnabled" to prefs.getBoolean("promptAudioEnabled", true),
                "attempts" to prefs.getString("attempts", "[]")
            ))
        }
        MethodChannel(messenger, "timestablego/settings").setMethodCallHandler { call, result ->
            if (call.method == "open") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                result.success(null)
            } else result.notImplemented()
        }
        MethodChannel(messenger, "timestablego/update").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "check" -> updates.checkForUpdates(automatic = call.argument<Boolean>("automatic") ?: false)
                    "download" -> updates.downloadAvailableUpdate()
                    "install" -> updates.installDownloadedUpdate()
                    else -> { result.notImplemented(); return@setMethodCallHandler }
                }
                result.success(null)
            } catch (e: Exception) { result.error("UPDATE_FAILED", e.message, null) }
        }
        EventChannel(messenger, "timestablego/update-state").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                collection?.cancel()
                collection = lifecycleScope.launch { updates.state.collect { state -> events.success(encode(state)) } }
                updates.checkForUpdates(automatic = true)
            }
            override fun onCancel(arguments: Any?) { collection?.cancel(); collection = null }
        })
    }
    private fun encode(s: AppUpdateUiState): Map<String, Any> = when(s) {
        is AppUpdateUiState.Available -> mapOf("state" to "available", "version" to s.manifest.version.versionName)
        is AppUpdateUiState.Downloading -> mapOf("state" to "downloading", "received" to s.receivedBytes, "total" to s.totalBytes)
        is AppUpdateUiState.ReadyToInstall -> mapOf("state" to "ready")
        is AppUpdateUiState.NeedsInstallPermission -> mapOf("state" to "ready")
        is AppUpdateUiState.Error -> mapOf("state" to "error", "zh" to s.messageZh, "en" to s.messageEn)
        AppUpdateUiState.Checking -> mapOf("state" to "checking")
        AppUpdateUiState.UpToDate -> mapOf("state" to "current")
        else -> mapOf("state" to "idle")
    }
}
