package com.example.multiplicationcoach.update

import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateFeedClientTest {
    @Test
    fun acceptsTheHighestNewerManifestWithAValidSignature() {
        val keys = testKeyPair()
        val source = URL("https://updates.example.com/updates/latest.json")
        val signed = signedEnvelope(payload(versionCode = 3), keys)
        val client = UpdateFeedClient(
            sources = listOf(source),
            transport = UpdateBytesTransport { signed },
            publicKey = keys.public,
        )

        val manifest = client.fetchLatest(AppUpdateVersion(versionName = "0.1.1", versionCode = 2))

        assertEquals(3L, manifest?.version?.versionCode)
        assertEquals("0.1.2", manifest?.version?.versionName)
    }

    @Test
    fun rejectsAnEnvelopeWhosePayloadWasChangedAfterSigning() {
        val keys = testKeyPair()
        val source = URL("https://updates.example.com/updates/latest.json")
        val signed = signedEnvelope(payload(versionCode = 3), keys).toString(Charsets.UTF_8)
        val tampered = signed.replace("\"payload\":\"", "\"payload\":\"A")
        val client = UpdateFeedClient(
            sources = listOf(source),
            transport = UpdateBytesTransport { tampered.toByteArray(Charsets.UTF_8) },
            publicKey = keys.public,
        )

        val manifest = client.fetchLatest(AppUpdateVersion(versionName = "0.1.1", versionCode = 2))

        assertNull(manifest)
    }

    @Test
    fun rejectsHttpAndMovingLatestAssetUrls() {
        val keys = testKeyPair()
        val source = URL("https://updates.example.com/updates/latest.json")
        val httpClient = UpdateFeedClient(
            sources = listOf(source),
            transport = UpdateBytesTransport { signedEnvelope(payload(versionCode = 3, downloadUrl = "http://updates.example.com/app.apk"), keys) },
            publicKey = keys.public,
        )
        val latestClient = UpdateFeedClient(
            sources = listOf(source),
            transport = UpdateBytesTransport { signedEnvelope(payload(versionCode = 3, downloadUrl = "https://github.com/kobe24o/TimesTableGO/releases/download/latest/multiplication-coach-release.apk"), keys) },
            publicKey = keys.public,
        )
        val installed = AppUpdateVersion(versionName = "0.1.1", versionCode = 2)

        assertNull(httpClient.fetchLatest(installed))
        assertNull(latestClient.fetchLatest(installed))
    }

    private fun testKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun signedEnvelope(payload: String, keys: KeyPair): ByteArray {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(payloadBytes)
            sign()
        }
        return """{"protocol":1,"payload":"${Base64.getEncoder().encodeToString(payloadBytes)}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun payload(versionCode: Long, downloadUrl: String = "https://github.com/kobe24o/TimesTableGO/releases/download/v0.1.2-3/multiplication-coach-0.1.2-3.apk"): String =
        """{"versionName":"0.1.2","versionCode":$versionCode,"publishedAt":"2026-10-01T00:00:00Z","releasePageUrl":"https://github.com/kobe24o/TimesTableGO/releases/tag/v0.1.2-3","android":{"packageName":"com.example.multiplicationcoach","fileName":"multiplication-coach-0.1.2-3.apk","size":123,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","signingCertificateSha256":"cdc2f40607af8e8c49060ce693161adf0c25b3df748fa7a2cab5fa6e023f4e36","urls":["$downloadUrl"]}}"""
}
