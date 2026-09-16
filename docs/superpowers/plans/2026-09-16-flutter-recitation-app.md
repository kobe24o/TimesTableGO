# Flutter 乘法口诀背诵 App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a polished Android/iOS Flutter multiplication-recitation app using device ASR and one parent-configured LLM.

**Architecture:** Flutter Material 3 provides practice, progress and parent flows. Testable domain services isolate device speech, one OpenAI-compatible judge, secure credentials, local history and a disabled advertising seam.

**Tech Stack:** Flutter stable/Dart 3.12+, `speech_to_text: ^7.5.0`, `flutter_secure_storage: ^11.1.1`, `shared_preferences: ^2.5.5`, `http`, `flutter_test`.

**Spec:** `docs/superpowers/specs/2026-09-16-multiplication-recitation-design.md`

## Global Constraints

- Build one Flutter codebase for Android and iOS, with `zh-CN` recognition.
- Retain no Baidu, Azure, multiple ASR providers, user-entered ASR keys, or cloud-TTS dependency.
- Let parents configure exactly one OpenAI-compatible API base, model name and API Key.
- Store API Key only in `flutter_secure_storage`; do not serialize it in normal settings, history, logs or exports.
- Do not retain raw audio; LLM requests contain only target text, ASR text and `zh-CN`.
- Require LLM JSON fields `correct`, `status`, `heard`, `feedback`; fall back to deterministic local comparison on any failure.
- Keep advertising disabled and contain no production AdMob identifier. Future ads appear only after a completed group behind the parent gate.
- Use specific, encouraging Chinese feedback and never shame the learner.
- Every production behavior starts with a failing test.

## File Structure

- `pubspec.yaml`, `analysis_options.yaml`: Flutter dependencies and linting.
- `lib/main.dart`, `lib/app.dart`: bootstrap, theme and three-tab shell.
- `lib/core/models/recitation.dart`: immutable target, attempt, judgement, setting and state data.
- `lib/core/normalization/chinese_recitation_normalizer.dart`: local Chinese-text comparison.
- `lib/core/services/`: speech, LLM, secure credentials, persistence and advertising boundaries.
- `lib/features/practice/`: practice controller and recitation UI.
- `lib/features/progress/`: local progress and teacher encouragement UI.
- `lib/features/parent/`: arithmetic parent gate and configuration form.
- `test/`: unit and widget coverage mirroring `lib/`.
- `docs/release/`: privacy, Android and App Store release material.

## Tasks

### Task 1: Flutter bootstrap and permission shells

**Files:**
- Create: `pubspec.yaml`, `analysis_options.yaml`, `lib/main.dart`, `lib/app.dart`, `test/app_test.dart`, `ios/Runner/Info.plist`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Remove only after equivalent Flutter build is verified: Kotlin/Gradle app sources superseded by Flutter.

**Interfaces:** Produces `Future<void> bootstrapApp()` and `const MultiplicationRecitationApp()`.

- [ ] **Step 1: Write the failing shell test**

```dart
testWidgets('renders today practice entry point', (tester) async {
  await tester.pumpWidget(const MultiplicationRecitationApp());
  expect(find.text('今日背诵'), findsOneWidget);
  expect(find.text('开始背诵'), findsOneWidget);
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/app_test.dart`

Expected: FAIL because the app widget is absent.

- [ ] **Step 3: Implement the smallest app shell**

```dart
Future<void> bootstrapApp() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MultiplicationRecitationApp());
}

class MultiplicationRecitationApp extends StatelessWidget {
  const MultiplicationRecitationApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: ThemeData(useMaterial3: true, colorSchemeSeed: const Color(0xff2864dc)),
    home: const Scaffold(body: Center(child: Text('今日背诵'))),
  );
}
```

Add the stack dependencies listed in the header. Add Android microphone permission and Chinese iOS microphone/speech purpose strings.

- [ ] **Step 4: Verify green**

Run: `dart format lib test && flutter analyze && flutter test test/app_test.dart`

