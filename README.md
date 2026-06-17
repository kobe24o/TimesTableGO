# 乘法口诀背诵 App

当前实现使用 Kotlin + Jetpack Compose。这个选择主要是为了更直接地调用 Android TTS、麦克风录音、系统语音识别和本机存储；后续如果需要跨平台，可以迁移到 Flutter。

一个 Android MVP，用于随机练习 1-9 乘法口诀：

- 随机出题：`A x B = ?`
- 开始 / 停止练习
- 可选系统 TTS 读题
- 3 秒语音作答窗口，可选 Android 系统 ASR、百度语音识别或 Azure Speech
- 可选讯飞星辰 MaaS OpenAI 兼容接口做答案校验
- 本机统计答题历史、高频错题和正确率曲线

## 安全说明

不要把 API Key 写进源码。打开 App 后进入“设置”，再填入对应服务的密钥。

默认值：

- API Base: `https://maas-api.cn-huabei-1.xf-yun.com/v2`
- Model ID: `qwen3.6-35b-a3b`
- Azure Endpoint: `https://asia25.cognitiveservices.azure.com`
- Azure Region: `eastasia`

## 语音识别

当前支持三种模式：

- 系统 ASR：免费，不需要密钥，依赖手机系统语音识别服务。
- 百度 ASR：在设置里填百度语音 `API Key` 和 `Secret Key`。
- Azure Speech：在设置里填 `Speech Key` 和 `Endpoint`。你的 Azure 资源名看起来是 `asia25`，默认 endpoint 已设为 `https://asia25.cognitiveservices.azure.com`；`Region` 作为旧区域端点备用。

## 构建

```powershell
./gradlew.bat :app:assembleDebug
```

如果本机没有 Gradle Wrapper，可以用已安装的 Gradle：

```powershell
gradle :app:assembleDebug
```

## GitHub Release APK 固定签名

GitHub Actions 会在 `main` 分支构建固定签名的 release APK，并上传到 `latest` Release。
为了避免“签名不一致，需要卸载后安装”，需要在仓库 Settings -> Secrets and variables -> Actions 里配置这些 Secrets：

- `ANDROID_KEYSTORE_BASE64`：release keystore 文件的 Base64 内容
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密码
- `ANDROID_KEY_ALIAS`：key alias
- `ANDROID_KEY_PASSWORD`：key 密码

本地生成 keystore 示例：

```powershell
keytool -genkeypair -v -keystore release.jks -alias multiplication-coach -keyalg RSA -keysize 2048 -validity 10000
```

把 keystore 转成 Base64 后填入 `ANDROID_KEYSTORE_BASE64`：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```

不要把 `release.jks`、密码或 Base64 内容提交到仓库。
