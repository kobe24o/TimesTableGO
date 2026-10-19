import 'dart:io';

import 'package:flutter/services.dart';

class AndroidUpdateService {
  static const _channel = MethodChannel('timestablego/update');
  static final Stream<Map<dynamic, dynamic>> states = Platform.isAndroid
      ? const EventChannel('timestablego/update-state')
            .receiveBroadcastStream()
            .map((e) => e as Map<dynamic, dynamic>)
            .asBroadcastStream()
      : const Stream.empty();
  static Future<void> action(String name) =>
      _channel.invokeMethod<void>(name, {'automatic': false});
}
