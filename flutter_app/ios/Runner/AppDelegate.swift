import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "TimesTableSettings")!
    FlutterMethodChannel(name: "timestablego/settings", binaryMessenger: registrar.messenger())
      .setMethodCallHandler { call, result in
        guard call.method == "open" else { result(FlutterMethodNotImplemented); return }
        guard let url = URL(string: UIApplication.openSettingsURLString) else { result(nil); return }
        UIApplication.shared.open(url) { _ in result(nil) }
      }
  }
}
