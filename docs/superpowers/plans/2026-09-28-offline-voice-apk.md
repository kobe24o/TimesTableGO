# Offline Voice APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an arm64 Android APK that plays packaged Chinese prompts and recognizes multiplication answers offline without cloud speech or LLM services.

**Architecture:** Fixed Ogg Opus resources are selected by a pure Kotlin index and played locally. A packaged sherpa-onnx SenseVoice/VAD stack installs its assets to app storage and returns a transcript to a pure local answer parser; Android `SpeechRecognizer` remains a selectable fallback.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Android MediaPlayer/AudioRecord, sherpa-onnx JNI, SenseVoice int8, Silero VAD, JUnit 4, Gradle 8.9.

**Spec:** `docs/superpowers/specs/2026-09-28-offline-voice-design.md`

## Global Constraints

- Ship only `arm64-v8a`; minSdk remains 26 and targetSdk remains 35.
- Bundle SenseVoice int8, tokens, Silero VAD and original license files; never download them after installation.
- Package 22.05 kHz mono Ogg Opus prompt and feedback audio; do not ship a runtime TTS model or call Android TTS.
- Use no network permission, cloud ASR/TTS, cloud credentials, or LLM answer fallback.
- Local ASR is the default; system ASR is an explicit fallback.
- Audio recordings and transcripts stay on-device.

## Review Focus

- `答案是二十四` parses as 24; Task 2 owns this test.
- `三乘四等于十二` is ambiguous and returns no answer; Task 2 owns this test.
- `八十二` and `0` are rejected; Task 2 owns these tests.
- A mismatched asset SHA-256 leaves local ASR unavailable; Task 3 owns this test.
- Missing audio leaves a textual practice flow usable without network playback; Task 5 owns this test.

---

### Task 1: Restore deterministic build and test support

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `./gradlew.bat :app:testDebugUnitTest` and `./gradlew.bat :app:assembleRelease`.

- [ ] **Step 1: Restore the Gradle Wrapper pinned to Gradle 8.9**

Use a repository-local Wrapper and Gradle 8.9 binary distribution; do not rely on a global Gradle installation.

- [ ] **Step 2: Add a red unit-test target**

Add JUnit 4.13.2 and a deliberate failing `BuildSmokeTest` under `app/src/test/java/com/example/multiplicationcoach/`.

- [ ] **Step 3: Verify the test target is red**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*BuildSmokeTest'`

Expected: FAIL due to the intentional assertion.

- [ ] **Step 4: Remove the intentional failure and verify a green baseline**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `build: restore Gradle wrapper and test support`.

### Task 2: Add local answer parsing and audio indexing

**Files:**
- Create: `app/src/main/java/com/example/multiplicationcoach/OfflineVoiceDomain.kt`
- Create: `app/src/test/java/com/example/multiplicationcoach/AnswerTranscriptParserTest.kt`
- Create: `app/src/test/java/com/example/multiplicationcoach/PromptAudioIndexTest.kt`

**Interfaces:**
- Produces: `AnswerTranscriptParser.parse(transcript: String): Int?`.
- Produces: `PromptAudioIndex.problemResourceName(a: Int, b: Int): String`.
- Produces: `PromptAudioIndex.feedbackResourceName(key: FixedFeedback): String`.
- Consumes: no Android or network types.

- [ ] **Step 1: Write failing parser tests**

```kotlin
assertEquals(24, AnswerTranscriptParser.parse("答案是二十四"))
assertEquals(24, AnswerTranscriptParser.parse("二四"))
assertNull(AnswerTranscriptParser.parse("三乘四等于十二"))
assertNull(AnswerTranscriptParser.parse("八十二"))
```

- [ ] **Step 2: Verify parser tests fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*AnswerTranscriptParserTest'`

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement `AnswerTranscriptParser.parse`**

Extract Arabic and Chinese number candidates, including two-digit speech such as `二四`. Return only a single distinct value in `1..81`; otherwise return null.

- [ ] **Step 4: Verify parser tests pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*AnswerTranscriptParserTest'`

