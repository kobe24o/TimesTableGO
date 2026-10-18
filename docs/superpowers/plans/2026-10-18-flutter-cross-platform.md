# Flutter Android / iOS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成保留现有功能的 Flutter Android / iOS 应用、美化界面并发布 TestFlight。

**Architecture:** Flutter 应用位于 `flutter_app/`，业务域与设备服务分离，通过可替换接口测试录音、识别和播放。Android 平台适配保留历史导入与安全更新协议；iOS 使用相同离线模型及独立应用标识。

**Tech Stack:** 本机 Flutter SDK、Dart、sherpa_onnx、record、本地存储、跨平台音频播放器、Kotlin 平台通道、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-18-flutter-ios-design.md`

## Global Constraints

- Android 沿用 `com.example.multiplicationcoach` 与现有签名，版本号高于当前 0.1.11 / 12。
- iOS 拟用 `app.timestablego`，注册前检查唯一性，使用团队 `P24F5QH7BB`。
- 默认开启读题、中文、3 秒答题时间；设置可切换语言、开关读题、调整 1–15 秒时限。
- 题库包含 1–9 两个因子的全部 81 个有序组合，随机出题。
- 采样为 16 kHz 单声道 PCM；首次启动不要求下载。
- 全程本机处理，不上传录音，不把识别结果替换成预期答案。
- 主要按钮触控区域至少 48 逻辑像素。
- 不得将私钥或密码写入 Git 或日志。

## Review Focus

- 取消后才返回的识别结果不能记分：Task 3 使用受控异步假服务测试。
- Ogg 不受 iOS 播放器支持：Task 2 验证解码及转换后资源完整性，Task 6 真机播放。
- 低存储或损坏历史：Task 1 验证保留原始数据并报告错误。
- 音频中断、权限撤销、切后台：Task 3 测试停止录音与取消计时。
- Android 旧版本安装覆盖与证书不匹配：Task 5 验证签名检查及一次性迁移。

## Task 1: 共享业务域及持久化

**Files:** `flutter_app/pubspec.yaml`, `lib/domain/problem.dart`, `lib/domain/answer_parser.dart`, `lib/domain/statistics.dart`, `lib/data/practice_store.dart`（后三者均位于 flutter_app），`flutter_app/test/domain_test.dart`, `flutter_app/test/store_test.dart`。

**Interfaces:** `Problem(int a, int b)` exposes `answer`; `AnswerParser.parse(String text, {required Problem problem, required String language}) -> int?`; `PracticeStore.load() -> Future<StoredPractice>`; `PracticeStore.save(StoredPractice data) -> Future<void>`。`StoredPractice` 包含设置、最新在前的作答记录、迁移状态；作答字段兼容原 a/b/expected/transcript/extractedAnswer/correct/checkedBy/timestamp。

- [ ] 在隔离分支建立 Flutter 工程，保留旧 Android 工程用于对照。
- [ ] 添加失败测试：81 个唯一组合；中文、英文、阿拉伯答案和完整算式；相互冲突的答案返回 null；超过 81、不完整和非数字输入不判对。
- [ ] 执行 `flutter test test/domain_test.dart`，确认缺少实现导致失败。
- [ ] 实现业务模型、解析和六个成就阈值；完整算式必须匹配当前因子；普通答案仅接受唯一有效值。
- [ ] 添加存储失败测试：默认 3 秒、时限边界 1/15、记录往返、损坏文件不被空数据覆盖、旧 Android 历史仅导入一次。
- [ ] 实现带版本的 JSON 存储，失败保留原文件；设置和历史写入串行化。
- [ ] 运行以上测试全部通过，提交本任务。

## Task 2: 离线资源和设备音频

**Files:** `flutter_app/lib/services/audio_service.dart`, `recognition_service.dart`, `asset_installer.dart`（均在同目录），`flutter_app/test/assets_test.dart`, `tools/prepare_flutter_assets.py`。

**Interfaces:** `AudioService.play(String asset) -> Future<void>` / `stop() -> Future<void>`；`RecognitionService.initialize(String language) -> Future<void>` / `transcribe(Float32List samples) -> Future<String>` / `dispose() -> Future<void>`；识别函数不接受 expectedAnswer。`AssetInstaller.install() -> Future<InstalledModels>` 返回已校验的模型路径。

- [ ] 核实插件官方文档、兼容性和许可，锁定依赖；取回 Git LFS 真实模型并核对原 manifest 摘要。
- [ ] 添加失败测试：双语各 81 题读题、81 题订正、4 条反馈都存在；任何模型为 LFS 指针或摘要不一致时拒绝初始化。
- [ ] 建立可重复的资源准备脚本；按实际 iOS 支持情况将 Ogg 转为 AAC/WAV，保留原文件与许可证。
- [ ] 实现后台识别、VAD 分段及整段回退；回退根据有效数字及歧义规则选择，不能根据正确答案挑选识别结果。
- [ ] 实现可取消的音频播放及录音服务；播放结束 Future 必须在真实结束后才完成，中断及 stop 不能悬挂等待。
- [ ] 运行资源测试；保存一条中文及英文识别烟测证据，提交本任务。

## Task 3: 可取消的练习流程

**Files:** `flutter_app/lib/practice/practice_controller.dart`, `flutter_app/test/practice_controller_test.dart`。

**Interfaces:** `PracticeController.start() -> Future<void>` / `stop() -> Future<void>` / `updateSettings(Settings value) -> Future<void>`；对外暴露可监听的 `PracticeState`，包含 problem/phase/countdown/transcript/feedback/attempts/error。

- [ ] 用可控时钟与假设备服务写失败测试：读题未结束不录音，录音启动后才倒计时，静音重复不计错，错误反馈等待完成才换题。
- [ ] 添加竞态测试：停止、语言切换、进入后台、权限失败及中断后才返回识别结果，不得计分或重启录音。
- [ ] 实现显式状态机 idle/preparing/prompting/recording/recognizing/feedback/error，使用会话代号使旧异步结果失效，串行化服务释放。
- [ ] 控制器不依赖 Widget；练习成功保存一次记录，失败不计为错误答案；跑通测试后提交。

## Task 4: 双语界面和统计

**Files:** `flutter_app/lib/main.dart`, `lib/ui/app_theme.dart`, `lib/ui/practice_page.dart`, `lib/ui/statistics_page.dart`, `lib/ui/settings_page.dart`, `lib/ui/strings.dart`（上述 lib 位于 flutter_app），`flutter_app/test/widget_test.dart`。

**Interfaces:** `TimesTableApp({required PracticeController controller})`；所有页面读取共享设置并随语言切换更新，统计使用 Task 1 的纯函数。

- [ ] 添加 Widget 测试：三个导航入口、开始与停止、语言切换、设置恢复、统计空态、清空二次确认。
- [ ] 实现温暖浅色/深蓝/青绿主题、题目大字、进度环和减少动画适配；统计展示六个成就、趋势、81 格掌握度与最近十次记录。
- [ ] 在窄屏、大字体及横屏测试无布局溢出；文字和语义不依赖颜色表达正误。
- [ ] 运行静态分析和全部测试，启动模拟器逐页截图检查，中英文均验证后提交。

## Task 5: Android 升级与安全更新

**Files:** `flutter_app/android/app/src/main/kotlin/com/example/multiplicationcoach/MainActivity.kt`, 对应 `update/` 适配，`flutter_app/lib/services/android_update_service.dart`, Android 签名配置及 `.github/workflows/android-release.yml`。

**Interfaces:** 平台通道 `timestablego/legacy` 的 `readPractice` 返回原 SharedPreferences 原始设置与 JSON，Flutter 验证后保存；`timestablego/update` 桥接原安全更新策略。

- [ ] 为迁移及更新拒绝路径添加测试：非 Wi-Fi 不自动下载、摘要/包名/版本/证书不匹配拒绝安装、已迁移不重复导入。
- [ ] 保留原清单签名公钥和验证实现，用现有 CI keystore 构建更高版本，安装交给系统确认。
- [ ] 切换 Android 发布 CI 到 Flutter，保留不可变发布标签和签名更新清单机制。
- [ ] 构建 APK，验证应用 ID、版本和证书；在安装旧版的设备覆盖安装检查记录与设置，提交本任务。

## Task 6: iOS 签名、验证与 TestFlight

**Files:** `flutter_app/ios/Runner/Info.plist`, `Runner.xcodeproj/project.pbxproj`, `ExportOptions.plist`（后两者在 flutter_app/ios），`.github/workflows/ios-testflight.yml`, `scripts/assign-testflight-group.mjs`, `README.md`。

**Interfaces:** 工作流消费已配置 `APPSTORE_API_PRIVATE_KEY`, `APPSTORE_CERTIFICATES_FILE_BASE64`, `APPSTORE_CERTIFICATES_PASSWORD` 与 API 标识变量；只在测试和构建通过后上传。

- [ ] 配置麦克风用途说明、隐私声明、应用图标和团队；核实 app.timestablego 可注册并建立独立 App Store Connect 应用和描述文件。
- [ ] 取得证书密码后验证证书归属及有效期；缺少密码时继续无签名构建，不宣称归档上传成功。
- [ ] 运行 Flutter 全量测试和分析，构建 iOS 模拟器及 Android APK，保存结果。
- [ ] 在两端真机飞行模式测试中英文识别、音频播放、停止、后台中断与数据恢复，记录设备与结果。
- [ ] 参考 bible_recite 工作流实现归档、签名、上传、处理轮询和测试组分配；失败必须报告 Apple 原始状态。
- [ ] 上传后核实构建处理状态与审核结果，记录构建号和可用测试链接；更新文档，提交最终交付。

## 完成标准

只有设计验收六项均有实际证据才能宣称整个任务完成；模型存在、测试通过、签名配置和 TestFlight 上传分别验证，不能相互替代。真机或 Apple 凭据缺失时保留任务未完成并准确报告受阻事项。
