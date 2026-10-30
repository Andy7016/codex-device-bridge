# 托管中继验证版 v0.3

新增Cloudflare Workers + SQLite Durable Objects与Render Web Service的共用WSS传输方案。客户端保留端到端mTLS、稳定设备身份及原操作恢复；更换入口支持给原设备签发连接更新二维码。部署与Render逐步操作见[relay/README.md](relay/README.md)。

该版先完成自有回环与模拟器验证；公网部署、真实运营商和VPN切换结果需独立记录。

以下保留先前版本的实现与操作说明。

# Codex Device Bridge

面向 Android 的设备会话客户端：通过独立连接通路，访问指定 Windows 电脑上已有的 ChatGPT 与 Codex 会话。

## 项目状态

2026-10-29：Companion 0.2.1 增加离线首次启动向导，可在没有host.json、中继或策略时生成本机身份并自动载入；支持后续补填连接设置和导入策略，保留原身份及恢复日志。

2026-10-29：Android v0.2 已实现扫码/图片配对、手机 Keystore 身份、电脑端确认与设备撤销、双向 TLS 中继通路及可选局域网候选连接。API 35 模拟器已完成合成会话的实际加密中继发送与丢确认核对。提供 Windows 便携程序和中继源码/容器部署文件；尚无公网中继，也未完成实体手机与 VPN 切换验证。原 Codex 适配器沿用 v0.1，ChatGPT 合法常驻入口、手机审批和完整项目管理仍待接入。

桌面入口属于当前安装版本内部协议，需按版本重新验证；不能视为已获得稳定公开 API。

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
- [桌面接入实测结果](docs/results/2026-10-28-desktop-probe.md)
- [原运行时真实消息与审批结果](docs/results/2026-10-28-desktop-runtime-control.md)
- [桌面适配代码与运行边界](desktop/README.md)
- [专用会话测试包](docs/desktop-test-packet.md)
- [真实 Codex 恢复主机运行方式](prototype/DESKTOP.md)
- [原运行时与恢复层贯通结果](docs/results/2026-10-28-integrated-recovery.md)
- [ChatGPT 常驻入口调查](docs/results/2026-10-28-chatgpt-background-entry.md)
- [Android APP 构建与连接](android/README.md)
- [电脑端与中继部署](companion/README.md)
- [Companion 离线首次启动验证](docs/results/2026-10-29-companion-startup-v021.md)
- [扫码配对与中继协议](docs/pairing-v2-contract.md)
- [Android v0.2 配对与中继验证](docs/results/2026-10-29-pairing-relay-v02.md)
- [Android v0.1 验证结果](docs/results/2026-10-28-android-v01.md)
- [本地恢复实验原型与运行方式](prototype/README.md)
- [21 项测试结果及覆盖边界](docs/results/2026-10-28-recovery-lab.md)

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
