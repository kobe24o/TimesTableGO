import 'dart:io';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:timestablego/data/practice_store.dart';
import 'package:timestablego/domain/practice.dart';

void main() {
  late Directory dir;
  setUp(() async {
    dir = await Directory.systemTemp.createTemp('practice-test');
  });
  tearDown(() async {
    await dir.delete(recursive: true);
  });
  test('fresh installation defaults to English and persists that choice', () async {
    final store = PracticeStore(dir);
    final first = await store.load(legacy: () async => null);
    expect(first.settings.language, 'en');
    expect((await store.load()).settings.language, 'en');
  });
  test('legacy Chinese preference is retained on upgrade', () async {
    final loaded = await PracticeStore(dir).load(
      legacy: () async => {'language': 'ZH', 'attempts': '[]'},
    );
    expect(loaded.settings.language, 'zh');
  });
  test('round trips settings, history and migration marker', () async {
    final store = PracticeStore(dir);
    final data = StoredPractice(
      settings: const Settings(language: 'en', seconds: 5),
      attempts: [
        Attempt(
          problem: const Problem(2, 3),
          transcript: 'six',
          answer: 6,
          timestamp: 10,
        ),
      ],
      migrated: true,
    );
    await store.save(data);
    final loaded = await store.load();
    expect(loaded.settings.language, 'en');
    expect(loaded.attempts.single.correct, true);
    expect(loaded.migrated, true);
  });
  test('corrupt original is retained and cannot be overwritten', () async {
    final f = File('${dir.path}/practice.json');
    await f.writeAsString('{broken');
    final store = PracticeStore(dir);
    await expectLater(store.load(), throwsFormatException);
    await expectLater(store.save(StoredPractice()), throwsStateError);
    expect(await f.readAsString(), '{broken');
  });
  test('imports legacy data once and keeps latest first', () async {
    final store = PracticeStore(dir);
    final legacy = {
      'language': 'EN',
      'answerTimeLimitSeconds': 5,
      'promptAudioEnabled': false,
      'attempts': jsonEncode([
        {
          'a': 2,
          'b': 3,
          'transcript': '6',
          'extractedAnswer': 6,
          'timestamp': 20,
        },
      ]),
    };
    final first = await store.load(legacy: () async => legacy);
    expect(first.settings.language, 'en');
    expect(first.settings.prompt, false);
    expect(first.attempts.single.correct, true);
    final second = await store.load(
      legacy: () async => throw StateError('must not read'),
    );
    expect(second.attempts.length, 1);
  });
}
