# Android 0.3.8 — experimental pre-release

Android 客户端通过扫码绑定 Windows Companion 的稳定身份，以自建 WSS 中继跨网连接，可选局域网直连。设备身份、配对和待核对操作独立于 IP、VPN 及中继入口。

本版重点：长回复先显示开头，点击“展开全文”继续加载；同 ID 内容变化时按 hash 重新加载；历史问题与选项可见；历史页在 Android Keystore/AES-GCM 保险箱缓存，刷新通过 revision 减少传输。沿用受限文本发送、同 operation ID 核对及可能送达后禁止自动重发。

## 安装与配套

- 包名 `io.github.vvcchh0.devicebridge`；Manifest 为 **0.3.8 / versionCode 8**；min API 26、target 35、compile 37.0。
- 此次发布已验证的 **debug APK**：可调试，使用 Android Debug 证书；不是生产签名版本。与既有0.3.x调试包签名相同，可覆盖安装并保留身份。签名SHA-256：`aaf7b346297b1dd8d0e560016d9bbc8058c70818d0747ca66ceb664e414951ff`。
- 需要 Companion 0.3.8 和自己的可达中继。先按[使用说明](../../README.md)配置，再扫码核对六位码。连接不要求共享 IP；实际网络仍须允许 App 使用对应路由。
- 支持无需认证的系统HTTP CONNECT代理，仍验证外层中继证书及内层电脑证书。

## 尚未完成

手机实时作答、审批、模型/推理设置、附件、完整项目管理和 ChatGPT 常驻接入尚未完成。历史问题展示不是实时表单；主机/设备授权同时允许才可发送。后台推送、生产签名更新和更广泛手机/VPN组合仍待实现或验证。

## 已有验证

Android 主包/测试包及 Lint 构建通过，0错误/4警告；API35自有模拟器4项设备检查通过，覆盖加密缓存隔离/损坏恢复、全文展开回调、未配对禁用及未知操作只读核对。相关Python/Windows证据见[0.3.8报告](../results/2026-10-30-full-text-v038.md)。本次公开未重新编译主包，也未宣称完成真实手机发送/审批验收。

APK：`device-bridge-0.3.8-debug.apk`，11476423字节，SHA-256 `a2338eee0bc193d997977de84757a51aad9aa691e233ada4b14d136852d1716f`。附构建清单、校验文件和第三方许可证。

## Developer review

从[工作流](../review/WORKFLOWS.md)、[后端实现](../review/BACKEND.md)、[API](../review/API.md)和[Agent README](../../agent/README.MD)进入。重点看首次写入边界、网络切换后的unknown、缓存/sessionKey隔离、片段hash与HTTP CONNECT证书校验。运行时代码来源 `cc9c65e`，发行标签另包含公开文档修订。
