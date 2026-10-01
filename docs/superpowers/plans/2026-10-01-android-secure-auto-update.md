# Android Secure Auto Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a signed, verified Android self-update flow that downloads and installs only newer APKs published by this repository.

**Architecture:** Pure Kotlin code verifies an RSA-signed update feed and APK identity before Android code stages the APK and opens the system installer. An update view model runs a background startup check, downloads only on Wi-Fi automatically, and exposes manual controls in Settings. GitHub Actions creates immutable Release assets and publishes the signed feed.

**Tech Stack:** Kotlin, coroutines, `org.json`, JCA `SHA256withRSA`, `HttpURLConnection`, Android `PackageManager`/`FileProvider`, Compose, GitHub Actions, OpenSSL.

**Spec:** `docs/superpowers/specs/2026-10-01-android-secure-auto-update-design.md`

## Global Constraints

- Preserve Android minSdk 26, targetSdk 35, arm64-v8a ASR, `allowBackup=false`, and offline practice behavior.
- Feed and APK URLs use HTTPS only; feeds reference immutable versioned Release assets, never `latest`.
- Before installation, verify feed signature, APK byte length and SHA-256, package `com.example.multiplicationcoach`, a newer version, and certificate digest `cdc2f40607af8e8c49060ce693161adf0c25b3df748fa7a2cab5fa6e023f4e36`.
- Download only to `cacheDir/updates`; delete partial, cancelled, or invalid APKs.
- Wi-Fi may download automatically; cellular needs an explicit user action; Android system installer always confirms installation.
- A failed update must not affect question audio, local ASR, microphone use, or practice records.

## Review Focus

- Tampered or unsigned feeds are rejected before version comparison (Task 1).
- HTTP URLs and moving `latest` APK URLs are rejected (Task 1).
- Truncated or altered large APK files are deleted after failed length/hash validation (Task 2).
- Foreign, wrongly signed, or same-version APKs never reach the installer (Task 3).
- Offline/cellular update checks never block practice or start an unwanted download (Task 4).

---

## File Structure

- `app/src/main/java/com/example/multiplicationcoach/update/UpdateManifest.kt`: strict envelope/manifest parsing, version ordering and embedded public key.
- `app/src/main/java/com/example/multiplicationcoach/update/UpdateFeedClient.kt`: HTTPS fetch and RSA signature verification.
- `app/src/main/java/com/example/multiplicationcoach/update/UpdateDownloadRepository.kt`: streaming download, progress, hash validation and cache staging.
- `app/src/main/java/com/example/multiplicationcoach/update/AndroidUpdateInstaller.kt`: archive inspection, install permission and `FileProvider` launch.
- `app/src/main/java/com/example/multiplicationcoach/update/UpdateViewModel.kt`: policy state machine.
- `MainActivity.kt`: one startup check and settings controls.
- `AndroidManifest.xml` and `res/xml/update_file_paths.xml`: required permissions and private cache sharing.
- `.github/workflows/build-apk.yml` and `tools/create_update_envelope.sh`: immutable releases and signed feed.
- `app/src/test/java/com/example/multiplicationcoach/update/`: pure Kotlin security and state tests.

### Task 1: Signed update-feed domain

**Files:**

- Create: `app/src/main/java/com/example/multiplicationcoach/update/UpdateManifest.kt`
- Create: `app/src/main/java/com/example/multiplicationcoach/update/UpdateFeedClient.kt`
- Test: `app/src/test/java/com/example/multiplicationcoach/update/UpdateFeedClientTest.kt`

**Interfaces:**

- Consumes: `UpdateBytesTransport.get(url: URL): ByteArray`.
- Produces: `UpdateManifest`, `UpdateAsset`, `AppUpdateVersion`, and `UpdateFeedClient.fetchLatest(installed: AppUpdateVersion): UpdateManifest?`.

- [ ] **Step 1: Write failing security tests**

```kotlin
@Test fun acceptsTheHighestNewerManifestWithAValidSignature()
@Test fun rejectsAnEnvelopeWhosePayloadWasChangedAfterSigning()
@Test fun rejectsHttpAndMovingLatestAssetUrls()
```

