# 桌面原运行时适配实验

本目录包含可运行的 Windows 本地适配代码。2026-10-28 已在两条用户授权的专用测试会话完成真实消息往返，并在 Codex 原运行时完成真实命令审批取消与单次允许。详细证据和限制见[实测报告](../docs/results/2026-10-28-desktop-runtime-control.md)。

当前是固定安装版本的内部接口实验。独立 Codex send/inspect 已接入[本机恢复主机](../prototype/DESKTOP.md)，有 loopback mTLS 监听，没有远端监听或 Android 客户端。运行前需具备同一用户对桌面管道的访问权限；不要扩大 ACL 或管理员常驻。

## 两条适配路径

| 模块 | 能力 | 前提 |
| --- | --- | --- |
| `adapter.mjs` + `transport.mjs` | 通过桌面 App Tools 读取 Codex/ChatGPT 历史、列出桌面项目、发固定测试消息 | 桌面任务继承的管道地址，以及真实、有效、仍在执行的发起任务上下文 |
| `coordination.mjs` | 连接既有协调路由，发现 Codex 原 owner、读取快照、发固定测试消息、处理限定命令审批 | 现有桌面路由和原 owner 在线；本地可信策略授权目标和操作，无需活动代理任务上下文 |
| `activation.mjs` | 发送前写入并 fsync 操作标记，未知结果不自动重发 | 同一持久目录和稳定操作 ID；不能删除标记后重试 |

协调客户端注册自己的 `codex-device-bridge` 身份，仅作为 follower；不会启动路由、认领会话所有权或启动第二个 Codex 运行时。方法、版本和消息结构由代码固定，调用方不能选择任意 RPC 或传入任意 turn context。

App Tools 的 ChatGPT 目标参数必须省略 Codex 的 `hostId` 路由提示。外层真实发起任务上下文依然包含其实际 host。当前不能据此认定 ChatGPT 已支持无人值守后台接入。

## 自动测试

仓库根目录，Node.js 22，无第三方依赖：

```powershell
node --test desktop/adapter.test.mjs desktop/transport.test.mjs desktop/coordination.test.mjs desktop/observation.test.mjs
```

36 项 Node 测试只连接自建 loopback 假路由或测试 transport，不连接真实桌面、不发送模型消息。包括跨会话授权、错误 owner/版本、过期上下文、动作变更、并发票据、丢包不重试、禁止持久规则，以及恢复关联证据和最后交接检查。

## 本地受限策略

真实配置和捕获只放忽略的 `.local/`，下例全部是占位值。手机不能上传或替换这个策略文件，也不能自报 caller context。

```json
{
  "allowProjectCatalog": false,
  "targets": {
    "codex": {
      "threadId": "synthetic-codex-placeholder",
      "hostId": "local",
      "kind": "codex",
      "rights": ["read", "send", "approval-decline"],
      "allowedMessages": ["synthetic test message"]
    },
    "chatgpt": {
      "threadId": "synthetic-chatgpt-placeholder",
      "hostId": "local",
      "kind": "chatgpt",
      "rights": ["read"],
      "allowedMessages": []
    }
  }
}
```

每个目标分别授予 `read`、`send`、`approval-decline`、`approval-accept`；允许审批还需主机预置的 `allowedActionDigests` 精确匹配。`test-approval-policy` 只对显式 `testFixture: true` 生效，只能把该会话收紧为 `untrusted/user`。它不是通用权限设置接口。

此实验仅允许精确白名单文本。后续开放自由输入时，必须结合设备身份、会话范围、运行时权限与审计重新设计授权；文本本身能够触发工具操作。

App Tools 的 `.local/desktop-caller-context.json` 使用真实发起任务的 `callerSource`、`hostId`、`threadId`、`turnId` 和短期 `expiresAt`。上下文来自实际桌面任务，不能编造 ID 或将过期值改成未来时间。适配器会匹配继承的任务 ID，并在发送前读取确认该轮仍为 `inProgress`。这些本地检查不是面向不可信客户端的身份认证协议。

## 使用与结果核对

`tools/desktop_adapter.mjs` 提供 `capabilities`、`read <label>`、`projects`、`send <label> <operationId>`。send 只选本地策略中该目标第一条已授权文本。运行真实发送前需已获得该测试会话的明确授权并具备有效上下文。结果正文保存在 `.local/desktop-last-result.json`，终端只输出脱敏元数据。

独立 Codex 调用使用 `CoordinationClient.open({policy})`，然后调用 `snapshot(label)` 或 `sendText(label, exactAllowedText, stableOperationId, activationBarrier)`；关闭时调用 `close()`。快照可能包含正文、路径和工具输出，应按敏感数据处理。

审批流程是 `commandApprovals(label)` 获取短期不透明票据，再调用 `decline(label, ticket)` 或 `acceptOnce(label, ticket)`。处理前重新拉取原 owner 的权威快照，核对完整请求摘要。票据仅在当前连接内有效 60 秒，同一请求的重复票据并发提交只发送一次。实际可选项提供 `decline` 时用它；只提供 `cancel` 时使用取消。允许操作固定为字符串 `accept`，不生成 session 授权或规则修订。

`decisionSubmitted` 仅代表 owner 接受了提交。还需核对请求消失、轮次终态、命令状态和实际输出，才能判定取消/执行成功。真实测试脚本 `tools/desktop_approval_test.mjs` 与 `tools/desktop_accept_test.mjs` 不属于自动测试；它们使用固定操作 ID、私有审批材料和一次性备份，禁止为重跑删除保护记录。允许脚本只接受提前人工核查过的完整动作摘要，不能仅因命令含有标记字符串就执行。

## 恢复与安全边界

- 发送前持久记录尝试；已尝试、冲突或损坏的操作记录均拒绝再次发送。确认丢失时先核对原历史，不能换新 ID 绕过未知结果。
- 审批已尝试状态目前保存在单个本地连接内。发送已整合持久恢复和撤销；审批的跨进程、多控制端原子决策与持久日志仍未整合，不宣称全局 exactly-once。
- 本地文件 fsync 不构成断电、磁盘损坏或备份回滚后的完整事务保证。实验权限文件也未部署 Windows ACL 管理、签名或加密存储。
- 消息缓冲有 8 MiB 上限、请求超时和待处理数限制；不兼容的状态版本不能作为审批证据。更新补丁会使审批缓存失效，重新取完整快照。
- 固定端点和 owner ID 校验不能抵御已经控制同一 Windows 用户的进程。后续需要主机账户隔离、安装权限和本地 IPC 身份防护；不能将本地管道直接转发到手机或公网。
- 本版固定验证桌面 `26.1023.6123.0`、CLI `0.165.0-alpha.1.2`。尚未实现安装版本自动门禁；桌面升级后必须暂停真实写入、重新验证，不能自动回退到更宽泛接口。
