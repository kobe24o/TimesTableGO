package com.example.multiplicationcoach.update

import java.net.URL
import org.junit.Assert.fail
import org.junit.Test

class UpdatePackageVerifierTest {
    @Test
    fun acceptsOnlyTheExpectedPackageCertificateAndNewerVersion() {
        UpdatePackageVerifier.verify(
            info = ApkArchiveInfo(UPDATE_PACKAGE_NAME, "0.1.2", 3, UPDATE_CERTIFICATE_SHA256),
            manifest = manifest(),
            installed = AppUpdateVersion("0.1.1", 2),
        )
    }

    @Test
    fun rejectsAHashMatchedForeignPackage() {
        expectRejected {
            UpdatePackageVerifier.verify(
                info = ApkArchiveInfo("com.example.other", "0.1.2", 3, UPDATE_CERTIFICATE_SHA256),
                manifest = manifest(),
                installed = AppUpdateVersion("0.1.1", 2),
            )
        }
    }

    @Test
    fun rejectsAHashMatchedSameVersionApk() {
        expectRejected {
            UpdatePackageVerifier.verify(
                info = ApkArchiveInfo(UPDATE_PACKAGE_NAME, "0.1.2", 3, UPDATE_CERTIFICATE_SHA256),
                manifest = manifest(),
                installed = AppUpdateVersion("0.1.2", 3),
            )
        }
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected update package verification to fail")
        } catch (_: UpdatePackageVerificationException) {
        }
    }

    private fun manifest() = UpdateManifest(
        version = AppUpdateVersion("0.1.2", 3),
        publishedAt = java.time.Instant.parse("2026-10-01T00:00:00Z"),
        releasePageUrl = URL("https://github.com/kobe24o/TimesTableGO/releases/tag/v0.1.2-3"),
        android = UpdateAsset(
            packageName = UPDATE_PACKAGE_NAME,
            fileName = "multiplication-coach-0.1.2-3.apk",
            size = 3,
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            signingCertificateSha256 = UPDATE_CERTIFICATE_SHA256,
            urls = listOf(URL("https://github.com/kobe24o/TimesTableGO/releases/download/v0.1.2-3/multiplication-coach-0.1.2-3.apk")),
        ),
    )
}
