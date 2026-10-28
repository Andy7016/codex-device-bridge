# 桌面原运行时受限控制实测

日期：2026-10-28。本轮已验证桌面原 Codex 与 ChatGPT 测试会话的真实消息往返，并实现 Codex 原运行时命令审批的取消和单次允许。独立 Codex follower 不需要活动代理任务上下文；ChatGPT 的后台常驻接入仍有前提限制。

同日后续已将独立 Codex 发送接入恢复日志与本机 mTLS，见[贯通报告](2026-10-28-integrated-recovery.md)。本文保留前一阶段的范围与结果。

本报告延续[此前只读探测](2026-10-28-desktop-probe.md)。此前新建 stdio app-server 的限制仍成立，本轮使用的是正在运行的桌面应用自身入口。

## 授权、环境与证据

- 用户明确允许创建 `Device Bridge Codex 测试`、`Device Bridge ChatGPT 测试` 两条专用会话，发送固定合成文本，测试无害打印动作的审批。
- 桌面包：`26.1023.6123.0`；配套 CLI：`0.165.0-alpha.1.2`；Node.js：`22.22.0`；Python：`3.11.2`。
- Codex 测试会话由桌面原生创建工具创建在用户指定工作区所属项目；ChatGPT 测试会话经桌面 ChatGPT 入口创建，用户就绪后完成 UI 操作。随后控制测试使用本地管道。
- 对已安装资源仅作静态只读检查，结合原生工具目录和实际报文确认协议。未修改安装文件、认证材料、全局配置或桌面数据库，未劫持 app-server stdin。
- 真实会话 ID、caller context、原始响应、审批动作和本机资源副本仅放忽略的 `.local/`。仓库保存新写的适配代码、合成夹具和脱敏结论，不保存厂商程序副本或真实个人内容。

## 可复用入口

### Desktop App Tools

桌面任务继承的 `CODEX_APP_TOOLS_PIPE_PATH` 指向 Windows 命名管道，使用 uint32 little-endian 长度帧和 JSON-RPC 2.0。实际 `tools/list` 返回 58 个工具，包含桌面 `codex_app` 的历史、项目和发消息能力，没有审批决定工具。

适配器仅提供三个具名业务调用，限定本地主机、目标会话和授权文本；不向调用者暴露原始工具目录的任意调用能力。请求使用真实发起任务的身份字段，发送前验证该轮仍在执行。ChatGPT 目标的工具参数必须省略 Codex 专用 `hostId`，否则会错误路由到 Codex。

此入口证明可复用桌面工具执行能力，但依赖合法活动任务上下文。当前没有证明无任务时也能长期独立调用 ChatGPT；不能伪造 caller ID 作为后台守护方案。

### Codex owner/follower 协调入口

客户端连接既有本地协调管道，注册自己的 `codex-device-bridge` 身份，发现原会话 owner 并作为 follower 获取权威快照。没有创建路由、认领 owner 或启动另一个模型运行时。

固定方法涵盖 owner discovery、完整历史加载、用户文本 turn start、测试会话审批策略收紧和命令审批决定。外部输入不能选择任意方法、模型、系统指令或 turn context。状态广播固定校验 owner、会话、host 和协议版本 11；其他消息不作为审批证据。

独立发送实测进程删除了 `CODEX_THREAD_ID` 和 `CODEX_APP_TOOLS_PIPE_PATH`，只凭既有协调入口和主机本地目标授权发送。原测试会话随后产生精确回复 `DEVICE_BRIDGE_COORDINATION_ACK_V1`，轮次为 completed。这证明已摆脱发起代理任务上下文的 Codex 基本发送前提；尚未验证桌面升级或重启后的稳定契约。

## 实测结果

| 能力 | 真实观察 | 结论与范围 |
| --- | --- | --- |
| Codex 历史 | 子进程通过 App Tools 读取同一原 ID、kind 和合成轮次；协调快照也包含同一会话 | VERIFIED：已测历史、快照和工具事件；附件未测 |
| ChatGPT 历史 | 子进程通过 App Tools 读取原 ChatGPT 会话，kind 和 ID 匹配 | VERIFIED：已测有限轮次；完整分页和附件未测 |
| 桌面项目 | 原生工具返回 22 个桌面项目，指定工作区项目 ID 与桌面创建目标一致 | VERIFIED：目录与该目标身份；全部成员关系未测 |
| Codex 发消息 | App Tools 往返成功；独立 follower 发消息后同一会话产生精确 ACK，任务 completed | VERIFIED：原运行时两条路径均通过 |
| ChatGPT 发消息 | 修正目标路由后，同一原会话出现固定合成消息与精确 `DEVICE_BRIDGE_ACK_V1` | VERIFIED：有效活动任务上下文内；无人值守待解 |
| 活动 Codex 状态 | 等待真实审批时快照返回待处理请求；决策后请求移除并观察到终态 | VERIFIED：快照恢复与终态；完整增量流未实现 |
| 命令审批取消 | 原请求提供 accept/规则修订/cancel，没有 decline；发送 cancel 后任务 interrupted，命令 declined，无打印输出 | VERIFIED：取消阻止该测试命令执行 |
| 命令审批单次允许 | 人工检查完整命令、目录和权限字段后预置动作摘要；发送字面 accept；命令 completed，输出精确为固定标记 | VERIFIED：只允许该请求，没有 session 授权或持久规则 |
| 测试会话权限 | 仅把新 Codex 测试会话收紧为 untrusted/user，其他设置不在更新字段中 | VERIFIED：局部收紧；通用权限编辑未实现 |

