package com.example.multiplicationcoach.update

import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

fun interface UpdateStreamTransport {
    fun open(url: URL): InputStream
}

class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UpdateDownloadRepository(private val transport: UpdateStreamTransport) {
    suspend fun downloadAndVerify(
        asset: UpdateAsset,
        cacheDir: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updates = File(cacheDir, "updates")
        val staged = File(updates, asset.fileName)
        val partial = File(staged.path + ".part")
        updates.mkdirs()
        staged.delete()
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            transport.open(asset.urls.first()).use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    if (received + read > asset.size) throw UpdateDownloadException("APK exceeds the signed size")
                    received += read
                        onProgress(received, asset.size)
                    }
                }
            }
            if (received != asset.size) throw UpdateDownloadException("APK size mismatch")
            if (digest.digest().toHex() != asset.sha256) throw UpdateDownloadException("APK SHA-256 mismatch")
            if (!partial.renameTo(staged)) throw UpdateDownloadException("Unable to stage update APK")
            staged
        } catch (error: Throwable) {
            partial.delete()
            staged.delete()
            if (error is UpdateDownloadException) throw error
            throw UpdateDownloadException("Unable to download update APK", error)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
