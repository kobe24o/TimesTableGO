import 'package:flutter_test/flutter_test.dart';
import 'package:timestablego/domain/practice.dart';

void main() {
  test('all 81 problems and bounded defaults', () {
    expect(Problem.all.length, 81);
    expect(Problem.all.map((p) => '${p.a},${p.b}').toSet().length, 81);
    expect(const Settings().seconds, 3);
    expect(Settings.fromJson({'seconds': 40}).seconds, 15);
    expect(Settings.fromJson({'seconds': 0}).seconds, 1);
  });
  test('accepts unique bilingual answers and matching formulas', () {
    const p = Problem(3, 7);
    for (final text in [
      '21',
      '二十一',
      'twenty one',
      '3 times 7 equals 21',
      '三七二十一',
    ]) {
      expect(
        AnswerParser.parse(text, problem: p, language: 'en'),
        21,
        reason: text,
      );
    }
    expect(AnswerParser.parse('二十四', problem: p, language: 'zh'), 24);
  });
  test('rejects conflicting answers, invalid numbers and substring words', () {
    const p = Problem(3, 7);
    for (final text in [
      '',
      '你好',
      '21 or 24',
      '二十一或者二十四',
      '121',
      'someone',
      'twenty thirty',
      '3 times 7 equals 21 or twenty four',
      '3721',
      'twenty one hundred',
      '-21',
      '3 times 8 equals 21',
    ]) {
      expect(
        AnswerParser.parse(text, problem: p, language: 'en'),
        isNull,
        reason: text,
      );
    }
  });
  test('rejects unsupported English magnitudes', () {
    expect(
      AnswerParser.parse(
        'one hundred',
        problem: const Problem(1, 1),
        language: 'en',
      ),
      isNull,
    );
  });
  test(
    'whole answer window wins over a VAD fragment without expected-answer bias',
    () {
      expect(selectTranscript('一。', '3乘7等于21。', 'zh'), '3乘7等于21。');
      expect(selectTranscript('seven', '你好', 'en'), 'seven');
      expect(selectTranscript('21', 'twenty four', 'en'), 'twenty four');
    },
  );
  test('accuracy trend preserves chronological cumulative values', () {
    final stats = PracticeStatistics([
      const Attempt(
        problem: Problem(1, 1),
        transcript: '1',
        answer: 1,
        timestamp: 3,
      ),
      const Attempt(
        problem: Problem(1, 1),
        transcript: '1',
        answer: 1,
        timestamp: 2,
      ),
      const Attempt(
        problem: Problem(1, 1),
        transcript: '2',
        answer: 2,
        timestamp: 1,
      ),
    ]);
    expect(stats.accuracyTrend, [0, .5, 2 / 3]);
    expect(PracticeStatistics([]).accuracyTrend, isEmpty);
  });
  test('statistics uses latest first and all thresholds', () {
    final rows = List.generate(
      10,
      (i) => Attempt(
        problem: const Problem(1, 1),
        transcript: '1',
        answer: 1,
        timestamp: i,
      ),
    );
    final stats = PracticeStatistics(rows);
    expect(stats.accuracy, 1);
    expect(stats.streak, 10);
    expect(stats.coverage, 1 / 81);
    expect(stats.achievements, [true, true, true, false, false, false]);
    expect(PracticeStatistics([]).accuracy, 0);
  });
}
