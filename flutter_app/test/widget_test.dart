import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:timestablego/ui/app.dart';
import 'package:timestablego/practice/practice_controller.dart';
import 'package:timestablego/services/voice_services.dart';

class SilentVoice implements VoiceServices {
  @override
  Future<void> initialize(String l) async {}
  @override
  Future<bool> permission() async => false;
  @override
  Future<void> play(String n) async {}
  @override
  Future<void> begin() async {}
  @override
  Future<Float32List> finish() async => Float32List(0);
  @override
  Future<String> transcribe(Float32List s) async => '';
  @override
  Future<void> cancel() async {}
  @override
  Future<void> dispose() async {}
}

void main() {
  testWidgets(
    'one obvious start action, three pages, language switch and clear confirmation',
    (tester) async {
      final c = PracticeController(voice: SilentVoice());
      await tester.pumpWidget(TimesTableApp(controller: c));
      expect(find.text('开始练习'), findsOneWidget);
      await tester.tap(find.text('统计'));
      await tester.pumpAndSettle();
      expect(find.text('每一次开口，都在进步'), findsOneWidget);
      await tester.tap(find.text('设置'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('English'));
      await tester.pumpAndSettle();
      expect(find.text('Read questions aloud'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Clear practice history'), 150);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Clear practice history'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Clear practice history'));
      await tester.pumpAndSettle();
      expect(find.text('Keep history'), findsOneWidget);
      await tester.tap(find.text('Keep history'));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  testWidgets('large text on narrow phone does not overflow', (tester) async {
    tester.view.physicalSize = const Size(640, 1136);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final c = PracticeController(voice: SilentVoice());
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(textScaler: TextScaler.linear(1.7)),
        child: TimesTableApp(controller: c),
      ),
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
}
