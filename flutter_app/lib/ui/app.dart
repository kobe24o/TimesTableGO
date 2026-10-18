import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../practice/practice_controller.dart';
import 'practice_page.dart';
import 'statistics_page.dart';
import 'settings_page.dart';
import 'update_banner.dart';

const ink = Color(0xff183b4e),
    teal = Color(0xff167d80),
    paper = Color(0xfff7f6f0);

class TimesTableApp extends StatelessWidget {
  const TimesTableApp({super.key, required this.controller});
  final PracticeController controller;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) => MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'TimesTable GO',
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: paper,
        colorScheme: ColorScheme.fromSeed(
          seedColor: teal,
          primary: teal,
          surface: paper,
        ),
        textTheme: Theme.of(context).textTheme
            .apply(bodyColor: ink, displayColor: ink),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            minimumSize: const Size(48, 56),
            textStyle: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        cardTheme: const CardThemeData(
          color: Colors.white,
          elevation: 0,
          margin: EdgeInsets.zero,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(24)),
          ),
        ),
      ),
      home: AppShell(controller: controller),
    ),
  );
}

class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.controller});
  final PracticeController controller;
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  int tab = 0;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) unawaited(widget.controller.stop());
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: [
              PracticePage(controller: c),
              StatisticsPage(controller: c),
              SettingsPage(controller: c),
            ][tab],
          ),
        ),
      ),
      bottomNavigationBar: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          UpdateBanner(controller: c),
          NavigationBar(
            selectedIndex: tab,
            onDestinationSelected: (value) {
              if (value != tab) {
                unawaited(c.stop());
                setState(() => tab = value);
              }
            },
            destinations: [
              NavigationDestination(
                icon: const Icon(Icons.mic_none_rounded),
                selectedIcon: const Icon(Icons.mic_rounded),
                label: c.t('练习', 'Practice'),
              ),
              NavigationDestination(
                icon: const Icon(Icons.insights_rounded),
                label: c.t('统计', 'Progress'),
              ),
              NavigationDestination(
                icon: const Icon(Icons.tune_rounded),
                label: c.t('设置', 'Settings'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class PageHeading extends StatelessWidget {
  const PageHeading(this.title, this.subtitle, {super.key});
  final String title, subtitle;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(
            fontSize: 30,
            fontWeight: FontWeight.w800,
            letterSpacing: -1,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          subtitle,
          style: const TextStyle(
            fontSize: 15,
            color: Color(0xff627781),
            height: 1.5,
          ),
        ),
      ],
    ),
  );
}

Future<void> runAction(
  BuildContext context,
  Future<void> Function() action,
) async {
  try {
    await action();
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('保存失败，请重试 / Could not save. Please try again.'),
        ),
      );
    }
  }
}

Future<void> openSystemSettings() async {
  try {
    await const MethodChannel('timestablego/settings')
        .invokeMethod<void>('open');
  } on PlatformException {
    /* Keep retry action available. */
  }
}