Expected: PASS.

- [ ] **Step 5: Write failing resource-index tests**

```kotlin
assertEquals("prompt_3_4", PromptAudioIndex.problemResourceName(3, 4))
assertEquals("feedback_correct", PromptAudioIndex.feedbackResourceName(FixedFeedback.Correct))
assertFailsWith<IllegalArgumentException> { PromptAudioIndex.problemResourceName(0, 4) }
```

- [ ] **Step 6: Implement the audio index and verify domain tests**

Use only lowercase Android raw-resource-safe names and validate multiplicands in `1..9`.

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*AnswerTranscriptParserTest' --tests '*PromptAudioIndexTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

Commit message: `feat: add local answer parsing and audio indexing`.

### Task 3: Package and validate offline ASR

**Files:**
- Create: `app/src/main/java/com/example/multiplicationcoach/OfflineAsrAssets.kt`
- Create: `app/src/main/java/com/example/multiplicationcoach/LocalAsrEngine.kt`
- Create: `app/src/test/java/com/example/multiplicationcoach/OfflineAsrAssetsTest.kt`
- Create: `app/src/main/assets/asr/manifest.json`, `app/src/main/assets/asr/LICENSES/`
- Create: `app/src/main/assets/asr/sensevoice/`, `app/src/main/jniLibs/arm64-v8a/`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `OfflineAsrAssets.verify(manifest: AsrAssetManifest, root: File): AsrAvailability`.
- Produces: `LocalAsrEngine.initialize(context: Context): Result<Unit>` and `suspend fun transcribe(pcm: ByteArray): Result<String>`.
- Consumes: 16 kHz mono PCM and immutable bundled assets.

- [ ] **Step 1: Write failing asset-verification tests**

```kotlin
assertTrue(OfflineAsrAssets.verify(manifest, root).available)
assertFalse(OfflineAsrAssets.verify(manifestWithWrongHash, root).available)
```

- [ ] **Step 2: Verify asset tests fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*OfflineAsrAssetsTest'`

Expected: FAIL because the verifier does not exist.

- [ ] **Step 3: Implement atomic asset installation and checksum verification**

Copy every ASR asset from `assets/asr/` to `filesDir/asr/` through a temporary file, SHA-256 check it, then rename. Return a displayable unavailable reason if any file is missing or mismatched.

- [ ] **Step 4: Verify asset tests pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*OfflineAsrAssetsTest'`

Expected: PASS.

- [ ] **Step 5: Add verified upstream artifacts**

Add the pinned SenseVoice int8 model, `tokens.txt`, Silero VAD, sherpa-onnx Kotlin bindings, arm64 JNI libraries, and unmodified licenses. Write every SHA-256 to `manifest.json`; configure `ndk.abiFilters += "arm64-v8a"`.

- [ ] **Step 6: Implement `LocalAsrEngine` and build debug APK**

Initialize SenseVoice with `language = "zh"` and inverse text normalization. Pass VAD-selected audio only; no speech returns an empty transcript. Run `./gradlew.bat :app:assembleDebug`; inspect the APK for model assets, licenses, and arm64 libraries.

- [ ] **Step 7: Commit**

Commit message: `feat: package offline SenseVoice recognition`.

### Task 4: Generate and package fixed Chinese voice resources

