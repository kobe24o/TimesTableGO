import 'package:flutter/material.dart';

import '../domain/practice.dart';
import '../practice/practice_controller.dart';
import 'app.dart';

class StatisticsPage extends StatelessWidget {
  const StatisticsPage({super.key, required this.controller});
  final PracticeController controller;
  @override
  Widget build(BuildContext context) {
    final c = controller, s = PracticeStatistics(c.attempts);
    final titles = [
      c.t('初出茅庐', 'First steps'),
      c.t('渐入佳境', 'Warming up'),
      c.t('连战连胜', 'On a roll'),
      c.t('稳如老手', 'Veteran'),
      c.t('九九归一', 'All tables'),
      c.t('百题斩', 'Centurion'),
    ];
    final details = [
      c.t('完成第一题', 'First problem done'),
      c.t('至少十题，正确率 80%', '10+ problems, 80% accuracy'),
      c.t('连续答对十题', '10 correct in a row'),
      c.t('至少五十题，正确率 95%', '50+ problems, 95% accuracy'),
      c.t('练过全部 81 题', 'Practice all 81 problems'),
      c.t('完成一百题', '100 problems done'),
    ];
    final recent = c.attempts.take(20).toList().reversed.toList();
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        PageHeading(
          c.t('每一次开口，都在进步', 'Every answer is a step forward'),
          c.t('不用和别人比，看看自己的成长。', 'Your journey, at your pace.'),
        ),
        if (c.attempts.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                children: [
                  const Icon(Icons.eco_outlined, size: 54, color: teal),
                  const SizedBox(height: 16),
                  Text(
                    c.t('从第一道题开始', 'Start with one little question'),
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    c.t(
                      '去练习页开口说一说，成长记录就会出现在这里。',
                      'Speak an answer on the Practice page. Your progress will grow here.',
                    ),
                    textAlign: TextAlign.center,
                    style: const TextStyle(height: 1.6),
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 16),
        LayoutBuilder(
          builder: (context, box) => Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _metric(
                c.t('累计练习', 'Practiced'),
                '${s.attempts.length}',
                (box.maxWidth - 12) / 2,
              ),
              _metric(
                c.t('正确率', 'Accuracy'),
                '${(s.accuracy * 100).round()}%',
                (box.maxWidth - 12) / 2,
              ),
              _metric(
                c.t('当前连对', 'Current streak'),
                '${s.streak}',
                (box.maxWidth - 12) / 2,
              ),
              _metric(
                c.t('九九表覆盖', 'Table coverage'),
                '${(s.coverage * 100).round()}%',
                (box.maxWidth - 12) / 2,
              ),
            ],
          ),
        ),
        if (recent.isNotEmpty) ...[
          const SizedBox(height: 28),
          _heading(c.t('最近的答题节奏', 'Your recent rhythm')),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    c.t(
                      '最近 ${recent.length} 题 · 从左到右',
                      'Last ${recent.length} answers · oldest to newest',
                    ),
                    style: const TextStyle(color: Color(0xff627781)),
                  ),
                  const SizedBox(height: 18),
                  Wrap(
                    spacing: 6,
                    runSpacing: 8,
                    children: [
                      for (final a in recent)
                        Tooltip(
                          message:
                              '${a.problem.a} × ${a.problem.b}: ${a.correct ? c.t('正确', 'correct') : c.t('需巩固', 'keep practicing')}',
                          child: Icon(
                            a.correct
                                ? Icons.check_circle_rounded
                                : Icons.remove_circle_outline,
                            color: a.correct ? teal : const Color(0xffb77342),
                            size: 24,
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(c.t('✓ 答对了    − 再巩固', '✓ Correct    − Keep practicing')),
                ],
              ),
            ),
          ),
        ],
        const SizedBox(height: 28),
        _heading(c.t('九九表掌握度', 'Your times tables')),
        const SizedBox(height: 8),
        Text(
          c.t(
            '点击格子查看掌握情况；浅色代表还没练过。',
            'Tap a tile to explore. Pale tiles are waiting for you.',
          ),
          style: const TextStyle(height: 1.5, color: Color(0xff627781)),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: LayoutBuilder(
              builder: (context, box) => Column(
                children: [
                  for (var a = 1; a <= 9; a++)
                    Row(
                      children: [
                        for (var b = 1; b <= 9; b++)
                          Expanded(child: _tile(context, Problem(a, b), s, c)),
                      ],
                    ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: 28),
        _heading(c.t('小小成就', 'Little milestones')),
        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              for (var i = 0; i < titles.length; i++)
                ListTile(
                  leading: Icon(
                    s.achievements[i]
                        ? Icons.emoji_events_rounded
                        : Icons.lock_outline_rounded,
                    color: s.achievements[i]
                        ? const Color(0xffbd8a30)
                        : Colors.grey,
                  ),
                  title: Text(titles[i]),
                  subtitle: Text(details[i]),
                  trailing: s.achievements[i]
                      ? const Icon(Icons.check_circle, color: teal)
                      : null,
                ),
            ],
          ),
        ),
        if (c.attempts.isNotEmpty) ...[
          const SizedBox(height: 28),
          _heading(c.t('最近作答', 'Recent answers')),
          const SizedBox(height: 12),
          Card(
            child: Column(
              children: [
                for (final a in c.attempts.take(10))
                  ListTile(
                    leading: Icon(
                      a.correct
                          ? Icons.check_circle_outline
                          : Icons.school_outlined,
                      color: a.correct ? teal : const Color(0xffb77342),
                    ),
                    title: Text(
                      '${a.problem.a} × ${a.problem.b} = ${a.problem.answer}',
                    ),
                    subtitle: Text(
                      c.t('你说：${a.transcript}', 'You said: ${a.transcript}'),
                    ),
                    trailing: Text(
                      a.correct ? c.t('正确', 'Correct') : c.t('再巩固', 'Review'),
                    ),
                  ),
              ],
            ),
          ),
        ],
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _heading(String text) => Text(
    text,
    style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700),
  );
  Widget _metric(String label, String value, double width) => SizedBox(
    width: width,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              value,
              style: const TextStyle(
                fontSize: 30,
                fontWeight: FontWeight.w800,
                color: teal,
              ),
            ),
            const SizedBox(height: 4),
            Text(label),
          ],
        ),
      ),
    ),
  );
  Widget _tile(
    BuildContext context,
    Problem p,
    PracticeStatistics stats,
    PracticeController c,
  ) {
    final value = stats.mastery(p);
    final label =
        '${p.a} × ${p.b}: ${value == null ? c.t('还没练过', 'not practiced') : '${(value * 100).round()}%'}';
    return Semantics(
      label: label,
      button: true,
      child: Padding(
        padding: const EdgeInsets.all(2),
        child: Material(
          color: value == null
              ? const Color(0xfff0f2ed)
              : Color.lerp(
                  const Color(0xfff0d9bd),
                  const Color(0xff67b6a5),
                  value,
                )!,
          borderRadius: BorderRadius.circular(8),
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: () => showDialog<void>(
              context: context,
              builder: (context) => AlertDialog(
                title: Text('${p.a} × ${p.b} = ${p.answer}'),
                content: Text(label),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: Text(c.t('知道了', 'Got it')),
                  ),
                ],
              ),
            ),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 48),
              child: Center(
                child: FittedBox(
                  child: Padding(
                    padding: const EdgeInsets.all(3),
                    child: Text(
                      '${p.a}×${p.b}',
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
