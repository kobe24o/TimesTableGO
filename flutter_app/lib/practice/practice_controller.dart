import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../domain/practice.dart';
import '../services/voice_services.dart';

enum PracticePhase {
  idle,
  preparing,
  prompting,
  recording,
  recognizing,
  feedback,
  error,
}

class _Cancelled implements Exception {}

class PracticeController extends ChangeNotifier {
  PracticeController({
    required this.voice,
    this.settings = const Settings(),
    List<Attempt> attempts = const [],
    Problem Function()? nextProblem,
    this.persist,
  }) : attempts = List.of(attempts),
       _next = nextProblem ?? (() => Problem.all[Random().nextInt(81)]);
  final VoiceServices voice;
  final Problem Function() _next;
  final Future<void> Function(Settings, List<Attempt>)? persist;
  Settings settings;
  List<Attempt> attempts;
  PracticePhase phase = PracticePhase.idle;
  Problem? problem;
  String transcript = '', message = '';
  bool running = false;
  bool? correct;
  int remaining = 0;
  Timer? _timer;
  int _session = 0;
  bool _disposed = false;
  Completer<void>? _cancelled;
  Future<void>? _active;
  Future<void>? _stopping;
  String t(String zh, String en) => settings.language == 'en' ? en : zh;
  String get suffix => settings.language == 'en' ? '_en' : '';
  void _changed() {
    if (!_disposed) notifyListeners();
  }

  Future<T> _wait<T>(Future<T> work, int session) async {
    final value = await Future.any([
      work,
      _cancelled!.future.then<T>((_) => throw _Cancelled()),
    ]);
    if (session != _session) throw _Cancelled();
    return value;
  }

  Future<void> start() async {
    final request = _session;
    if (_stopping != null) await _stopping;
    if (request != _session) return;
    if (running || _disposed) return _active ?? Future.value();
    running = true;
    final session = ++_session;
    _cancelled = Completer<void>();
    _active = _run(session);
    return _active!;
  }

  Future<void> _run(int session) async {
    try {
      phase = PracticePhase.preparing;
      message = '';
      _changed();
      if (!await _wait(voice.permission(), session)) {
        throw StateError(
          t(
            '请在系统设置中允许麦克风，然后重试。',
            'Allow microphone access in Settings, then try again.',
          ),
        );
      }
      await _wait(voice.initialize(settings.language), session);
      while (running && session == _session) {
        problem ??= _next();
        transcript = '';
        correct = null;
        if (settings.prompt) {
          phase = PracticePhase.prompting;
          _changed();
          try {
            await _wait(
              voice.play('prompt_${problem!.a}_${problem!.b}$suffix'),
              session,
            );
          } on _Cancelled {
            rethrow;
          } catch (_) {
            message = t(
              '读题播放失败，请看题目作答。',
              'Audio unavailable. Read the question above.',
            );
          }
        }
        await _wait(voice.begin(), session);
        phase = PracticePhase.recording;
        remaining = settings.seconds;
        _changed();
        final clock = Stopwatch()..start();
        _timer = Timer.periodic(const Duration(milliseconds: 100), (_) {
          remaining =
              ((settings.seconds * 1000 - clock.elapsedMilliseconds).clamp(
                        0,
                        settings.seconds * 1000,
                      ) /
                      1000)
                  .ceil();
          _changed();
        });
        await _wait(
          Future<void>.delayed(Duration(seconds: settings.seconds)),
          session,
        );
        _timer?.cancel();
        remaining = 0;
        final samples = await _wait(voice.finish(), session);
        phase = PracticePhase.recognizing;
        _changed();
        transcript = await _wait(voice.transcribe(samples), session);
        final numbers = AnswerParser.candidates(transcript, settings.language);
        if (numbers.isEmpty) {
          phase = PracticePhase.feedback;
          message = t('没听清，再试一次这道题。', 'Let’s try this one again.');
          _changed();
          await _feedback('feedback_no_speech$suffix', session);
          continue;
        }
        final answer = AnswerParser.parse(
          transcript,
          problem: problem!,
          language: settings.language,
        );
        correct = answer == problem!.answer;
        attempts = [
          Attempt(
            problem: problem!,
            transcript: transcript,
            answer: answer,
            timestamp: DateTime.now().millisecondsSinceEpoch,
          ),
          ...attempts,
        ];
        await _wait(_save(), session);
        phase = PracticePhase.feedback;
        message = correct!
            ? t('答对了！继续保持。', 'You got it! Keep going.')
            : t(
                '一起记住：${problem!.a} × ${problem!.b} = ${problem!.answer}',
                'Remember: ${problem!.a} × ${problem!.b} = ${problem!.answer}',
              );
        _changed();
        await _feedback(
          correct!
              ? 'feedback_correct$suffix'
              : 'correction_${problem!.a}_${problem!.b}$suffix',
          session,
        );
        await _wait(
          Future<void>.delayed(const Duration(milliseconds: 700)),
          session,
        );
        problem = null;
      }
    } on _Cancelled {
      /* Explicit stop or lifecycle change. */
    } catch (e) {
      if (session == _session) {
        phase = PracticePhase.error;
        message = e is StateError
            ? e.message.toString()
            : t('练习暂时无法继续，请重试。', 'Practice paused. Please try again.');
      }
    } finally {
      if (session == _session) {
        _timer?.cancel();
        running = false;
        remaining = 0;
        final cleanup = voice.cancel().catchError((Object _) {});
        _stopping = cleanup;
        await cleanup;
        if (identical(_stopping, cleanup)) _stopping = null;
        if (session == _session) _changed();
      }
    }
  }

  Future<void> _feedback(String name, int session) async {
    try {
      await _wait(voice.play(name), session);
    } on _Cancelled {
      rethrow;
    } catch (_) {
      message = t(
        '反馈音频不可用，请看屏幕订正。',
        'Feedback audio unavailable. See the answer above.',
      );
      _changed();
    }
  }

  Future<void> _save() async {
    if (persist != null) await persist!(settings, List.unmodifiable(attempts));
  }

  Future<void> stop() {
    if (_stopping != null) return _stopping!;
    running = false;
    final session = ++_session;
    _timer?.cancel();
    if (_cancelled != null && !_cancelled!.isCompleted) _cancelled!.complete();
    final work = _finishStop(session);
    _stopping = work;
    return work.whenComplete(() {
      if (identical(_stopping, work)) _stopping = null;
    });
  }

  Future<void> _finishStop(int session) async {
    try {
      await voice.cancel();
    } catch (_) {}
    if (session != _session) return;
    phase = PracticePhase.idle;
    remaining = 0;
    problem = null;
    correct = null;
    message = '';
    _changed();
  }

  Future<void> updateSettings(Settings value) async {
    await stop();
    settings = value;
    _changed();
    await _save();
  }

  Future<void> clearHistory() async {
    await stop();
    attempts = [];
    _changed();
    await _save();
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    if (_cancelled != null && !_cancelled!.isCompleted) _cancelled!.complete();
    unawaited(voice.dispose());
    super.dispose();
  }
}
