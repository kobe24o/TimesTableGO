import 'dart:convert';
import 'dart:io';

import '../domain/practice.dart';

class StoredPractice {
  StoredPractice({
    this.settings = const Settings(),
    this.attempts = const [],
    this.migrated = false,
  });
  final Settings settings;
  final List<Attempt> attempts;
  final bool migrated;
  Map<String, dynamic> toJson() => {
    'version': 1,
    'settings': settings.toJson(),
    'attempts': attempts.map((a) => a.toJson()).toList(),
    'migrated': migrated,
  };
}

class PracticeStore {
  PracticeStore(this.directory);
  final Directory directory;
  bool _corrupt = false;
  Future<void> _writes = Future.value();
  File get _file => File('${directory.path}/practice.json');
  Future<StoredPractice> load({
    Future<Map<String, dynamic>?> Function()? legacy,
  }) async {
    if (await _file.exists()) {
      try {
        final j =
            jsonDecode(await _file.readAsString()) as Map<String, dynamic>;
        if (j['version'] != 1) {
          throw const FormatException('Unsupported practice format');
        }
        return StoredPractice(
          settings: Settings.fromJson(j['settings'] as Map<String, dynamic>),
          attempts: (j['attempts'] as List)
              .map((a) => Attempt.fromJson(a as Map<String, dynamic>))
              .toList(),
          migrated: j['migrated'] as bool,
        );
      } catch (_) {
        _corrupt = true;
        throw const FormatException(
          'Saved practice data could not be read. Original retained.',
        );
      }
    }
    final old = await legacy?.call();
    final rows = old == null
        ? <Attempt>[]
        : (jsonDecode(old['attempts'] as String? ?? '[]') as List)
              .map((a) => Attempt.fromJson(a as Map<String, dynamic>))
              .toList();
    rows.sort((a, b) => b.timestamp.compareTo(a.timestamp));
    final data = StoredPractice(
      settings: old == null ? const Settings() : Settings.fromJson(old),
      attempts: rows,
      migrated: true,
    );
    await save(data);
    return data;
  }

  Future<void> save(StoredPractice data) {
    if (_corrupt) {
      return Future.error(
        StateError('Original data requires recovery before writing'),
      );
    }
    final encoded = jsonEncode(data.toJson());
    final next = _writes.catchError((Object _) {}).then((_) async {
      await directory.create(recursive: true);
      final temporary = File('${_file.path}.tmp');
      await temporary.writeAsString(encoded, flush: true);
      await temporary.rename(_file.path);
    });
    _writes = next;
    return next;
  }
}
