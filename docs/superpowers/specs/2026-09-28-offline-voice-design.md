# 离线语音 APK 设计

## 目标

将乘法口诀练习改为不依赖百度、Azure 或大模型的语音路径。Android 手机在无网络条件下能够播放题目、录制答案、识别普通话答案，并以本地规则判对 1 到 81 的结果。

## 已确认约束

- 固定题目与反馈使用内置音频，而不是运行时 TTS。
- ASR 模型必须随 APK 打包，不能首次下载。
- 不使用大模型进行答案提取或判断。
- Android 系统 ASR 保留为备用选项；默认使用应用内的本地 ASR。
- 内置音频由可商用许可的中文合成音生成。生成阶段采用 `FunAudioLLM/CosyVoice-300M-SFT` 的内置中文音色；该模型和仓库标注为 Apache-2.0。生成模型不随 APK 分发。

## 运行时架构

```text
题目/固定反馈 ──> res/raw/*.ogg ──> MediaPlayer

AudioRecord ──> 16 kHz PCM ──> Silero VAD ──> SenseVoice int8 ──> 数字解析器 ──> 正误判断
                                  (assets/asr, arm64-v8a)

备用路径：Android SpeechRecognizer ──> 数字解析器 ──> 正误判断
```

### 音频

为 81 个乘法题目及固定状态/鼓励语生成单声道 22.05 kHz Ogg Opus 文件。资源名由纯 Kotlin 的题目音频索引产生，播放失败时显示文字反馈但不中断练习。运行时不调用 Android `TextToSpeech`、百度 TTS 或任何网络服务。

### 本地 ASR

使用 sherpa-onnx 的 Android/Kotlin JNI 接口、`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`、其 `tokens.txt`，以及 Silero VAD。模型和 JNI 库直接放入应用资源：模型只支持 `arm64-v8a`，因此 APK 只发布给 64 位 ARM Android 设备。模型约 228 MB；JNI/ONNX Runtime 额外占用约 20 MB，预计最终 APK 增量约 250 MB。

启动时将不可直接随机访问的 assets 复制到 `filesDir/asr/`，按资源版本和 SHA-256 校验；成功后创建识别器。若资源校验或识别器初始化失败，界面给出明确提示，并允许用户改用系统 ASR。

录音沿用 16 kHz 单声道 PCM。练习计时结束后以 VAD 截取有效语音，再运行一次离线识别；不把音频或转写上传。

### 数字规则

新增纯 Kotlin `AnswerTranscriptParser`。它按优先级识别阿拉伯数字、中文数字和常见引导词（如“答案是二十四”），只接受 1..81；无法唯一确定时返回空。正确性始终比较解析整数与题目答案。该规则替代 `AnswerVerifier.verifyWithLlm`，并且答案路径中不保留网络回退。

### 设置与隐私

`asrProvider` 缩减为 `local` 和 `system`，默认 `local`。删除百度/Azure/LLM 设置、凭据持久化与 UI，移除 `INTERNET` 权限以及所有网络语音客户端。保留 `RECORD_AUDIO`。

应用内“关于/许可”列出：sherpa-onnx（Apache-2.0）、SenseVoice 模型的实际随包许可证、Silero VAD 的实际随包许可证，以及用于生成内置音频的 CosyVoice 模型归属和 Apache-2.0 提示。随包模型的许可证文件必须原样放入 APK assets。

## 可测试边界

- `AnswerTranscriptParser`：覆盖阿拉伯数字、中文数字、引导语、越界值、多个候选和空结果。
- `PromptAudioIndex`：覆盖题目到资源名的稳定映射及反馈键映射。
- `OfflineAsrAvailability`：仅根据资源清单/校验结果报告可用性，不依赖 Android UI。
- Android 集成：本地模式不会构造网络请求；系统模式继续使用 `SpeechRecognizer`。

## 验收证据

1. 单元测试在新增规则与音频索引上通过。
2. release APK 构建通过，且 APK 分析显示 ASR 模型、词表、VAD、许可证和 Ogg 音频在包内。
3. 在 arm64 Android 设备或模拟设备安装 APK：关闭网络后可播放题目、完成一次本地语音作答，并保存练习记录。
4. 搜索源码与 manifest，不再包含百度/Azure/LLM 网络端点、密钥设置或 `INTERNET` 权限。

## 非目标

- 不支持 32 位 ARM 设备。
- 不在手机上运行通用 TTS 模型；固定音频不会在设备上二次合成。
- 不承诺系统 ASR 离线可用性，它仅是设备服务的备用通道。
