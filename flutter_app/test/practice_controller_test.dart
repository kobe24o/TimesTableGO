import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:timestablego/domain/practice.dart';
import 'package:timestablego/practice/practice_controller.dart';
import 'package:timestablego/services/voice_services.dart';

class FakeVoice implements VoiceServices {
  final prompt = Completer<void>();
  final recognition = Completer<String>();
  int recordings = 0;
  bool granted = true;
  Completer<void>? cancellation;
  @override
  Future<void> initialize(String language) async {}
  @override
  Future<bool> permission() async => granted;
  @override
  Future<void> play(String name) =>
      name.startsWith('prompt') ? prompt.future : Future.value();
  @override
  Future<void> begin() async {
    recordings++;
  }

  @override
  Future<Float32List> finish() async => Float32List(16000);
  @override
  Future<String> transcribe(Float32List samples) => recognition.future;
  @override
  Future<void> cancel() async {
    await cancellation?.future;
  }

  @override
  Future<void> dispose() async {}
}

class QuickVoice extends FakeVoice {
  QuickVoice(this.answer);
  final String answer;
  final feedback = Completer<void>();
  @override
  Future<void> play(String name) =>
      name.startsWith('prompt') ? Future.value() : feedback.future;
  @override
  Future<String> transcribe(Float32List samples) async => answer;
}

void main() {
  test('waits for prompt, starts countdown only after capture, stop invalidates result', () async {
    final voice = FakeVoice();
    final c = PracticeController(
      voice: voice,
      settings: const Settings(seconds: 1),
      nextProblem: () => const Problem(3, 7),
    );
    final run = c.start();
    await Future<void>.delayed(Duration.zero);
    expect(voice.recordings, 0);
    expect(c.phase, PracticePhase.prompting);
    voice.prompt.complete();
    await Future<void>.delayed(Duration.zero);
    expect(voice.recordings, 1);
    expect(c.remaining, 1);
    await Future<void>.delayed(const Duration(milliseconds: 1100));
    expect(c.phase, PracticePhase.recognizing);
    await c.stop();
    voice.recognition.complete('21');
    await run;
    expect(c.attempts, isEmpty);
    expect(c.running, false);
    c.dispose();
  });
  test('restart waits for old recording teardown', () async {
    final voice = FakeVoice();
    final c = PracticeController(
      voice: voice,
      nextProblem: () => const Problem(3, 7),
    );
    final first = c.start();
    await Future<void>.delayed(Duration.zero);
    voice.cancellation = Completer<void>();
    final stopping = c.stop();
    final second = c.start();
    await Future<void>.delayed(Duration.zero);
    expect(c.running, false);
    voice.cancellation!.complete();
    await stopping;
    await Future<void>.delayed(Duration.zero);
    expect(c.running, true);
    expect(c.phase, PracticePhase.prompting);
    await c.stop();
    await first;
    await second;
    c.dispose();
  });
  test('silence repeats without counting an incorrect attempt', () async {
    final voice = QuickVoice('');
    final c = PracticeController(
      voice: voice,
      settings: const Settings(seconds: 1),
      nextProblem: () => const Problem(3, 7),
    );
    final run = c.start();
    await Future<void>.delayed(const Duration(milliseconds: 1150));
    expect(c.phase, PracticePhase.feedback);
    expect(c.attempts, isEmpty);
    expect(c.problem!.key, '3:7');
    await c.stop();
    voice.feedback.complete();
    await run;
    c.dispose();
  });
  test('correct feedback finishes before next question', () async {
    final voice = QuickVoice('21');
    final c = PracticeController(
      voice: voice,
      settings: const Settings(seconds: 1),
      nextProblem: () => const Problem(3, 7),
    );
    final run = c.start();
    await Future<void>.delayed(const Duration(milliseconds: 1150));
    expect(c.phase, PracticePhase.feedback);
    expect(c.attempts.single.correct, true);
    expect(voice.recordings, 1);
    await c.stop();
    voice.feedback.complete();
    await run;
    c.dispose();
  });
  test('permission refusal does not start recording', () async {
    final voice = FakeVoice()..granted = false;
    final c = PracticeController(voice: voice);
    await c.start();
    expect(c.phase, PracticePhase.error);
    expect(voice.recordings, 0);
    expect(c.attempts, isEmpty);
    c.dispose();
  });
}
