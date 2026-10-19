import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:timestablego/practice/practice_controller.dart';
import 'package:timestablego/ui/update_banner.dart';

import 'widget_test.dart' show SilentVoice;

void main() {
  testWidgets('download errors remain visible with retry', (tester) async {
    final c = PracticeController(voice: SilentVoice());
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: UpdateBanner(
            controller: c,
            stateStream: Stream.value({
              'state': 'error',
              'zh': '下载更新失败',
              'en': 'Update download failed',
            }),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Update download failed'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    c.dispose();
  });
}
