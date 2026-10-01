package com.example.multiplicationcoach.update

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class Available(val manifest: UpdateManifest, val mobileData: Boolean) : AppUpdateUiState
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : AppUpdateUiState
    data class ReadyToInstall(val manifest: UpdateManifest, val filePath: String) : AppUpdateUiState
    data class NeedsInstallPermission(val manifest: UpdateManifest, val filePath: String) : AppUpdateUiState
    data object Installing : AppUpdateUiState
    data class Error(val message: String) : AppUpdateUiState
}

private class AndroidUpdateTransport : UpdateBytesTransport, UpdateStreamTransport {
    override fun get(url: URL): ByteArray = open(url).use { it.readBytes() }

    override fun open(url: URL): InputStream {
        require(url.protocol == "https") { "Updates must use HTTPS" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("Update server returned HTTP $code")
        }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}

private object UpdateRuntime {
    private const val publicKeyBase64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAk/I8V8FxL7ZAGRXWNS9XTmHgNQH4pLSd63UY3vvGK+0UwoS2NAN1k7bKI/u3m9oAp7MjB0bh5WSsxn4+QplCvemZUE6uQ47TTtbYRrk6OaGUvwrVU2B7eOh7GZPoMFeumuUsxwL3bQJbZjZrVBfx8Tlcw4mU2pK/BbSrcj+HiKnVzL2jqDGsjzWpi7I/jgNx3mhHoxlc1I3iTNDKHNAt1KMNB/O++/6t1Bl7fVDULx8z26uu+Lv3oDX95QD28Ob1v1vjzkjaQhBQbEp0QLM2Of/dIB28P8F9bJu2FPXDzr3U4EJEqhxUGOhkg0FC4YYjQQ6G7pNTa9mf1YWKH1jQEBGhsVbzjRhqQiSH0u8qHzRLRcGIIovT7VxNBbTJlbgHNRoWH7OG24o6xBFJiFyQ0TOYcqUB1VcFZDErFjT4pD7u2XeAh+3udO8L+KrEeE15va8h3N979q1WmPkfAUnw32nCjfC4JKvOtM5/BwFKzyqR8Bw1cKe4Cz0NNvPztAopAgMBAAE="

    private val transport = AndroidUpdateTransport()
    private val sources = listOf(
        URL("https://raw.githubusercontent.com/kobe24o/TimesTableGO/update-feed/updates/latest.json"),
        URL("https://cdn.jsdelivr.net/gh/kobe24o/TimesTableGO@update-feed/updates/latest.json"),
    )

    fun client(): UpdateFeedClient = UpdateFeedClient(sources, transport, publicKey())
    fun downloads(): UpdateDownloadRepository = UpdateDownloadRepository(transport)

    private fun publicKey(): PublicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
    )
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val application = application
    private val installer = AndroidUpdateInstaller(application)
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    fun checkForUpdates(automatic: Boolean) {
        if (_state.value is AppUpdateUiState.Checking || _state.value is AppUpdateUiState.Downloading) return
        _state.value = AppUpdateUiState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { UpdateRuntime.client().fetchLatest(installedVersion()) }
                .fold(
                    onSuccess = { manifest ->
                        withContext(Dispatchers.Main) {
                            if (manifest == null) {
                                _state.value = AppUpdateUiState.UpToDate
                            } else {
                                val network = updateNetwork()
                                if (UpdatePolicy.decide(automatic, network) == UpdateDecision.DownloadNow) download(manifest)
                                else _state.value = AppUpdateUiState.Available(manifest, mobileData = network == UpdateNetwork.Cellular)
                            }
                        }
                    },
                    onFailure = { error -> withContext(Dispatchers.Main) { failed("检查更新失败", error) } },
                )
        }
    }

    fun downloadAvailableUpdate() {
        val available = _state.value as? AppUpdateUiState.Available ?: return
        download(available.manifest)
    }

    fun installDownloadedUpdate() {
        val ready = when (val value = _state.value) {
            is AppUpdateUiState.ReadyToInstall -> value.manifest to value.filePath
            is AppUpdateUiState.NeedsInstallPermission -> value.manifest to value.filePath
            else -> return
        }
        val (manifest, filePath) = ready
        val file = java.io.File(filePath)
        if (!installer.canRequestPackageInstalls()) {
            installer.openInstallPermission()
            _state.value = AppUpdateUiState.NeedsInstallPermission(manifest, filePath)
            return
        }
        installer.install(file)
        _state.value = AppUpdateUiState.Installing
    }

    private fun download(manifest: UpdateManifest) {
        _state.value = AppUpdateUiState.Downloading(0, manifest.android.size)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = UpdateRuntime.downloads().downloadAndVerify(manifest.android, application.cacheDir) { received, total ->
                    _state.value = AppUpdateUiState.Downloading(received, total)
                }
                UpdatePackageVerifier.verify(installer.inspect(file), manifest, installedVersion())
                file
            }.fold(
                onSuccess = { file ->
                    withContext(Dispatchers.Main) {
                        val ready = if (installer.canRequestPackageInstalls()) {
                            AppUpdateUiState.ReadyToInstall(manifest, file.path)
                        } else {
                            AppUpdateUiState.NeedsInstallPermission(manifest, file.path)
                        }
                        _state.value = ready
                    }
                },
                onFailure = { error -> withContext(Dispatchers.Main) { failed("下载更新失败", error) } },
            )
        }
    }

    private fun installedVersion(): AppUpdateVersion {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        return AppUpdateVersion(info.versionName.orEmpty(), info.longVersionCode)
    }

    private fun updateNetwork(): UpdateNetwork {
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return UpdateNetwork.Offline
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpdateNetwork.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpdateNetwork.Cellular
            else -> UpdateNetwork.Offline
        }
    }

    private fun failed(prefix: String, error: Throwable) {
        _state.value = AppUpdateUiState.Error("$prefix：${error.message.orEmpty()}")
    }
}
