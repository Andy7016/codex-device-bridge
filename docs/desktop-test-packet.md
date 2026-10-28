# 桌面原运行时写入验证包

状态：用户已明确授权两条专用测试会话；真实消息、取消与单次允许测试已执行。结果见[实测报告](results/2026-10-28-desktop-runtime-control.md)。禁止向既有工作会话发送此验证包；禁止删除尝试记录后重跑。

## 测试会话

- Codex：`Device Bridge Codex 测试`，创建于已授权工作区项目，仅执行固定打印动作，不启用 worktree、不指定或改变默认模型。
- ChatGPT：`Device Bridge ChatGPT 测试`，通过桌面支持的本地 Chat 入口创建；不以云端 Work 会话冒充本地 Chat。

## 往返消息

首条：`这是设备连接测试会话。请仅回复 DEVICE_BRIDGE_READY_V1。不要使用工具，不要操作文件，不要创建其他会话。`

桥接发送：`DEVICE_BRIDGE_HELLO_V1。请仅回复 DEVICE_BRIDGE_ACK_V1。不要使用工具或操作文件。`

ChatGPT 路由诊断使用有区分的 `DEVICE_BRIDGE_CHATGPT_PROBE_V2` 前缀，要求同一 ACK；独立 Codex follower 使用 `DEVICE_BRIDGE_COORDINATION_V1`，要求 `DEVICE_BRIDGE_COORDINATION_ACK_V1`。三者结果分别核对，未用换 ID 自动重放未知发送。

验收：桌面同一个会话 ID 中出现该用户消息与回复；记录请求接受、任务终态与消息文本校验结果。首次发送结果未知时不自动重发。

## 审批测试

先确认入口能够读取、绑定并拒绝真实测试审批；不通过扩大全局权限、关闭安全机制或伪造运行时上下文制造通过结果。

允许的候选动作仅为在测试会话中打印固定合成标记，不包含网络、删除、真实凭据或用户文档写入。是否触发审批以现有有效策略为准；无法安全触发时记录未执行。审批必须绑定测试会话、轮次、请求 ID、具体动作及最新状态。

App Tools 目录没有审批决定操作，该适配路径报告 unsupported。固定版本的本地协调入口已单独实现具名命令审批操作，并绑定真实 owner/请求/动作；不提供任意 RPC 透传。测试会话局部收紧为 untrusted/user，保留原沙箱约束；实际拒绝采用运行时提供的 cancel，单次允许只接受人工核查过的完整动作摘要，禁止持久规则和 session 授权。
