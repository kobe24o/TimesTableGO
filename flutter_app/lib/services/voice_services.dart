import 'dart:typed_data';

abstract interface class VoiceServices {
  Future<void> initialize(String language);
  Future<bool> permission();
  Future<void> play(String name);
  Future<void> begin();
  Future<Float32List> finish();
  Future<String> transcribe(Float32List samples);
  Future<void> cancel();
  Future<void> dispose();
}
