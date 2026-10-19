import 'dart:io';

import 'package:flutter/material.dart';

import '../practice/practice_controller.dart';
import '../services/android_update_service.dart';
import 'app.dart';

class UpdateBanner extends StatelessWidget {
  const UpdateBanner({super.key, required this.controller, this.stateStream});
  final PracticeController controller;
  final Stream<Map<dynamic, dynamic>>? stateStream;
  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid && stateStream == null) {
      return const SizedBox.shrink();
    }
    return StreamBuilder<Map<dynamic, dynamic>>(
      stream: stateStream ?? AndroidUpdateService.states,
      builder: (context, snapshot) {
        final state = snapshot.data?['state'];
        if (!['available', 'downloading', 'ready', 'error'].contains(state)) {
          return const SizedBox.shrink();
        }
        final c = controller;
        final text = state == 'error'
            ? (snapshot.data?[c.settings.language] as String? ??
                  c.t('更新失败，请重试', 'Update failed. Please try again.'))
            : state == 'ready'
            ? c.t('新版已准备好', 'Your update is ready')
            : state == 'downloading'
            ? c.t('正在下载新版…', 'Downloading update…')
            : c.t('有新版本可用', 'An update is available');
        return Material(
          color: const Color(0xffe5f1ee),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Row(
              children: [
                const Icon(Icons.system_update_rounded, color: teal),
                const SizedBox(width: 10),
                Expanded(child: Text(text)),
                if (state != 'downloading')
                  TextButton(
                    onPressed: () async {
                      await c.stop();
                      if (context.mounted) {
                        await runAction(
                          context,
                          () => AndroidUpdateService.action(
                            state == 'error'
                                ? 'check'
                                : state == 'ready'
                                ? 'install'
                                : 'download',
                          ),
                        );
                      }
                    },
                    child: Text(
                      state == 'error'
                          ? c.t('重试', 'Retry')
                          : state == 'ready'
                          ? c.t('安装', 'Install')
                          : c.t('下载', 'Download'),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}
