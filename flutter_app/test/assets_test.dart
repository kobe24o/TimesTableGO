import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'all bilingual prompts, corrections and feedback are playable WAV',
    () async {
      for (final suffix in ['', '_en']) {
        final names = [
          for (var a = 1; a <= 9; a++)
            for (var b = 1; b <= 9; b++) ...[
              'prompt_${a}_$b$suffix',
              'correction_${a}_$b$suffix',
            ],
          for (final f in ['correct', 'incorrect', 'listen', 'no_speech'])
            'feedback_$f$suffix',
        ];
        for (final name in names) {
          final f = File('assets/audio/$name.wav');
          expect(await f.exists(), true, reason: name);
          final bytes = await f.readAsBytes();
          expect(ascii.decode(bytes.take(4).toList()), 'RIFF', reason: name);
        }
      }
    },
  );
  test('packaged model files match original manifest', () async {
    final manifest = jsonDecode(
      await File('assets/asr/manifest.json').readAsString(),
    ) as Map;
    for (final item in manifest['files'] as List) {
      final f = File('assets/asr/${item['path']}');
      expect(await f.exists(), true);
      expect(
        (await sha256.bind(f.openRead()).first).toString(),
        item['sha256'],
      );
    }
    expect(
      await File('assets/asr/sensevoice/model.int8.onnx').length(),
      239233841,
    );
  });
}
