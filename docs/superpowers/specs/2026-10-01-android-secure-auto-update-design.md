# Android 安全自动更新设计

## 目标与边界

乘法口诀背诵 App 在 Android 上采用与 BibleRecite 相同的本地优先更新体验：

- 每次冷启动后在后台检查更新；发现新版本时，在 Wi-Fi 下自动下载，下载完成后显示“安装更新”。
- 使用移动网络时不自动下载，用户在设置页点击“下载更新”后才开始。
- 安装前必须验证更新清单签名、APK 大小、SHA-256、包名、版本号、版本号递增与 APK 签名证书。
- 通过 Android 系统安装器完成安装。普通 Android App 不能静默安装，因此首次安装需用户允许“允许来自此应用的安装”，并在系统安装器中确认。
- 更新失败不得影响背诵练习，也不得下载、安装或保留未经校验的 APK。

本设计不增加服务器，也不上传学习记录、录音或语音识别数据。

## 发布与信任链

GitHub Actions 将不再只替换可变的 `latest` 附件。每个 `main` 构建将：

1. 从 `app/build.gradle.kts` 读取 `versionName` 和 `versionCode`，构建固定签名 APK。
2. 以不可变标签 `v<versionName>-<versionCode>` 创建 GitHub Release，并上传版本化 APK 与 SHA-256 文件。
3. 从 Release 的 APK 计算字节长度和 SHA-256，生成包含 APK URL、包名、版本、证书摘要、发布时间与 Release 页面地址的 JSON 负载。
4. 使用 GitHub Secret `UPDATE_MANIFEST_PRIVATE_KEY_B64` 中的 RSA 私钥，以 `SHA256withRSA` 对负载字节签名；私钥只存在于 Actions 运行环境。
5. 将 `{ protocol, payload, signature }` 写入 `update-feed` 分支的 `updates/latest.json`。App 仅内置对应的 RSA 公钥，依次通过 GitHub Raw 和 jsDelivr HTTPS 地址读取该文件。

签名更新清单指向固定标签资产，而非可变 `latest` 资产，防止后续发布改变已签名清单指向的文件。APK 本身仍必须通过哈希和证书复核。

## App 组件

### 更新清单与验签

新增纯 Kotlin 模型和解析器：

- `UpdateManifest`：协议、版本、Release 信息及 Android APK 资产字段。
- `SignedUpdateEnvelope`：解码 Base64 负载和签名，只接受协议版本 1。
- `UpdateFeedClient`：尝试所有 HTTPS 源，使用内置 RSA 公钥验证签名，只选择版本号最高的有效清单。
- `UpdateVerifier`：严格比较 APK 字节数与 SHA-256，并拒绝不匹配的包名、签名证书、版本名或 `versionCode`。

远端数据均不可信；字段缺失、非 HTTPS URL、无效 Base64、无效签名、非递增版本以及任何校验不一致都视为更新不可用。

### 下载、暂存与系统安装

`UpdateRepository` 在 `cacheDir/updates/` 下载到 `.part` 文件，完成哈希校验后再原子移动成 APK。取消或失败时删除 `.part` 和不合格 APK。

新增 Android `FileProvider`，仅暴露这个私有缓存目录。`PackageManager.getPackageArchiveInfo` 读取下载 APK 的包名、版本和签名证书；校验成功后使用 `ACTION_INSTALL_PACKAGE` 打开 Android 系统安装器。安装前检查 Android 8+ 的“未知来源安装”授权；未授权时跳转系统授权页，返回后可再次点安装。

### UI 与状态

新增 `UpdateState`（空闲、检查中、可下载、下载中、可安装、当前已最新、失败）和 `UpdateViewModel`。

- 启动后静默检查，不中断离线语音模型初始化与练习。
- Wi-Fi 下发现新版本自动下载；移动网络仅提示“发现新版本”。
- 设置页显示当前版本，提供“检查更新”；下载中显示进度并可取消；校验完成显示“安装更新”。
- 更新错误显示为可重试的简短信息，不暴露密钥、下载地址或堆栈。

## Android 配置

增加 `INTERNET`、`ACCESS_NETWORK_STATE` 与 `REQUEST_INSTALL_PACKAGES` 权限，以及 `FileProvider` 的 `cache-path` 配置。继续保持 `allowBackup=false`。离线 ASR、题目音频、练习数据和麦克风数据仍不访问网络；网络权限仅服务于明确的更新检查与下载。

## 测试与发布验证

测试先于实现，至少覆盖：

- 有效 RSA 签名清单被接受；签名、协议、HTTPS URL 或版本字段错误被拒绝。
- 仅新于本机的版本可用；哈希或长度不符时删除下载文件。
- APK 检查结果的包名、证书、版本名和版本号必须全部相符。
- Wi-Fi 自动下载、移动网络等待用户确认、取消下载和安装权限未授予的状态迁移。
- 生成清单脚本对已构建 APK 的长度、SHA-256 与版本字段输出正确，且 CI 在发布前验证 Release APK 证书。

完成实现后执行 Android 单元测试、Release 构建、APK 清单/签名检查，以及 GitHub Actions 发布验证。真实设备安装授权与更新安装由用户在手机上确认。
