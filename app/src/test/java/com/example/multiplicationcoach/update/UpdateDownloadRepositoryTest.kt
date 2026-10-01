package com.example.multiplicationcoach.update

import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun movesOnlyACompleteHashMatchedApkIntoTheUpdatesCache() = runBlocking {
        val repository = UpdateDownloadRepository(UpdateStreamTransport { ByteArrayInputStream("abc".toByteArray()) })

        val apk = repository.downloadAndVerify(asset(size = 3), temporaryFolder.root) { _, _ -> }

        assertTrue(apk.isFile)
        assertEquals("multiplication-coach-0.1.2-3.apk", apk.name)
        assertArrayEquals("abc".toByteArray(), apk.readBytes())
        assertFalse(File(apk.path + ".part").exists())
    }

    @Test
    fun deletesThePartFileWhenTheExpectedSizeDoesNotMatch() = runBlocking {
        val repository = UpdateDownloadRepository(UpdateStreamTransport { ByteArrayInputStream("abc".toByteArray()) })

        try {
            repository.downloadAndVerify(asset(size = 4), temporaryFolder.root) { _, _ -> }
            fail("Expected a size mismatch")
        } catch (_: UpdateDownloadException) {
            assertNoStagedApk()
        }
    }

    @Test
    fun deletesThePartFileWhenTheSha256DoesNotMatch() = runBlocking {
        val repository = UpdateDownloadRepository(UpdateStreamTransport { ByteArrayInputStream("abc".toByteArray()) })

        try {
            repository.downloadAndVerify(asset(sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"), temporaryFolder.root) { _, _ -> }
            fail("Expected a SHA-256 mismatch")
        } catch (_: UpdateDownloadException) {
            assertNoStagedApk()
        }
    }

    private fun assertNoStagedApk() {
        val updates = File(temporaryFolder.root, "updates")
        assertFalse(File(updates, "multiplication-coach-0.1.2-3.apk").exists())
        assertFalse(File(updates, "multiplication-coach-0.1.2-3.apk.part").exists())
    }

    private fun asset(
        size: Long = 3,
        sha256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    ) = UpdateAsset(
        packageName = UPDATE_PACKAGE_NAME,
        fileName = "multiplication-coach-0.1.2-3.apk",
        size = size,
        sha256 = sha256,
        signingCertificateSha256 = UPDATE_CERTIFICATE_SHA256,
        urls = listOf(URL("https://updates.example.com/multiplication-coach-0.1.2-3.apk")),
    )
}
