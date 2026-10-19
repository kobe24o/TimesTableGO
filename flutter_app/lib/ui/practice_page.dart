import 'dart:async';

import 'package:flutter/material.dart';

import '../domain/practice.dart';
import '../practice/practice_controller.dart';
import 'app.dart';

class PracticePage extends StatelessWidget {
  const PracticePage({super.key, required this.controller});
  final PracticeController controller;
  @override
  Widget build(BuildContext context) {
    final c = controller, stats = PracticeStatistics(controller.attempts);
    final recording = c.phase == PracticePhase.recording;
    final labels = {
      PracticePhase.idle: c.t(
        '准备好，开口练一练',
        'A little practice, a little progress',
      ),
      PracticePhase.preparing: c.t(
        '正在准备离线语音…',
        'Getting offline speech ready…',
      ),
      PracticePhase.prompting: c.t('先听题目', 'Listen to the question'),
      PracticePhase.recording: c.t('现在说出答案', 'Say your answer now'),
      PracticePhase.recognizing: c.t('正在听懂你的答案…', 'Checking your answer…'),
      PracticePhase.feedback: c.correct == true
          ? c.t('真棒，答对了！', 'Nice work!')
          : c.t('再巩固一下', 'Let’s learn together'),
      PracticePhase.error: c.t('稍作准备，再试一次', 'Let’s try again'),
    };
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Row(
          children: [
            const Icon(Icons.auto_awesome_rounded, color: teal, size: 26),
            const SizedBox(width: 10),
            const Expanded(
              child: Text(
                'TimesTable GO',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
              ),
            ),
            Tooltip(
              message: c.t(
                '语音与记录仅保存在本机',
                'Speech and history stay on this device',
              ),
              child: const Icon(Icons.shield_outlined, color: teal, size: 22),
            ),
          ],
        ),
        const SizedBox(height: 30),
        PageHeading(
          c.t('每天一点，乘法更熟练', 'Small steps. Big confidence.'),
          c.t(
            '听题目 · 说答案 · 轻松记住九九表',
            'Listen. Speak. Make the times tables yours.',
          ),
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 30),
            child: Column(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: teal.withValues(alpha: .08),
                    borderRadius: BorderRadius.circular(30),
                  ),
                  child: Text(
                    labels[c.phase]!,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: teal,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(height: 26),
                Semantics(
                  liveRegion: true,
                  child: Text(
                    c.problem == null
                        ? '×'
                        : '${c.problem!.a} × ${c.problem!.b}',
                    style: TextStyle(
                      fontSize: c.problem == null ? 78 : 64,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 2,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
                const SizedBox(height: 18),
                SizedBox(
                  height: 86,
                  width: 86,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      if (recording)
                        SizedBox.expand(
                          child: CircularProgressIndicator(
                            value: c.remaining / c.settings.seconds,
                            strokeWidth: 5,
                            backgroundColor: teal.withValues(alpha: .1),
                          ),
                        ),
                      CircleAvatar(
                        radius: 34,
                        backgroundColor: c.correct == true
                            ? const Color(0xffdef2d9)
                            : const Color(0xffe5f1ee),
                        child: Icon(
                          c.correct == true
                              ? Icons.check_rounded
                              : recording
                              ? Icons.mic_rounded
                              : Icons.graphic_eq_rounded,
                          size: 34,
                          color: teal,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  recording
                      ? c.t(
                          '还有 ${c.remaining} 秒',
                          '${c.remaining} seconds left',
                        )
                      : c.running
                      ? c.t('慢慢来，你可以的', 'Take your time. You’ve got this.')
                      : c.t('点击下方按钮，直接开始', 'Tap below. You’re ready to go.'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Color(0xff627781), height: 1.5),
                ),
                if (c.transcript.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Text(
                    c.t('你说：${c.transcript}', 'You said: ${c.transcript}'),
                    textAlign: TextAlign.center,
                  ),
                ],
                if (c.message.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Semantics(
                    liveRegion: true,
                    child: Text(
                      c.message,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 26),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: () =>
                        unawaited(c.running ? c.stop() : c.start()),
                    icon: Icon(
                      c.running ? Icons.stop_rounded : Icons.play_arrow_rounded,
                    ),
                    label: Text(
                      c.running
                          ? c.t('结束练习', 'Finish practice')
                          : c.phase == PracticePhase.error
                          ? c.t('重试', 'Try again')
                          : c.t('开始练习', 'Start practice'),
                    ),
                  ),
                ),
                if (c.phase == PracticePhase.error)
                  TextButton(
                    onPressed: openSystemSettings,
                    child: Text(c.t('打开系统设置', 'Open system settings')),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            _pill(
              Icons.check_circle_outline,
              c.t(
                '已练 ${stats.attempts.length} 题',
                '${stats.attempts.length} practiced',
              ),
            ),
            _pill(
              Icons.local_fire_department_outlined,
              c.t('连对 ${stats.streak} 题', '${stats.streak} in a row'),
            ),
          ],
        ),
        const SizedBox(height: 24),
        Text(
          c.t(
            '小提示：听完题目，看到麦克风后再回答。没听清时会再练同一道题，不用着急。',
            'A little tip: wait for the microphone before answering. If we don’t hear you, you can try the same question again.',
          ),
          style: const TextStyle(color: Color(0xff627781), height: 1.6),
        ),
      ],
    );
  }

  Widget _pill(IconData icon, String text) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 20, color: teal),
        const SizedBox(width: 8),
        Text(text),
      ],
    ),
  );
}
