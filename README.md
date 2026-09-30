# 乘法口诀背诵 App

这是一个 Android 本地语音练习应用：题目音频、录音、离线识别、答案判定和学习记录都在设备上处理。应用不申请网络权限，也不包含云端 ASR、TTS、LLM 或 API Key 配置。

## 语音方案

- 题目：81 道乘法题及 4 条反馈音频，以 22.05 kHz 单声道 Ogg/Vorbis 打包在 APK 中。
- 合成来源：本地执行 CosyVoice-300M-SFT 的固定中文女声音色；运行时不携带 TTS 权重。
- 识别：APK 内置 sherpa-onnx SenseVoice int8 和 Silero VAD，默认完全离线。
- 后备：用户可以在设置中显式选择 Android 系统 ASR。
- 判定：转写只会由本地 AnswerTranscriptParser 解析；歧义结果不会猜测答案。

## 构建

运行 gradlew.bat :app:testDebugUnitTest :app:assembleRelease。

APK 位于 app/build/outputs/apk/release。正式发布请在 CI 配置稳定的 release keystore。

当前本地构建产物（2026-09-30）为 204,292,260 字节，SHA-256：
`fe82c7e65460bbadca07159606a22940d1c6003aebcbbdfd87ca52a589c530fc`。
该产物在未提供 release keystore 时使用 Android Debug 证书签名，仅供本机安装测试；正式发布前必须替换为稳定的发布证书。

## 重新生成固定音频

CosyVoice 权重只用于开发期音频生成，不能放入 APK。准备好本地 CosyVoice 源码、Python 环境和 CosyVoice-300M-SFT 模型后，运行 tools/generate_fixed_voice_audio.ps1，并提供 Python、CosyVoiceDir、ModelDir 和 Speaker 参数。

语料清单在 tools/fixed_voice_lines.txt；脚本会拒绝不完整或非 85 个资源的输出。

## 许可

- SenseVoice、Silero VAD 和 sherpa-onnx 的原始许可文件位于 app/src/main/assets/asr/LICENSES。
- CosyVoice 的来源、模型版本、Apache-2.0 声明和重新生成信息位于 app/src/main/assets/licenses/cosyvoice-notice.txt。
