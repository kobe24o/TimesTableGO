import 'package:flutter/material.dart';

import '../domain/practice.dart';
import '../practice/practice_controller.dart';
import 'app.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, required this.controller});
  final PracticeController controller;
  @override
  Widget build(BuildContext context) {
    final c = controller, s = c.settings;
    void update({String? language, int? seconds, bool? prompt}) {
      runAction(
        context,
        () => c.updateSettings(
          Settings(
            language: language ?? s.language,
            seconds: seconds ?? s.seconds,
            prompt: prompt ?? s.prompt,
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        PageHeading(
          c.t('按你的节奏来', 'Make it yours'),
          c.t(
            '默认设置就能开始，随时都可以调整。',
            'Ready from the start. Adjust whenever you like.',
          ),
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  c.t('练习语言', 'Practice language'),
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 14),
                Wrap(
                  spacing: 12,
                  children: [
                    ChoiceChip(
                      label: const Text('中文'),
                      selected: s.language == 'zh',
                      onSelected: (_) => update(language: 'zh'),
                    ),
                    ChoiceChip(
                      label: const Text('English'),
                      selected: s.language == 'en',
                      onSelected: (_) => update(language: 'en'),
                    ),
                  ],
                ),
                const Divider(height: 32),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(c.t('朗读题目', 'Read questions aloud')),
                  subtitle: Text(
                    c.t('听完题目后才开始计时', 'The timer starts after the question'),
                  ),
                  value: s.prompt,
                  onChanged: (v) => update(prompt: v),
                ),
                const Divider(height: 32),
                Text(
                  c.t(
                    '每题答题时间：${s.seconds} 秒',
                    'Answer time: ${s.seconds} seconds',
                  ),
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                Slider(
                  value: s.seconds.toDouble(),
                  min: 1,
                  max: 15,
                  divisions: 14,
                  label: '${s.seconds}',
                  onChanged: (v) => update(seconds: v.round()),
                ),
                Text(
                  c.t(
                    '刚开始可以选 5–8 秒，熟练后再缩短。',
                    'Try 5–8 seconds at first, then shorten it as you improve.',
                  ),
                  style: const TextStyle(color: Color(0xff627781), height: 1.5),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.privacy_tip_outlined, color: teal),
                const SizedBox(height: 12),
                Text(
                  c.t('安心离线练习', 'Comfortably offline'),
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  c.t(
                    '录音在本机识别，用完即丢弃；学习记录只保存在这台设备上。无需登录。',
                    'Speech is recognized on your device and discarded afterward. Practice history stays here. No sign-in needed.',
                  ),
                  style: const TextStyle(height: 1.6),
                ),
                const SizedBox(height: 12),
                TextButton.icon(
                  onPressed: () => showLicensePage(
                    context: context,
                    applicationName: 'TimesTable GO',
                    applicationVersion: '0.2.0',
                  ),
                  icon: const Icon(Icons.info_outline),
                  label: Text(c.t('开源许可', 'Open-source licenses')),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        OutlinedButton.icon(
          onPressed: () async {
            final confirmed = await showDialog<bool>(
              context: context,
              builder: (context) => AlertDialog(
                title: Text(c.t('清空学习记录？', 'Clear practice history?')),
                content: Text(
                  c.t(
                    '清空后无法恢复，练习设置会保留。',
                    'This cannot be undone. Your settings will stay.',
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: Text(c.t('保留记录', 'Keep history')),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: Text(c.t('确认清空', 'Clear history')),
                  ),
                ],
              ),
            );
            if (confirmed == true && context.mounted) {
              await runAction(context, c.clearHistory);
            }
          },
          icon: const Icon(Icons.delete_outline),
          label: Text(c.t('清空学习记录', 'Clear practice history')),
        ),
        const SizedBox(height: 20),
        const Center(
          child: Text(
            'TimesTable GO · 0.2.0',
            style: TextStyle(color: Color(0xff627781)),
          ),
        ),
      ],
    );
  }
}
