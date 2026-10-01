package com.example.multiplicationcoach.update

import java.net.URL
import java.time.Instant
import java.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

const val UPDATE_PACKAGE_NAME = "com.example.multiplicationcoach"
const val UPDATE_CERTIFICATE_SHA256 = "cdc2f40607af8e8c49060ce693161adf0c25b3df748fa7a2cab5fa6e023f4e36"

data class AppUpdateVersion(val versionName: String, val versionCode: Long) {
    fun isNewerThan(installed: AppUpdateVersion): Boolean = versionCode > installed.versionCode
}

data class UpdateAsset(
    val packageName: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val urls: List<URL>,
)

data class UpdateManifest(
    val version: AppUpdateVersion,
    val publishedAt: Instant,
    val releasePageUrl: URL,
    val android: UpdateAsset,
) {
    companion object {
        fun decodePayload(bytes: ByteArray): UpdateManifest {
            val value = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val versionName = value.requiredString("versionName")
            val versionCode = value.requiredLong("versionCode")
            require(versionCode > 0) { "versionCode must be positive" }
            val publishedAt = Instant.parse(value.requiredString("publishedAt"))
            val releasePageUrl = value.requiredHttpsUrl("releasePageUrl")
            val android = value.requiredObject("android")
            val packageName = android.requiredString("packageName")
            val fileName = android.requiredString("fileName")
            val size = android.requiredLong("size")
            val sha256 = android.requiredString("sha256")
            val certificate = android.requiredString("signingCertificateSha256")
            val urls = android.requiredAssetUrls("urls")

            require(packageName == UPDATE_PACKAGE_NAME) { "Unexpected package name" }
            require(fileName.endsWith(".apk", ignoreCase = true)) { "APK filename is required" }
            require(size > 0) { "APK size must be positive" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK SHA-256" }
            require(certificate == UPDATE_CERTIFICATE_SHA256) { "Unexpected APK certificate" }

            return UpdateManifest(
                version = AppUpdateVersion(versionName, versionCode),
                publishedAt = publishedAt,
                releasePageUrl = releasePageUrl,
                android = UpdateAsset(packageName, fileName, size, sha256, certificate, urls),
            )
        }
    }
}

data class SignedUpdateEnvelope(val payloadBytes: ByteArray, val signatureBytes: ByteArray) {
    companion object {
        fun decode(bytes: ByteArray): SignedUpdateEnvelope {
            val value = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(value.requiredInt("protocol") == 1) { "Unsupported update protocol" }
            val payload = value.requiredString("payload")
            val signature = value.requiredString("signature")
            val payloadBytes = Base64.getDecoder().decode(payload)
            val signatureBytes = Base64.getDecoder().decode(signature)
            require(payloadBytes.isNotEmpty() && signatureBytes.isNotEmpty()) { "Update envelope is empty" }
            return SignedUpdateEnvelope(payloadBytes, signatureBytes)
        }
    }
}

private fun JsonObject.requiredString(name: String): String = get(name).asString.also {
    require(it.isNotBlank()) { "$name is required" }
}

private fun JsonObject.requiredLong(name: String): Long = get(name).asLong

private fun JsonObject.requiredInt(name: String): Int = get(name).asInt

private fun JsonObject.requiredObject(name: String): JsonObject = get(name).asJsonObject

private fun JsonObject.requiredHttpsUrl(name: String): URL = URL(requiredString(name)).also { url ->
    require(url.protocol == "https" && url.host.isNotBlank()) { "$name must use HTTPS" }
}

private fun JsonObject.requiredAssetUrls(name: String): List<URL> {
    val values: JsonArray = get(name).asJsonArray
    require(values.size() in 1..2) { "At least one APK URL is required" }
    return List(values.size()) { index ->
        URL(values[index].asString).also { url ->
            require(url.protocol == "https" && url.host.isNotBlank()) { "APK URL must use HTTPS" }
            require(!url.path.contains("/latest/")) { "APK URL must be immutable" }
        }
    }
}
