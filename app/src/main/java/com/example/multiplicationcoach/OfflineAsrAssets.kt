package com.example.multiplicationcoach

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.json.JSONObject

data class AsrAssetEntry(
    val path: String,
    val sha256: String,
)

data class AsrAssetManifest(
    val files: List<AsrAssetEntry>,
)

data class AsrAvailability(
    val available: Boolean,
    val reason: String? = null,
)

object OfflineAsrAssets {
    private const val manifestPath = "asr/manifest.json"

    fun install(context: Context): Pair<AsrAssetManifest, AsrAvailability> {
        val manifest = runCatching { readManifest(context.assets.open(manifestPath)) }
            .getOrElse { return AsrAssetManifest(emptyList()) to AsrAvailability(false, "无法读取离线识别清单") }
        val root = File(context.filesDir, "asr")

        for (entry in manifest.files) {
            val destination = childOrNull(root, entry.path)
                ?: return manifest to AsrAvailability(false, "离线识别文件路径无效")
            if (destination.isFile && sha256(destination).equals(entry.sha256, ignoreCase = true)) continue

            runCatching {
                destination.parentFile?.mkdirs()
                val partial = File(destination.parentFile, ".${destination.name}.partial")
                context.assets.open("asr/${entry.path}").use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                check(sha256(partial).equals(entry.sha256, ignoreCase = true)) {
                    "checksum mismatch"
                }
                Files.move(partial.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }.getOrElse {
                return manifest to AsrAvailability(false, "离线识别文件校验失败：${entry.path}")
            }
        }

        return manifest to verify(manifest, root)
    }

    fun verify(manifest: AsrAssetManifest, root: File): AsrAvailability {
        if (manifest.files.isEmpty()) return AsrAvailability(false, "离线识别清单为空")
        for (entry in manifest.files) {
            val file = childOrNull(root, entry.path)
                ?: return AsrAvailability(false, "离线识别文件路径无效")
            if (!file.isFile) return AsrAvailability(false, "缺少离线识别文件：${entry.path}")
            if (!sha256(file).equals(entry.sha256, ignoreCase = true)) {
                return AsrAvailability(false, "离线识别文件校验失败：${entry.path}")
            }
        }
        return AsrAvailability(true)
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun childOrNull(root: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val rootPath = root.canonicalFile.toPath()
        val child = File(root, relativePath).canonicalFile
        return child.takeIf { it.toPath().startsWith(rootPath) }
    }

    private fun readManifest(input: InputStream): AsrAssetManifest {
        val json = JSONObject(input.bufferedReader().use { it.readText() })
        val files = json.getJSONArray("files")
        return AsrAssetManifest(
            List(files.length()) { index ->
                val entry = files.getJSONObject(index)
                AsrAssetEntry(entry.getString("path"), entry.getString("sha256"))
            },
        )
    }
}
