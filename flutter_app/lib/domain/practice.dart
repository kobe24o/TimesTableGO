class Problem {
  const Problem(this.a, this.b) : assert(a >= 1 && a <= 9 && b >= 1 && b <= 9);
  final int a, b;
  int get answer => a * b;
  String get key => '$a:$b';
  static final all = [
    for (var a = 1; a <= 9; a++)
      for (var b = 1; b <= 9; b++) Problem(a, b),
  ];
}

class Settings {
  const Settings({this.language = 'zh', this.seconds = 3, this.prompt = true});
  final String language;
  final int seconds;
  final bool prompt;
  factory Settings.fromJson(Map<String, dynamic> json) => Settings(
    language: json['language'] == 'en' || json['language'] == 'EN'
        ? 'en'
        : 'zh',
    seconds: ((json['seconds'] ?? json['answerTimeLimitSeconds'] ?? 3) as num)
        .toInt()
        .clamp(1, 15),
    prompt: (json['prompt'] ?? json['promptAudioEnabled'] ?? true) as bool,
  );
  Map<String, dynamic> toJson() => {
    'language': language,
    'seconds': seconds,
    'prompt': prompt,
  };
}

class Attempt {
  const Attempt({
    required this.problem,
    required this.transcript,
    required this.answer,
    required this.timestamp,
  });
  final Problem problem;
  final String transcript;
  final int? answer;
  final int timestamp;
  bool get correct => answer == problem.answer;
  factory Attempt.fromJson(Map<String, dynamic> j) {
    final a = (j['a'] as num).toInt(), b = (j['b'] as num).toInt();
    if (a < 1 || a > 9 || b < 1 || b > 9) {
      throw const FormatException('Invalid problem');
    }
    return Attempt(
      problem: Problem(a, b),
      transcript: j['transcript'] as String,
      answer: (j['extractedAnswer'] as num?)?.toInt(),
      timestamp: (j['timestamp'] as num).toInt(),
    );
  }
  Map<String, dynamic> toJson() => {
    'a': problem.a,
    'b': problem.b,
    'expected': problem.answer,
    'transcript': transcript,
    'extractedAnswer': answer,
    'correct': correct,
    'checkedBy': 'local',
    'timestamp': timestamp,
  };
}

class PracticeStatistics {
  PracticeStatistics(this.attempts);
  final List<Attempt> attempts;
  int get correct => attempts.where((a) => a.correct).length;
  double get accuracy => attempts.isEmpty ? 0 : correct / attempts.length;
  int get streak => attempts.takeWhile((a) => a.correct).length;
  double get coverage => attempts.map((a) => a.problem.key).toSet().length / 81;
  double? mastery(Problem p) {
    final rows = attempts.where((a) => a.problem.key == p.key).toList();
    return rows.isEmpty
        ? null
        : rows.where((a) => a.correct).length / rows.length;
  }

  List<bool> get achievements => [
    attempts.isNotEmpty,
    attempts.length >= 10 && accuracy >= .8,
    streak >= 10,
    attempts.length >= 50 && accuracy >= .95,
    coverage == 1,
    attempts.length >= 100,
  ];
}

class AnswerParser {
  static const _digits = '零一二三四五六七八九';
  static const _ones = [
    'zero',
    'one',
    'two',
    'three',
    'four',
    'five',
    'six',
    'seven',
    'eight',
    'nine',
    'ten',
    'eleven',
    'twelve',
    'thirteen',
    'fourteen',
    'fifteen',
    'sixteen',
    'seventeen',
    'eighteen',
    'nineteen',
  ];
  static const _tens = [
    'twenty',
    'thirty',
    'forty',
    'fifty',
    'sixty',
    'seventy',
    'eighty',
    'ninety',
  ];
  static String chinese(int n) => n < 10
      ? _digits[n]
      : '${n < 20 ? '' : _digits[n ~/ 10]}十${n % 10 == 0 ? '' : _digits[n % 10]}';
  static int? _chinese(String s) {
    s = s.replaceAll('〇', '零').replaceAll('两', '二');
    if (s.contains(RegExp('[百千万]'))) return null;
    if (!s.contains('十')) {
      return int.tryParse(s.split('').map((c) => _digits.indexOf(c)).join());
    }
    if (!RegExp(r'^[一二三四五六七八九]?十[零一二三四五六七八九]?$').hasMatch(s)) return null;
    final parts = s.split('十');
    return (parts.first.isEmpty ? 1 : _digits.indexOf(parts.first)) * 10 +
        (parts.last.isEmpty ? 0 : _digits.indexOf(parts.last));
  }

  static List<int> candidates(String text, String language) {
    final result = <int>[];
    final normalized = text.toLowerCase().replaceAll(
      RegExp(r'<\|[^>]*\|>'),
      '',
    );
    // One token stream keeps formula order and prevents matching "one" in "someone".
    final pattern = RegExp(
      r'\d十[零〇一二两三四五六七八九]?|\d+|[零〇一二两三四五六七八九十百千万]+|[a-z]+',
    );
    final tokens = pattern.allMatches(normalized).map((m) => m[0]!).toList();
    for (var i = 0; i < tokens.length; i++) {
      final token = tokens[i];
      if (RegExp(r'^\d十').hasMatch(token)) {
        result.add(
          int.parse(token[0]) * 10 +
              (token.length == 3
                  ? _digits.indexOf(token[2].replaceAll('两', '二'))
                  : 0),
        );
      } else if (RegExp(r'^\d').hasMatch(token)) {
        result.add(int.tryParse(token) ?? -1);
      } else if (RegExp(r'^[零〇一二两三四五六七八九十百千万]').hasMatch(token)) {
        result.add(_chinese(token) ?? -1);
      } else if (language == 'en') {
        if (_ones.contains(token)) {
          result.add(_ones.indexOf(token));
        } else if (_tens.contains(token)) {
          var n = (_tens.indexOf(token) + 2) * 10;
          if (i + 1 < tokens.length && _ones.take(10).contains(tokens[i + 1])) {
            n += _ones.indexOf(tokens[++i]);
          }
          result.add(n);
        }
      }
    }
    return result;
  }

  static int? parse(
    String text, {
    required Problem problem,
    required String language,
  }) {
    final values = candidates(text, language);
    if (RegExp(
          r'(^|[\s=])[-−]\s*(\d|[a-z]|[零一二两三四五六七八九十])',
          caseSensitive: false,
        ).hasMatch(text) ||
        RegExp(
          r'\b(hundred|thousand|million|minus|negative)\b',
          caseSensitive: false,
        ).hasMatch(text)) {
      return null;
    }
    final shorthand = text
        .replaceAll(RegExp(r'[\s，。！？,.!?]'), '')
        .replaceAll('〇', '零')
        .replaceAll('两', '二');
    if (shorthand ==
        '${chinese(problem.a)}${chinese(problem.b)}${chinese(problem.answer)}') {
      return problem.answer;
    }
    if (values.length == 3) {
      return values[0] == problem.a &&
              values[1] == problem.b &&
              values[2] == problem.answer
          ? problem.answer
          : null;
    }
    if (values.isEmpty || values.any((n) => n < 1 || n > 81)) return null;
    return values.toSet().length == 1 ? values.first : null;
  }
}

/// VAD can cut a short number or formula; the complete capture is authoritative
/// when it contains number candidates. No expected answer enters this decision.
String selectTranscript(String segmented, String full, String language) =>
    AnswerParser.candidates(full, language).isNotEmpty ? full : segmented;
