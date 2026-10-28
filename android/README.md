# Device Bridge Android v0.1

原生 Kotlin / Jetpack Compose Android 客户端，连接本仓库 Windows 主机。当前支持授权 Codex 会话目录、分页消息、文本发送、操作记录、前台同步和未知结果核对。ChatGPT 常驻、新建/项目管理、模型设置、手机审批、附件和后台推送尚未接通，界面明确标注。

## 打开和构建

在 Android Studio 打开本 `android/` 目录，选择安装的 JBR/JDK 25（或满足 Gradle 要求的 JDK），SDK 路径使用本机 `ANDROID_HOME`。本轮使用 Studio 2026.2.1、AGP 9.2.1、Gradle 9.4.1、Kotlin 2.2.10、Compose BOM 2025.08.01；compile SDK 37.0 / build-tools 36.0.0，target 35，最低 Android 8.0 / API 26。实际模拟器为 API 35，其他 Android 版本未作设备验证。

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

输出 `app/build/outputs/apk/debug/app-debug.apk`。本轮可安装副本在仓库 `.local/distribution/device-bridge-0.1.0-debug.apk`，是自用调试签名，非商店正式版本。不要分发 AndroidTest APK，其可包含本地生成的测试凭据。

Wrapper 固定官方 Gradle 分发 SHA-256。网络受限时可以使用相同分发的镜像，但必须校验 Wrapper 中的完整哈希。本轮下载缓存、Android 用户数据和签名文件均在 `.local/`，没有修改全局代理或 IDE 配置。后续升级 APK 应保持同一签名，避免卸载后丢失本机待核对记录。

## 用实体手机连接

v0.1 主机仍只监听 127.0.0.1。可以通过 USB 的 adb reverse 连接，模拟器使用 10.0.2.2。没有开放防火墙或局域网端口，也没有公网中继。

先在电脑仓库根目录，沿用已明确授权的本地策略，生成新的手机身份：

```powershell
python -m tools.prepare_android_host --output .local/android-phone --port 8765
python -m prototype.host .local/android-phone/host.json
```

第一条只生成私有配置、30 天证书和连接文件，不启动监听、不改原策略。第二条在当前终端运行主机；保持终端和桌面应用在线。默认授权目标来自 `.local/desktop-target-policy.json` 的 `codex` 条目，当前为专用测试会话，仅允许固定文本。

手机启用 USB 调试并在系统提示中确认电脑后，在另一个终端使用 SDK 的 adb；将 `<serial>` 换成 `adb devices` 中明确选定的手机：

```powershell
adb -s <serial> install -r .local/distribution/device-bridge-0.1.0-debug.apk
adb -s <serial> reverse tcp:8765 tcp:8765
adb -s <serial> push .local/android-phone/device-bridge.connection.json /sdcard/Download/device-bridge.connection.json
```

打开 APP → 设备 → 导入档案，选择该 JSON，再连接设备。连接文件含客户端私钥材料，只应通过自己的可信电脑交付，不上传聊天、网盘或仓库。APP 导入后加密保存；Downloads 中的源文件仍需由用户自行保管或移除。

USB 断开或设备重启后可能需要重新执行 reverse。证书到期需重新生成身份并在电脑处理旧身份撤销；当前没有自动轮换和真实配对协议。

## 普通文本模式

首次验证建议保留固定消息模式。电脑端明确允许普通对话时，停止旧主机，再生成独立配置：

```powershell
python -m tools.prepare_android_host --output .local/android-phone-chat --port 8765 --free-text
python -m prototype.host .local/android-phone-chat/host.json
```

重新导入这个新身份。该选项只在派生主机策略中明确设定 `allowFreeText: true`；没有发送授权的设备仍不能发送。它允许文本触发原 Codex 行为，执行范围、模型及审批继承桌面当前会话。手机尚不能处理审批，需在桌面处理。真实 Android 测试保持固定模式，没有向既有工作会话写入或放宽全局权限。

主机目录只列配置且已授权的会话，并非桌面全部项目/会话列表。访问其他原会话需先在电脑端明确配置其真实身份与范围；不能用 cwd 分组代替项目归属。

## 恢复和保护

- TLS 使用独立客户端证书、标准证书链/主机名校验与精确叶证书 SHA-256 固定，验证后才写应用数据；不忽略证书错误。
- Android Keystore 保存 AES 加密密钥。连接资料和操作记录用 AES-GCM、文件名认证和原子文件保存，位于 noBackupFilesDir；禁用备份和设备迁移。
- 发送先保存操作 ID。网络结果不确定保存 unknown，重建应用只读核对；不会自动换 ID 重发。损坏记录保留并报错，不作为空列表继续发送。
- 设备身份、电脑证书和端点共同隔离缓存。切换配置失败时不保留旧身份的显示数据，演示数据也不冒充真实连接。
- 前台只读同步按失败退避；后台停止轮询，没有前台常驻服务或推送。已加载旧页不会被前台刷新立即清掉。
- completed 表示投递确认，模型生成状态单独显示。撤销阻止后续读取和写入，不能收回已经交付的数据或取消已交接任务。
- 主机 stdin 的本地 `{"action":"revoke","device":"导出工具返回的设备身份"}` 可撤销设备；`{"action":"shutdown"}` 停止主机。

## 验证

本轮已实际生成/安装 APK、执行 Android Lint，并在 API 35 模拟器验证加密/损坏保护、真实 mTLS 消息、丢确认核对、身份显示、TLS 拒绝和撤销。详见[结果报告](../docs/results/2026-10-28-android-v01.md)。

普通自动回归不会连接真实会话：

```powershell
python -m unittest discover -s tests -q
node --test desktop/adapter.test.mjs desktop/transport.test.mjs desktop/coordination.test.mjs desktop/observation.test.mjs desktop/history.test.mjs
```

AndroidTest 的真实测试 assets 仅由本地验证准备，并受 `.local/android-app-live.claim.json` 保护，不进源码或主 APK。已有记录时禁止删除声明、清空日志、换身份/操作 ID 重跑未知发送。后续回归应选择只读测试类；不能把带真实测试凭据的 AndroidTest APK 当可分发安装包。
