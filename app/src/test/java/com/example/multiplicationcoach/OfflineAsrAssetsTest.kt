package com.example.multiplicationcoach

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAsrAssetsTest {
    @Test
    fun acceptsEveryBundledFileWithTheExpectedDigest() {
        val root = Files.createTempDirectory("asr-assets").toFile()
        val model = root.resolve("sensevoice/model.int8.onnx")
        model.parentFile!!.mkdirs()
        model.writeText("model bytes")
        val manifest = AsrAssetManifest(
            listOf(AsrAssetEntry("sensevoice/model.int8.onnx", OfflineAsrAssets.sha256(model))),
        )

        assertTrue(OfflineAsrAssets.verify(manifest, root).available)
    }

    @Test
    fun rejectsAMismatchedDigest() {
        val root = Files.createTempDirectory("asr-assets").toFile()
        val model = root.resolve("sensevoice/model.int8.onnx")
        model.parentFile!!.mkdirs()
        model.writeText("model bytes")
        val manifest = AsrAssetManifest(
            listOf(AsrAssetEntry("sensevoice/model.int8.onnx", "0".repeat(64))),
        )

        assertFalse(OfflineAsrAssets.verify(manifest, root).available)
    }
}
