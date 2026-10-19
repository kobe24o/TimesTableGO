import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import 'data/practice_store.dart';
import 'practice/practice_controller.dart';
import 'services/local_voice_services.dart';
import 'ui/app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    for (final file in [
      'assets/licenses/cosyvoice-notice.txt',
      'assets/asr/LICENSES/sherpa-onnx-apache-2.0.txt',
      'assets/asr/LICENSES/funasr-mit.txt',
      'assets/asr/LICENSES/silero-vad-mit.txt',
      'assets/asr/LICENSES/sensevoice-model-reference.txt',
    ]) {
      yield LicenseEntryWithLineBreaks([
        'TimesTable GO offline speech',
      ], await rootBundle.loadString(file));
    }
  });
  runApp(const Bootstrap());
}

class Bootstrap extends StatefulWidget {
  const Bootstrap({super.key});
  @override
  State<Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<Bootstrap> {
  late Future<PracticeController> _future;
  PracticeController? _controller;
  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<PracticeController> _load() async {
    final store = PracticeStore(await getApplicationSupportDirectory());
    final data = await store.load(
      legacy: () async {
        if (!Platform.isAndroid) return null;
        return await const MethodChannel('timestablego/legacy')
            .invokeMapMethod<String, dynamic>('readPractice');
      },
    );
    return _controller = PracticeController(
      voice: LocalVoiceServices(),
      settings: data.settings,
      attempts: data.attempts,
      persist: (settings, attempts) => store.save(
        StoredPractice(settings: settings, attempts: attempts, migrated: true),
      ),
    );
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<PracticeController>(
    future: _future,
    builder: (context, snapshot) {
      if (snapshot.hasData) return TimesTableApp(controller: snapshot.data!);
      return MaterialApp(
        debugShowCheckedModeBanner: false,
        home: Scaffold(
          backgroundColor: paper,
          body: Center(
            child: Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (!snapshot.hasError)
                    const CircularProgressIndicator()
                  else ...[
                    const Icon(Icons.folder_outlined, size: 52, color: teal),
                    const SizedBox(height: 20),
                    const Text(
                      '暂时无法读取学习记录\nCould not read practice history',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 22),
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      '原始记录已保留，请检查设备剩余空间后重试。\nYour original data is safe. Check available storage, then retry.',
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: () => setState(() => _future = _load()),
                      child: const Text('重试 / Retry'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}