- [ ] **Step 2: Verify the tests are red**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateFeedClientTest`

Expected: FAIL because the update-domain classes do not exist.

- [ ] **Step 3: Implement the exact interfaces**

Implement `SignedUpdateEnvelope.decode(bytes: ByteArray)`, `UpdateManifest.decodePayload(bytes: ByteArray)`, `AppUpdateVersion.isNewerThan(other: AppUpdateVersion)`, and `UpdateFeedClient.fetchLatest(installed: AppUpdateVersion)`. Require protocol 1, valid Base64, HTTPS URLs, no `latest` asset, fixed package/certificate, lowercase 64-digit SHA-256 and positive version code. Use `Signature.getInstance("SHA256withRSA")` against the embedded X.509 public key before parsing the payload.

- [ ] **Step 4: Verify the tests are green**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateFeedClientTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/example/multiplicationcoach/update/UpdateManifest.kt app/src/main/java/com/example/multiplicationcoach/update/UpdateFeedClient.kt app/src/test/java/com/example/multiplicationcoach/update/UpdateFeedClientTest.kt && git commit -m feat-verify-signed-android-update-feed`

### Task 2: APK download and content verification

**Files:**

- Create: `app/src/main/java/com/example/multiplicationcoach/update/UpdateDownloadRepository.kt`
- Test: `app/src/test/java/com/example/multiplicationcoach/update/UpdateDownloadRepositoryTest.kt`

**Interfaces:**

- Consumes: `UpdateAsset`, `HttpConnectionFactory.open(url: URL): HttpURLConnection`, and a cache root.
- Produces: `suspend fun downloadAndVerify(asset: UpdateAsset, cacheDir: File, onProgress: (received: Long, total: Long) -> Unit): File` and `UpdateDownloadException`.

- [ ] **Step 1: Write failing download tests**

```kotlin
@Test fun movesOnlyACompleteHashMatchedApkIntoTheUpdatesCache()
@Test fun deletesThePartFileWhenTheExpectedSizeDoesNotMatch()
@Test fun deletesThePartFileWhenTheSha256DoesNotMatch()
```

- [ ] **Step 2: Verify the tests are red**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateDownloadRepositoryTest`

Expected: FAIL because `UpdateDownloadRepository` does not exist.

- [ ] **Step 3: Implement private-cache download**

Stream to `cacheDir/updates/<versionCode>.apk.part`, report progress, require exact bytes and SHA-256, then atomically rename it to `<versionCode>.apk`. Delete final and part files on cancellation, I/O errors, size mismatch, or digest mismatch.

- [ ] **Step 4: Verify the tests are green**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateDownloadRepositoryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/example/multiplicationcoach/update/UpdateDownloadRepository.kt app/src/test/java/com/example/multiplicationcoach/update/UpdateDownloadRepositoryTest.kt && git commit -m feat-verify-downloaded-android-updates`

### Task 3: Android APK inspection and installation

**Files:**

- Create: `app/src/main/java/com/example/multiplicationcoach/update/AndroidUpdateInstaller.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/update_file_paths.xml`
- Test: `app/src/test/java/com/example/multiplicationcoach/update/UpdatePackageVerifierTest.kt`

**Interfaces:**

- Consumes: a verified APK `File`, `UpdateManifest`, and installed `AppUpdateVersion`.
- Produces: `UpdatePackageVerifier.verify(info: ApkArchiveInfo, manifest: UpdateManifest, installed: AppUpdateVersion): Unit` and `AndroidUpdateInstaller.install(file: File): InstallLaunchResult`.

- [ ] **Step 1: Write failing identity tests**

```kotlin
@Test fun acceptsOnlyTheExpectedPackageCertificateAndNewerVersion()
@Test fun rejectsAHashMatchedForeignPackage()
@Test fun rejectsAHashMatchedSameVersionApk()
```

- [ ] **Step 2: Verify the tests are red**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdatePackageVerifierTest`

Expected: FAIL because the verifier does not exist.

- [ ] **Step 3: Implement verifier and installer**

Implement the pure verifier around `ApkArchiveInfo`. Use `PackageManager.getPackageArchiveInfo` with signing certificates to populate it, compare the signer digest, detect `canRequestPackageInstalls()`, open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` when needed, and launch `ACTION_INSTALL_PACKAGE` using `${applicationId}.update-files`. Add `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, a non-exported `FileProvider`, and cache-path `updates/`.

- [ ] **Step 4: Verify the tests are green**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdatePackageVerifierTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/example/multiplicationcoach/update/AndroidUpdateInstaller.kt app/src/main/AndroidManifest.xml app/src/main/res/xml/update_file_paths.xml app/src/test/java/com/example/multiplicationcoach/update/UpdatePackageVerifierTest.kt && git commit -m feat-install-verified-android-updates`

### Task 4: Update policy and Compose controls

**Files:**

- Create: `app/src/main/java/com/example/multiplicationcoach/update/UpdateViewModel.kt`
- Modify: `app/src/main/java/com/example/multiplicationcoach/MainActivity.kt`
- Test: `app/src/test/java/com/example/multiplicationcoach/update/UpdatePolicyTest.kt`

