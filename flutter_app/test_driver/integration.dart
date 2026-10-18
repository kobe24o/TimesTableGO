import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

Future<void> main() async {
  await integrationDriver(
    onScreenshot: (name, bytes, [args]) async {
      final directory = Directory('../docs/verification');
      await directory.create(recursive: true);
      await File('${directory.path}/ios-$name.png').writeAsBytes(bytes);
      return true;
    },
  );
}