Expected: all commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add pubspec.yaml analysis_options.yaml lib test android/app/src/main/AndroidManifest.xml ios/Runner/Info.plist && git commit -m "feat: bootstrap Flutter recitation app"
```

### Task 2: Recitation models and local fallback judgement

**Files:**
- Create: `lib/core/models/recitation.dart`, `lib/core/normalization/chinese_recitation_normalizer.dart`, `test/core/normalization/chinese_recitation_normalizer_test.dart`

**Interfaces:** Produces `RecitationTarget(left, right)`, `Judgement`, `JudgementStatus`, `JudgementSource`, and `LocalRecitationJudge.judge(RecitationTarget, String)`.

- [ ] **Step 1: Write failing local judgement tests**

```dart
group('LocalRecitationJudge', () {
  final target = RecitationTarget(left: 7, right: 8);
  test('accepts Chinese and Arabic numeral variants', () {
    expect(LocalRecitationJudge().judge(target, '七乘八等于56').correct, isTrue);
  });
  test('identifies a missing ending', () {
    expect(LocalRecitationJudge().judge(target, '七八五').status, JudgementStatus.missing);
  });
  test('identifies changed order', () {
    expect(LocalRecitationJudge().judge(target, '五十六七八').status, JudgementStatus.reordered);
  });
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/core/normalization/chinese_recitation_normalizer_test.dart`

Expected: FAIL because models and judge are absent.

- [ ] **Step 3: Implement minimal normalizer and data models**

```dart
enum JudgementStatus { correct, missing, wrong, reordered, unclear }
enum JudgementSource { local, llm }

String normalizeRecitation(String raw) => raw
  .replaceAll(RegExp(r'[，。！？、\\s乘以等于x×]'), '')
  .replaceAll('56', '五十六');
```

Map Chinese/Arabic numerals to the target canonical text. Canonical equality is correct; canonical prefix is missing; an anagram with changed order is reordered; blank is unclear; otherwise wrong. Feedback includes one calm next action.

- [ ] **Step 4: Verify green**

Run: `flutter test test/core/normalization/chinese_recitation_normalizer_test.dart && flutter analyze`

Expected: all tests pass and analyzer has no diagnostics.

- [ ] **Step 5: Commit**

```bash
git add lib/core/models/recitation.dart lib/core/normalization/chinese_recitation_normalizer.dart test/core/normalization/chinese_recitation_normalizer_test.dart && git commit -m "feat: add local recitation judgement"
```

### Task 3: One configured model, strict contract and safe fallback

**Files:**
- Create: `lib/core/services/recitation_judge.dart`, `test/core/services/recitation_judge_test.dart`

**Interfaces:** Consumes Task 2 models. Produces `OpenAiCompatibleRecitationJudge({required Uri endpoint, required String model, required String apiKey, required http.Client client})` with `Future<Judgement> judge(RecitationTarget target, String transcript)`.

- [ ] **Step 1: Write failing contract tests**

```dart
test('returns valid LLM json judgement', () async {
  final client = FakeHttpClient.json(200, responseWithContent(
    '{"correct":false,"status":"missing","heard":"七八五","feedback":"补上十六就完整啦。"}',
  ));
  final result = await judgeWith(client).judge(RecitationTarget(left: 7, right: 8), '七八五');
  expect(result.status, JudgementStatus.missing);
  expect(result.source, JudgementSource.llm);
});

test('uses local judge when LLM content is invalid', () async {
  final result = await judgeWith(FakeHttpClient.json(200, '{"choices":[]}'))
      .judge(RecitationTarget(left: 7, right: 8), '七八五十六');
  expect(result.source, JudgementSource.local);
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/core/services/recitation_judge_test.dart`

Expected: FAIL because the judge is absent.

- [ ] **Step 3: Implement request, parse and fallback**

```dart
final body = jsonEncode({
  'model': model,
  'temperature': 0,
  'response_format': {'type': 'json_object'},
  'messages': [
    {'role': 'system', 'content': systemPrompt},
    {'role': 'user', 'content': jsonEncode({'target': target.canonicalText, 'transcript': transcript, 'language': 'zh-CN'})},
  ],
});
```

Apply a 10-second timeout. Parse only `choices[0].message.content`; validate all four keys, map unknown status to `unclear`, trim `heard`, and limit feedback to 80 characters. HTTP, timeout, malformed JSON and schema errors call the Task 2 judge.

- [ ] **Step 4: Verify green**

Run: `flutter test test/core/services/recitation_judge_test.dart && flutter test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add lib/core/services/recitation_judge.dart test/core/services/recitation_judge_test.dart && git commit -m "feat: add single-model recitation judge"
```

### Task 4: Secure settings, device ASR and practice controller

**Files:**
- Create: `lib/core/services/secure_credential_store.dart`, `lib/core/services/settings_repository.dart`, `lib/core/services/speech_recognition_service.dart`, `lib/core/services/device_speech_recognition_service.dart`, `lib/features/practice/practice_controller.dart`
- Create: `test/core/services/secure_credential_store_test.dart`, `test/core/services/settings_repository_test.dart`, `test/core/services/device_speech_recognition_service_test.dart`, `test/features/practice/practice_controller_test.dart`

**Interfaces:** Produces `CredentialStore`, `SettingsRepository`, `SpeechRecognitionService`, `SpeechAvailability`, `PracticeController`, `PracticeViewState`, and `PracticePhase`.

- [ ] **Step 1: Write failing persistence/ASR/controller tests**

```dart
test('saves API key only through secure storage', () async {
  final store = FakeCredentialStore();
  await store.writeApiKey('secret-key');
  expect(await store.readApiKey(), 'secret-key');
});

test('forwards partial and final device recognition', () async {
  final platform = FakeSpeechPlatform(available: true);
  final values = <String>[];
  await DeviceSpeechRecognitionService(platform).start(
    onPartial: values.add, onFinal: (value) => values.add('final:$value'),
  );
  platform.emitPartial('七八');
  platform.emitFinal('七八五十六');
  expect(values, ['七八', 'final:七八五十六']);
});

test('makes retry available when speech is unavailable', () async {
  final harness = PracticeHarness(speechAvailability: SpeechAvailability.unavailable);
  await harness.controller.startListening();
  expect(harness.controller.state.phase, PracticePhase.speechUnavailable);
  expect(harness.controller.state.canRetry, isTrue);
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/core/services test/features/practice/practice_controller_test.dart`

Expected: FAIL because services and controller are absent.

- [ ] **Step 3: Implement production boundaries**

```dart
abstract interface class CredentialStore {
  Future<String?> readApiKey();
  Future<void> writeApiKey(String value);
  Future<void> deleteApiKey();
}

enum PracticePhase { idle, ready, listening, judging, correct, needsRetry, speechUnavailable }
```

Use `FlutterSecureStorage` only in the credential implementation and `SharedPreferencesAsync` for model base/model name, mode, duration and maximum 300 attempts. Adapt `speech_to_text` with `localeId: 'zh_CN'`, partial results, idempotent stop, and no disk audio. The controller shuffles all 81 combinations, weights weak targets after the first cycle, preserves transcript, records after final judgement, and maps empty transcript to `unclear`.

- [ ] **Step 4: Verify green**

Run: `flutter test test/core/services test/features/practice/practice_controller_test.dart && flutter analyze && flutter build apk --debug`

Expected: tests, analyzer and Android debug build exit 0.

- [ ] **Step 5: Commit**

```bash
git add lib/core/services lib/features/practice/practice_controller.dart test/core/services test/features/practice/practice_controller_test.dart android/app/src/main/AndroidManifest.xml ios/Runner/Info.plist && git commit -m "feat: add secure practice services"
```

### Task 5: Polished practice, progress and parent UI with disabled AdMob seam

**Files:**
- Modify: `lib/app.dart`
- Create: `lib/features/practice/practice_screen.dart`, `lib/features/progress/progress_screen.dart`, `lib/features/parent/parent_gate.dart`, `lib/features/parent/parent_settings_screen.dart`, `lib/core/services/ad_service.dart`
- Create: `test/features/practice/practice_screen_test.dart`, `test/features/parent/parent_gate_test.dart`, `test/features/parent/parent_settings_screen_test.dart`, `test/core/services/ad_service_test.dart`

**Interfaces:** Consumes Task 4 controller/repositories. Produces `PracticeScreen`, `ProgressScreen`, `ParentGate`, `ParentSettingsScreen`, `AdService` and `DisabledAdService`.

- [ ] **Step 1: Write failing UI/advertising tests**

```dart
testWidgets('shows transcript and retry for a missing judgement', (tester) async {
  final controller = FakePracticeController.withState(
    transcript: '七八五', phase: PracticePhase.needsRetry,
    judgement: Judgement.missing(heard: '七八五', feedback: '补上十六就完整啦。'),
  );
  await tester.pumpWidget(testApp(PracticeScreen(controller: controller)));
  expect(find.text('我听到：七八五'), findsOneWidget);
  expect(find.text('再背一次'), findsOneWidget);
});

testWidgets('opens parent settings only after correct arithmetic answer', (tester) async {
  var unlocked = false;
  await tester.pumpWidget(testApp(ParentGate(onUnlocked: () => unlocked = true)));
  await tester.enterText(find.byType(TextField), '12');
  await tester.tap(find.text('继续'));
  expect(unlocked, isTrue);
});

test('disabled service requests no advertisement', () async {
  expect(await DisabledAdService().showCompletionReward(), AdResult.disabled);
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/features/practice/practice_screen_test.dart test/features/parent test/core/services/ad_service_test.dart`

Expected: FAIL because feature widgets and ad seam are absent.

- [ ] **Step 3: Implement accessible child-friendly UI**

```dart
FilledButton.icon(
  onPressed: state.canListen ? controller.startListening : null,
  icon: const Icon(Icons.mic_rounded),
  label: const Text('按住背诵'),
)

final class DisabledAdService implements AdService {
  @override
  Future<AdResult> showCompletionReward() async => AdResult.disabled;
}
```

Use a high-contrast large canonical card, transcript panel, accessible recording labels, no automatic advance after errors, three bottom destinations `练习`/`进度`/`家长`, local weak-target/streak display, and an arithmetic parent gate regenerated on every entry. Settings validate HTTPS base and nonempty model; reveal no stored key. Label disabled ad controls `广告功能将在 AdMob 配置完成后开放`.

- [ ] **Step 4: Verify green**

Run: `flutter test && flutter analyze && flutter build apk --debug`

Expected: all commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add lib/app.dart lib/features lib/core/services/ad_service.dart test/features test/core/services/ad_service_test.dart && git commit -m "feat: deliver recitation practice experience"
```

### Task 6: Store-release material and final platform verification

**Files:**
- Create: `docs/release/privacy-policy.md`, `docs/release/google-play-data-safety.md`, `docs/release/app-store-privacy-and-listing.md`, `test/release_documentation_test.dart`
- Modify: `README.md`, `.github/workflows/build-apk.yml`

**Interfaces:** Produces reviewer-facing disclosure for ASR, local history, secure user-provided LLM configuration, text-only requests and disabled ads; CI produces Android AAB.

- [ ] **Step 1: Write failing release-document test**

```dart
test('privacy policy declares no raw audio retention', () {
  final policy = File('docs/release/privacy-policy.md').readAsStringSync();
  expect(policy, contains('不保存原始录音'));
  expect(policy, contains('目标口诀和识别文本'));
});
```

- [ ] **Step 2: Verify red**

Run: `flutter test test/release_documentation_test.dart`

Expected: FAIL because release material is absent.

- [ ] **Step 3: Implement truthful documents and CI**

```markdown
## 模型服务

仅当家长主动配置并开启模型功能时，应用会向该服务发送目标口诀和语音识别后的文本。应用不发送原始录音、孩子姓名或广告标识符。
```

Update CI to run `flutter pub get`, `flutter analyze`, `flutter test` and `flutter build appbundle`. Include App Store Chinese description, age-rating checklist, screenshots checklist and review note that advertising is disabled. Include no personal-data claim inconsistent with actual SDKs.

- [ ] **Step 4: Verify Android release**

Run: `flutter test test/release_documentation_test.dart && flutter analyze && flutter test && flutter build appbundle`

Expected: all checks exit 0 and `build/app/outputs/bundle/release/app-release.aab` exists.

- [ ] **Step 5: Verify iOS in a signed Apple environment**

Run: `flutter build ipa --release`

Expected: IPA archive after the user selects their Apple team/provisioning profile in Xcode.

- [ ] **Step 6: Commit**

```bash
git add docs/release README.md .github/workflows/build-apk.yml test/release_documentation_test.dart && git commit -m "docs: prepare mobile store release material"
```

## Plan Self-Review

- Spec coverage: Tasks 1 and 5 deliver cross-platform Flutter UX; Tasks 2 and 3 deliver judgement; Task 4 handles secure credentials, ASR and history; Task 5 handles parent gating and disabled advertising; Task 6 prepares privacy, CI and stores.
- Placeholder scan: the plan has no deferred implementation markers and every task specifies tests, commands, files and interfaces.
- Type consistency: `RecitationTarget`, `Judgement`, `LocalRecitationJudge`, `CredentialStore`, `SpeechRecognitionService`, `PracticeController` and `AdService` are defined before later task consumption.

