import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:audioplayers/audioplayers.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

import '../domain/practice.dart';
import 'voice_services.dart';

/// One worker owns native recognition handles; UI never blocks on inference.
class LocalVoiceServices implements VoiceServices {
  final _recorder = AudioRecorder();
  final _player = AudioPlayer();
  Isolate? _worker;
  SendPort? _commands;
  String? _language;
  Future<void>? _initializing;
  final _bytes = BytesBuilder(copy: false);
  StreamSubscription<Uint8List>? _capture;
  StreamSubscription<RecordState>? _recordState;
  Object? _captureError;
  bool _recording = false;
  bool _disposed = false;
  int _generation = 0;
  Completer<void>? _playDone;
  Future<void> _captureOperation = Future.value();
  @override
  Future<bool> permission() => _recorder.hasPermission();
  @override
  Future<void> initialize(String language) async {
    if (_disposed) throw StateError('Audio service disposed');
    if (_language == language && _commands != null) return;
    if (_initializing != null) await _initializing;
    if (_language == language && _commands != null) return;
    final work = _initialize(language);
    _initializing = work;
    try {
      await work;
    } finally {
      _initializing = null;
    }
  }

  Future<void> _initialize(String language) async {
    await _shutdownWorker();
    final root = await getApplicationSupportDirectory();
    final dir = Directory('${root.path}/asr');
    final manifest = jsonDecode(
      await rootBundle.loadString('assets/asr/manifest.json'),
    ) as Map;
    for (final row in manifest['files'] as List) {
      final f = File('${dir.path}/${row['path']}');
      var valid = false;
      if (await f.exists()) {
        valid =
            (await sha256.bind(f.openRead()).first).toString() == row['sha256'];
      }
      if (!valid) {
        final data = await rootBundle.load('assets/asr/${row['path']}');
        await f.parent.create(recursive: true);
        final tmp = File('${f.path}.tmp');
        await tmp.writeAsBytes(
          data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
          flush: true,
        );
        if ((await sha256.bind(tmp.openRead()).first).toString() !=
            row['sha256']) {
          await tmp.delete();
          throw StateError('离线模型校验失败 / Offline model verification failed');
        }
        await tmp.rename(f.path);
      }
    }
    if (_disposed) return;
    final ready = ReceivePort();
    _worker = await Isolate.spawn(_recognitionWorker, [
      ready.sendPort,
      dir.path,
      language,
    ]);
    try {
      final response = await ready.first.timeout(const Duration(seconds: 90));
      if (response is! SendPort) {
        throw StateError('离线识别准备失败 / Offline recognition unavailable');
      }
      _commands = response;
      _language = language;
    } finally {
      ready.close();
    }
  }

  @override
  Future<void> play(String name) async {
    await _stopPlayback();
    final done = Completer<void>();
    _playDone = done;
    final subscription = _player.onPlayerComplete.listen((_) {
      if (!done.isCompleted) done.complete();
    });
    try {
      await _player.play(AssetSource('audio/$name.wav'));
      await done.future.timeout(const Duration(seconds: 20));
    } finally {
      await subscription.cancel();
      if (identical(_playDone, done)) _playDone = null;
    }
  }

  Future<void> _stopPlayback() async {
    final done = _playDone;
    _playDone = null;
    if (done != null && !done.isCompleted) done.complete();
    await _player.stop();
  }

  @override
  Future<void> begin() {
    final gen = _generation;
    final work = _captureOperation.catchError((Object _) {}).then((_) async {
      _bytes.clear();
      _captureError = null;
      final stream = await _recorder.startStream(
        const RecordConfig(
          encoder: AudioEncoder.pcm16bits,
          sampleRate: 16000,
          numChannels: 1,
        ),
      );
      if (gen != _generation || _disposed) {
        await _recorder.stop();
        return;
      }
      _recording = true;
      _capture = stream.listen(
        _bytes.add,
        onError: (Object e) {
          _captureError = e;
        },
      );
      _recordState ??= _recorder.onStateChanged().listen((state) {
        if (_recording && state != RecordState.record) {
          _captureError = StateError('Recording interrupted');
        }
      });
    });
    _captureOperation = work;
    return work;
  }