**Files:**
- Create: `tools/fixed_voice_lines.txt`, `tools/generate_fixed_voice_audio.ps1`
- Create: `app/src/main/res/raw/prompt_1_1.ogg` through `prompt_9_9.ogg`
- Create: `app/src/main/res/raw/feedback_correct.ogg`, `feedback_incorrect.ogg`, `feedback_listen.ogg`, `feedback_no_speech.ogg`
- Create: `app/src/main/assets/licenses/cosyvoice-notice.txt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `resource_name<TAB>Chinese text` source lines.
- Produces: 22.05 kHz mono Ogg Opus at `res/raw/<resource_name>.ogg`.

- [ ] **Step 1: Create the canonical 85-line source manifest**

Include 81 existing question wordings (`三乘以四等于多少？`) using `PromptAudioIndex` names and four fixed feedback messages.

- [ ] **Step 2: Add the local generation script**

Use local CosyVoice-300M-SFT once per source line, then convert output to 22.05 kHz mono Ogg Opus. Fail on empty or misnamed output; never call a cloud TTS API.

- [ ] **Step 3: Generate and validate resources**

Run the script, then verify 85 nonempty Ogg files: 81 `prompt_<a>_<b>` files and four feedback files.

- [ ] **Step 4: Add attribution and regeneration instructions**

Record CosyVoice model revision, Apache-2.0 notice, generation date, and the regeneration command. Do not add CosyVoice weights to the repository or APK.

- [ ] **Step 5: Commit**

Commit message: `feat: add packaged Chinese practice audio`.

### Task 5: Replace cloud speech flow in the app

**Files:**
- Modify: `app/src/main/java/com/example/multiplicationcoach/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `README.md`
- Create: `app/src/test/java/com/example/multiplicationcoach/OfflineVoiceFlowTest.kt`

**Interfaces:**
- Consumes: `LocalAsrEngine`, `AnswerTranscriptParser`, `PromptAudioIndex`, `PcmRecorder`, and `SpeechRecognizer`.
- Produces: attempts with `checkedBy = "local"` or `"system"`; no `baidu`, `azure`, or `llm` provider exists.

- [ ] **Step 1: Write failing local-flow tests**

Assert that `答案是二十四` verifies locally, ambiguous text produces no extracted answer, and a missing raw resource leaves a textual feedback state rather than selecting a network speaker.

- [ ] **Step 2: Verify flow tests fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests '*OfflineVoiceFlowTest'`

Expected: FAIL because local verifier/player adapters do not exist.

- [ ] **Step 3: Extract `LocalAnswerVerifier` and `FixedAudioPlayer`**

Both adapters are testable outside Compose. `FixedAudioPlayer` uses `PromptAudioIndex`, catches resource-player failure, and leaves practice usable with text feedback.

- [ ] **Step 4: Route both ASR providers through the local parser**

Default `AppSettings.asrProvider` to `ASR_LOCAL`. Local mode records PCM then invokes `LocalAsrEngine`; system mode retains `SpeechRecognizer`. Both use `LocalAnswerVerifier`. Remove `AnswerVerifier`, Baidu/Azure clients, Baidu TTS, OkHttp uses, and all LLM-generated messages.

- [ ] **Step 5: Replace cloud settings and permission**

Expose only “本地 ASR” and “系统 ASR”, include local readiness/fallback copy, remove key fields and LLM UI, and remove `android.permission.INTERNET`.

- [ ] **Step 6: Verify the app code**

Run `./gradlew.bat :app:testDebugUnitTest`, then `rg -n -i "baidu|azure|openai|okhttp|https?://|INTERNET|api.?key|secret" app README.md`.

Expected: tests PASS; no cloud implementation, key settings, or network permission remains.

- [ ] **Step 7: Commit**

Commit message: `feat: use packaged offline voice flow`.

### Task 6: Produce and verify the release APK

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: `app/build/outputs/apk/release/app-release.apk` and recorded evidence.

- [ ] **Step 1: Run complete verification**

Run: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease`

Expected: PASS.

- [ ] **Step 2: Inspect APK content and ABI**

Use `jar tf` or APK Analyzer to verify all 85 Ogg files, ASR manifest/model/tokens/VAD/licenses, and only `lib/arm64-v8a/` native libraries.

- [ ] **Step 3: Install and exercise offline mode**

On an arm64 Android device with Wi-Fi/mobile data disabled, install the release APK, grant microphone permission, complete one local spoken answer, and confirm a saved attempt. Exercise system-ASR fallback separately if supported.

- [ ] **Step 4: Record evidence and commit**

Add APK version, SHA-256, size, test summary, and device result to `README.md`. If no arm64 device is available, explicitly record runtime verification as pending. Commit message: `build: publish offline voice APK evidence`.