审批测试命令仅为 `python -c "print('DEVICE_BRIDGE_APPROVAL_V1')"`，运行时带有其原生 PowerShell 包装。允许摘要覆盖实际完整命令、cwd、环境、命令动作及额外权限/网络/规则修订字段，避免只匹配标记字符串就放行。

两条测试会话保留。Codex 测试会话保留较严格的 untrusted/user 设置，原设置只在 `.local/` 备份。未修改既有工作会话；模型与推理强度未改。

## 失败与恢复处理

首次 ChatGPT 发送使用了 Codex host 提示，入口报错。该操作保留尝试记录并按未知结果处理，没有自动重放；核对原历史和空闲状态后，修正路由并使用另一条有区分标记的诊断文本验证。

首次审批探针预期 decline，但实际只提供 cancel，因此失败关闭并保留待处理审批。检查实际可选项后实现安全取消，再核对命令 declined。该失败与后续成功分别保留私有记录，未将失败报告覆盖成通过。

所有真实发送均先落盘操作尝试；同一操作 ID 不能再次发送，冲突和损坏记录同样拒绝。发送确认不能替代实际 ACK。审批提交确认不能替代命令状态和输出验证。

## 自动验证与防护

本轮运行以下测试，全部通过：

```powershell
node --test desktop/adapter.test.mjs desktop/transport.test.mjs desktop/coordination.test.mjs
python -m unittest discover -s tests -v
```

- 新增 29 项 Node 测试：跨目标/权限拒绝、真实上下文约束、帧边界、错误响应身份与 owner、协议版本、动作变更、请求消失、丢失回包、并发重复票据、精确动作单次允许及禁止持久规则等。
- 既有 21 项 Python 恢复/有界攻击测试继续通过：真实 loopback mTLS、进程崩溃、重复提交、撤销、伪造身份和游标、审批竞态等。该层仍连接合成 adapter，尚未与本轮桌面真实适配层串接。
- 自动攻击用例只连接项目自建夹具；未针对生产 OpenAI、ToDesk 或其他设备开展攻击测试。

本地策略只允许精确白名单文本；审批票据绑定 owner、原请求摘要、动作和短有效期，决策前重新读取快照，连接内并发/重复提交受限。不支持任意 RPC、任意路径、session 授权或规则修订。该层尚不能单独承担不可信远端的身份认证。

## 剩余工程门槛

1. 两条入口属于当前安装版本内部实现，没有公开稳定外部 API 保证。尚无安装版本自动门禁；升级需停止真实写入并重新验证。官方 app-server 文档不能作为这些内部入口的支持承诺。
2. ChatGPT 常驻后台通路、完整实时消息、停止/补充任务、模型和推理强度修改、附件与通用新建仍待验证或实现。Codex 独立 follower 的成功不能填补 ChatGPT 的缺口。
3. 持久恢复日志、多进程单写入约束、设备撤销和真实 adapter 尚未接成一条链。审批跨控制端原子性、断电和备份回滚均未完成验证；不宣称外部动作 exactly-once。
4. Android、端到端加密中继、代理共存、蜂窝/后台通知、远端重连尚未实现。本轮没有新增公开监听或改动 ToDesk。
5. 同一 Windows 用户被控制后的 IPC 冒充、策略文件篡改和历史泄露仍需主机隔离与部署防护，不能把管道直接映射为网络服务。

后续可先把独立 Codex follower 接到已有恢复日志，使用仍受限的合成消息贯通连接、快照恢复与撤销；ChatGPT 继续验证合法常驻入口。当前证据支持继续推进受限原型，尚不足以发布覆盖全部原需求的移动客户端。

官方资料：[App Server](https://learn.chatgpt.com/docs/app-server)、[Developer commands](https://learn.chatgpt.com/docs/developer-commands)。2026-10-28 通过官方文档 MCP 查阅；内部管道结论来自本机安装资源与实际测试。Node 管道实现依据 [Node.js net 文档](https://nodejs.org/docs/latest-v22.x/api/net.html)。
