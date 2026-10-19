import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:path_provider/path_provider.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;
import 'package:timestablego/main.dart' as app;
import 'package:timestablego/domain/practice.dart';
import 'package:timestablego/services/local_voice_services.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('native UI and actual offline model work in both languages', (
    tester,
  ) async {
    app.main();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('中文'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('练习'));
    await tester.pumpAndSettle();
    expect(find.text('开始练习'), findsOneWidget);
    await binding.takeScreenshot('practice-zh');
    await tester.tap(find.text('统计'));
    await tester.pumpAndSettle();
    expect(find.text('九九表掌握度'), findsOneWidget);
    await binding.takeScreenshot('statistics-zh');
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('English'));
    await tester.pumpAndSettle();
    expect(find.text('Read questions aloud'), findsOneWidget);
    await binding.takeScreenshot('settings-en');
    await tester.tap(find.text('Practice'));
    await tester.pumpAndSettle();
    await binding.takeScreenshot('practice-en');
    final services = LocalVoiceServices();
    try {
      for (final lang in ['zh', 'en']) {
        await services.initialize(lang);
        final bytes = await rootBundle.load(
          'assets/audio/correction_3_7${lang == 'en' ? '_en' : ''}.wav',
        );
        final temp = await getTemporaryDirectory();
        final file = File('${temp.path}/smoke.wav');
        await file.writeAsBytes(
          bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
        );
        sherpa.initBindings();
        final wave = sherpa.readWave(file.path);
        final samples = Float32List(
          (wave.samples.length * 16000 / wave.sampleRate).floor(),
        );
        for (var i = 0; i < samples.length; i++) {
          samples[i] = wave.samples[(i * wave.sampleRate / 16000).floor()];
        }
        final text = await services.transcribe(samples);
        expect(
          AnswerParser.parse(
            text,
            problem: const Problem(3, 7),
            language: lang,
          ),
          21,
          reason: '$lang: $text',
        );
        await services.play('feedback_correct${lang == 'en' ? '_en' : ''}');
      }
    } finally {
      await services.dispose();
    }
  });
}
