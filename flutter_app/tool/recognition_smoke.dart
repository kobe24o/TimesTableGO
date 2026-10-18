import 'dart:io';

import 'package:sherpa_onnx/sherpa_onnx.dart' as s;

void main(List<String> args) {
  s.initBindings(args[0]);
  for (final language in ['zh', 'en']) {
    final r = s.OfflineRecognizer(
      s.OfflineRecognizerConfig(
        model: s.OfflineModelConfig(
          senseVoice: s.OfflineSenseVoiceModelConfig(
            model: 'assets/asr/sensevoice/model.int8.onnx',
            language: language,
            useInverseTextNormalization: true,
          ),
          tokens: 'assets/asr/sensevoice/tokens.txt',
          numThreads: 2,
          debug: false,
        ),
      ),
    );
    final wave = s.readWave(
      'assets/audio/correction_3_7${language == 'en' ? '_en' : ''}.wav',
    );
    final stream = r.createStream();
    stream.acceptWaveform(samples: wave.samples, sampleRate: wave.sampleRate);
    r.decode(stream);
    final text = r.getResult(stream).text;
    if (text.trim().isEmpty) throw StateError('Empty $language transcript');
    stdout.writeln('$language: $text');
    stream.free();
    r.free();
  }
}
