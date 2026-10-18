# TimesTable GO · 乘法口诀语音练习

Flutter 跨平台应用位于 `flutter_app/`，同一套界面与练习逻辑支持 Android 和 iOS。保留原 Android 包名，旧原生工程 `app/` 用于资源、行为和安全更新协议的参考。

## 使用

打开应用，点「开始练习」，允许麦克风访问。听完题目，看到麦克风后说出答案；应用会播放反馈，答错时给出订正。无语音时重复当前题，不计为答错。

- 全部 81 道乘法题，中英文界面和固定录音。
- 首次安装默认英语，可在设置中切换中文；升级保留已有语言设置。
- 默认每题 3 秒，可在设置中调整为 1–15 秒。
- SenseVoice int8 + Silero VAD 在设备上识别；模型与音频随包提供，无需账号或云端 API。
- 学习统计包含正确率、连对、81 题覆盖率、掌握度、六项成就与最近作答。
- 语音不会上传，录音只在内存中短暂使用；设置和答题记录保存在本机。
- Android 升级保留包名 `com.example.multiplicationcoach`，首次迁移读取旧 `practice` SharedPreferences。

## 构建与验证

使用 Flutter 3.47.3、Java 17、Android SDK 和 Xcode。首次检出需要 Git LFS 拉取模型；模型真文件为 239,233,841 字节，不能将 LFS 指针打包。

```sh
git lfs pull
python3 tools/prepare_flutter_assets.py
cd flutter_app
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
flutter build ios --simulator --debug
```

资源准备脚本校验原 manifest 的 SHA-256，并使用 ffmpeg（或可解码 Ogg 的 macOS afconvert）生成 332 条通用 WAV。没有 Git LFS 时，可提供已下载且摘要匹配的原模型：`python3 tools/prepare_flutter_assets.py --model /absolute/path/model.int8.onnx`。生成的 Flutter 模型副本不会重复提交到 Git。

运行 iOS 原生集成测试（验证真实双语识别、反馈播放、页面交互并保存截图）：

```sh
flutter drive --driver=test_driver/integration.dart \
  --target=integration_test/app_test.dart --device-id=YOUR_SIMULATOR_ID
```

Android 更新安全测试位于 `flutter_app/android/app/src/test/`：在该 `android/` 目录运行 `./gradlew :app:testDebugUnitTest`。

## 发布

iOS 正式版采用付费下载、一次性买断全部功能，用户选定中国大陆基准价格人民币 ¥9.9；其他地区使用 Apple 对应货币的等价价格。TestFlight 测试不收取真实费用。付费协议、收款和税务资料须由账号持有人完成。

- Android：`.github/workflows/build-apk.yml` 构建 Flutter 应用，使用原 keystore 签名并核对固定证书。主分支发布不可变标签及签名更新清单；Wi-Fi 自动下载，移动网络需用户主动下载，安装前校验大小、SHA-256、包名、版本和发布证书，再交给系统安装器确认。
- iOS：`app.timestablego`，Apple 团队 `P24F5QH7BB`，独立 App Store Connect 记录 `6824225139`。`ios/ExportOptions.plist` 与 Release 配置使用 `TimesTable GO App Store` 分发描述文件。
- 在 `flutter_app/` 运行 `flutter build ipa --release --target lib/main.dart --export-options-plist=ios/ExportOptions.plist`。
- 手动 GitHub 工作流 `.github/workflows/ios-testflight.yml` 支持签名上传、处理轮询、分组及 Beta 审核提交。仓库仍需填写 `APPSTORE_CERTIFICATES_PASSWORD` 后才能在 CI 解锁已上传的证书；本机可使用钥匙串现有分发身份。
- TestFlight 公开组 `outer` 上限 100 人；[测试链接](https://testflight.apple.com/join/DfHF6NSJ)。链接是否可安装取决于 Apple 构建处理和 Beta 审核状态。

发布凭据仅放在钥匙串或 GitHub Secrets。不要提交 `.p8`、`.p12`、keystore 或密码。当前验证结果见 `docs/verification/2026-10-18-status.md`。

## 图标与许可

新图标母版在 `design/timestablego-icon.png`。用装有 Pillow 的 Python 运行 `tools/generate_app_icons.py` 可更新两端图标及 iOS 启动画面。

原音频由开发期 CosyVoice-300M-SFT 生成，运行时不携带 TTS 权重。原生成工具仍在 `tools/`。模型和音频许可随包位于 `flutter_app/assets/asr/LICENSES/` 与 `flutter_app/assets/licenses/`，并可在设置页查看。
