# Codex Device Bridge

面向 Android 的设备会话客户端：通过独立连接通路，访问指定 Windows 电脑上已有的 ChatGPT 与 Codex 会话。

## 项目状态

2026-10-28：需求与验证计划已建立。尚无可运行客户端、连接代理或中继，也尚未开展接口探测、连接实测和攻击测试。

优先验证桌面上同一条会话的接入能力，再实施连接与恢复原型，最后开发 Android 界面。单独启动一个 Codex 实例不能作为已接入当前桌面会话的证明。

## 已确认方向

- Android 原生会话界面 + 电脑端连接代理 + 独立中继。
- 访问当前桌面应用内的 ChatGPT 与 Codex 会话，包括历史、对话、项目、新建、模型与推理强度、审批、附件。
- 手机和电脑优先主动建立出站连接，研究与现有代理共存、断线恢复和后台通知。
- 聚焦结构化会话操作；屏幕传输、鼠标键盘控制和通用远程桌面不在首版范围。
- 电脑端保留执行环境和 OpenAI 凭据；移动设备拥有的操作能力由电脑端限制。
- 连接和恢复验证包括授权范围内的攻击测试，使用隔离环境、测试身份与合成数据。

## 文档

- [需求和验收边界](docs/requirements.md)
- [架构与待验证决策](docs/architecture.md)
- [桌面接口验证](docs/desktop-capability-validation.md)
- [连接、恢复与攻击测试计划](docs/validation-plan.md)

## 首个里程碑

分别验证一条已有 Codex 会话和一条已有 ChatGPT 会话：是否能读取历史、发消息、接收活动任务事件、处理审批，并保留项目归属与执行上下文。根据证据决定适配路线，不提前承诺完整接口覆盖。

此仓库计划保持 GitHub private。远端可见性由 GitHub 设置决定，忽略规则不提供访问控制。

## 官方依据

以下文档于 2026-10-28 查阅；实现时需重新核对本机版本和对应 schema。

- [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [Remote connections](https://learn.chatgpt.com/docs/remote-connections)
- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android VPN](https://developer.android.com/develop/connectivity/vpn)

本项目独立开发，与 OpenAI 无官方隶属关系。