  @override
  Future<Float32List> finish() async {
    await _captureOperation;
    _recording = false;
    await _recorder.stop();
    await _capture?.cancel();
    _capture = null;
    if (_captureError != null) {
      throw StateError('录音中断，请重试 / Recording interrupted. Try again.');
    }
    final bytes = _bytes.takeBytes();
    final data = ByteData.sublistView(bytes);
    return Float32List.fromList([
      for (var i = 0; i + 1 < bytes.length; i += 2)
        data.getInt16(i, Endian.little) / 32768,
    ]);
  }

  @override
  Future<String> transcribe(Float32List samples) async {
    final port = _commands;
    if (port == null) throw StateError('Offline recognition not ready');
    final reply = ReceivePort();
    port.send([reply.sendPort, samples]);
    try {
      final result = await reply.first.timeout(const Duration(seconds: 45));
      if (result is! String) throw StateError('Offline recognition failed');
      return result;
    } finally {
      reply.close();
    }
  }

  @override
  Future<void> cancel() async {
    ++_generation;
    await _stopPlayback();
    await _captureOperation.catchError((Object _) {});
    _recording = false;
    await _recorder.stop();
    await _capture?.cancel();
    _capture = null;
    _bytes.clear();
  }

  Future<void> _shutdownWorker() async {
    final port = _commands;
    if (port != null) {
      final reply = ReceivePort();
      try {
        port.send([reply.sendPort, 'dispose']);
        await reply.first.timeout(const Duration(seconds: 50));
      } finally {
        reply.close();
      }
    }
    _worker?.kill();
    _worker = null;
    _commands = null;
    _language = null;
  }

  @override
  Future<void> dispose() async {
    _disposed = true;
    await cancel();
    try {
      await _initializing;
    } catch (_) {}
    await _shutdownWorker();
    await _recordState?.cancel();
    await _recorder.dispose();
    await _player.dispose();
  }
}

void _recognitionWorker(List<Object> arguments) {
  final ready = arguments[0] as SendPort;
  final root = arguments[1] as String, language = arguments[2] as String;
  try {
    sherpa.initBindings();
    final recognizer = sherpa.OfflineRecognizer(
      sherpa.OfflineRecognizerConfig(
        model: sherpa.OfflineModelConfig(
          senseVoice: sherpa.OfflineSenseVoiceModelConfig(
            model: '$root/sensevoice/model.int8.onnx',
            language: language,
            useInverseTextNormalization: true,
          ),
          tokens: '$root/sensevoice/tokens.txt',
          numThreads: 2,
          debug: false,
        ),
      ),
    );
    final vad = sherpa.VoiceActivityDetector(
      config: sherpa.VadModelConfig(
        sileroVad: sherpa.SileroVadModelConfig(
          model: '$root/vad/silero_vad.int8.onnx',
          minSpeechDuration: .12,
          minSilenceDuration: .25,
          maxSpeechDuration: 15,
        ),
        sampleRate: 16000,
        debug: false,
      ),
      bufferSizeInSeconds: 30,
    );
    String decode(Float32List samples) {
      if (samples.isEmpty) return '';
      final stream = recognizer.createStream();
      try {
        stream.acceptWaveform(samples: samples, sampleRate: 16000);
        recognizer.decode(stream);
        return recognizer.getResult(stream).text.trim();
      } finally {
        stream.free();
      }
    }

    final commands = ReceivePort();
    ready.send(commands.sendPort);
    commands.listen((Object? message) {
      final args = message as List;
      final reply = args[0] as SendPort;
      if (args[1] == 'dispose') {
        vad.free();
        recognizer.free();
        commands.close();
        reply.send(true);
        return;
      }
      try {
        final samples = args[1] as Float32List;
        vad.reset();
        vad.acceptWaveform(samples);
        vad.flush();
        final segments = <String>[];
        while (!vad.isEmpty()) {
          final segment = vad.front();
          segments.add(decode(segment.samples));
          vad.pop();
        }
        final segmented = segments.join(' '), full = decode(samples);
        final result = selectTranscript(segmented, full, language);
        reply.send(result);
      } catch (_) {
        reply.send(null);
      }
    });
  } catch (_) {
    ready.send(null);
  }
}