**Interfaces:**

- Consumes: Tasks 1-3, installed-version provider, and `NetworkTransport`.
- Produces: `StateFlow<UpdateState>`, `checkForUpdate(automatic: Boolean)`, `startDownload()`, `cancelDownload()`, and `installReadyUpdate()`.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun automaticCheckDownloadsOnlyWhenWifiIsActive()
@Test fun cellularUpdateWaitsForAnExplicitDownloadAction()
@Test fun failedAutomaticCheckLeavesPracticeUsableAndRetriable()
```

- [ ] **Step 2: Verify the tests are red**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdatePolicyTest`

Expected: FAIL because the update state machine does not exist.

- [ ] **Step 3: Implement state ownership and UI**

Create a dependency-injected `UpdateViewModel` whose startup check runs in a background coroutine and never throws into `PracticeViewModel`. Call `checkForUpdate(automatic = true)` once from composition; Settings displays current version, “检查更新”, progress/cancel, “下载更新” for cellular and “安装更新” only after all verification succeeds. Wi-Fi download must not auto-open the installer.

- [ ] **Step 4: Verify the tests are green**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdatePolicyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/example/multiplicationcoach/update/UpdateViewModel.kt app/src/main/java/com/example/multiplicationcoach/MainActivity.kt app/src/test/java/com/example/multiplicationcoach/update/UpdatePolicyTest.kt && git commit -m feat-add-android-update-controls`

### Task 5: Immutable Release and signed feed

**Files:**

- Modify: `.github/workflows/build-apk.yml`
- Create: `tools/create_update_envelope.sh`
- Test: `app/src/test/java/com/example/multiplicationcoach/update/UpdateManifestTest.kt`

**Interfaces:**

- Consumes: release APK, `UPDATE_MANIFEST_PRIVATE_KEY_B64`, build version, immutable tag, and certificate digest.
- Produces: `update-feed:updates/latest.json` with a protocol-1 envelope Task 1 accepts.

- [ ] **Step 1: Write a failing CI-envelope round-trip test**

```kotlin
@Test fun acceptsTheCiEnvelopeSchemaSignedWithTheMatchingPublicKey()
```

- [ ] **Step 2: Verify the test is red**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateManifestTest`

Expected: FAIL because the schema is not supported.

- [ ] **Step 3: Configure signing and publication**

Generate a 3072-bit RSA key, embed only its DER public key, set private DER Base64 as repository secret `UPDATE_MANIFEST_PRIVATE_KEY_B64`, then securely delete local private-key files. Actions must verify the APK certificate using `apksigner`, create tag `v<versionName>-<versionCode>`, calculate bytes/SHA-256, and invoke `tools/create_update_envelope.sh`. That script writes JSON payload first, signs its exact bytes with OpenSSL, Base64-encodes without line breaks, and emits the protocol-1 envelope. Publish `updates/latest.json` to orphan `update-feed` only after APK validation succeeds.

- [ ] **Step 4: Verify the test is green**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.multiplicationcoach.update.UpdateManifestTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add .github/workflows/build-apk.yml tools/create_update_envelope.sh app/src/main/java/com/example/multiplicationcoach/update/UpdateManifest.kt app/src/test/java/com/example/multiplicationcoach/update/UpdateManifestTest.kt && git commit -m ci-publish-signed-android-update-feed`

### Task 6: Full verification and publication evidence

**Files:**

- Modify: `README.md`

**Interfaces:**

- Consumes: Tasks 1-5.
- Produces: documented behavior and a verifiable public Release/feed pair.

- [ ] **Step 1: Document Android update behavior**

Document Wi-Fi automatic download, mobile confirmation, system install permission, manual checks, and the local-only learning/recording guarantee.

- [ ] **Step 2: Run all local tests and Release build**

Run: `gradlew.bat clean :app:testDebugUnitTest :app:assembleRelease`

Expected: BUILD SUCCESSFUL with every update test passing.

- [ ] **Step 3: Inspect the local APK**

Run: `aapt dump badging app/build/outputs/apk/release/app-release.apk` and `apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk`

Expected: expected package/version/arm64-v8a and a valid signature.

- [ ] **Step 4: Push and verify published evidence**

Push to `main`, wait for Actions, download the versioned Release APK and fetch `update-feed/updates/latest.json`. Verify feed version, size, SHA-256, package and certificate all equal the downloaded APK.

- [ ] **Step 5: Commit documentation**

Run: `git add README.md && git commit -m docs-describe-android-secure-updates`
