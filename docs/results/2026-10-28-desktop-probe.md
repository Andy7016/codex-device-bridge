# Windows 桌面会话接入实测

日期：2026-10-28。结论：只读部分能力已验证，完整原会话接管门槛未通过。

后续进展：同日已找到桌面原运行时内部入口并完成授权测试会话的真实消息与审批验证，见[后续报告](2026-10-28-desktop-runtime-control.md)。本文保留初次独立 app-server 探测的历史结果。

## 环境与方法

- 桌面包版本：26.1023.6123.0。
- 同安装配套 CLI：codex-cli 0.165.0-alpha.1.2。
- Schema：安装版本通过 `app-server generate-json-schema --experimental` 导出。
- 完整 schema bundle SHA-256：`8C2A3ADEBB3E0DA97F9B1EFAF686F8D14F9969436879ADE25A4B1B208EC65CD7`。
- 工具：`tools/probe_app_server.py`。仅允许 initialize、历史/元数据、loaded/list、project/list/read、model/list；不调用 resume、turn/start、审批、账户登录或配置写入。
- 真实目标 ID 仅在本地执行参数使用，不入库。原始 schema 与脱敏 JSON 结果保存在忽略目录 `.local/`。
- 应用内工具能列出并读取已存在的 Codex / ChatGPT 会话。本轮选取当前 Codex 会话及一条闲置 ChatGPT 会话作只读核对；未向这些会话发送消息。

## 实测结果

| 能力/路径 | 观察 | 结论 |
| --- | --- | --- |
| 官方 `app-server proxy` 连接现有控制端点 | 子进程退出，报告 control socket 不可用；默认控制目录和 socket 均不存在 | 当前配置下此默认入口不可接入；未证明所有其他端点均不存在 |
| 桌面运行进程 | 观察到桌面持有 app-server 子进程，启动选项未指定 `--listen` | 与默认 stdio 路径一致；没有接管该进程的 stdin 或修改启动配置 |
| 新启 stdio app-server 初始化 | 成功，探针结束后该子进程正常退出 | 独立服务可用，不能视为已接入桌面原运行时 |
| 新服务 loaded/list | 成功，0 条 | 新运行时无已加载会话 |
| Codex 原会话 thread/read | 原 ID 匹配，status=`notLoaded` | 已验证按原 ID 读取存储元数据 |
| Codex 原会话 turns/list | 返回 1 条历史轮次及 userMessage 类型 | 已验证有限历史读取；未验证完整历史、附件与全部工具事件 |
| ChatGPT 原会话 read / turns/list | 均返回 -32600，脱敏消息 `thread not loaded: <id>` | 此路径当前无法读取该会话；原因不能仅由此推断为产品永久不支持 |
| 模型列表 | 返回 8 个可见模型条目 | 列表能力已验证；模型设置生效尚未验证 |
| 项目列表 | 返回 25 条，有 1 条 root 匹配指定桌面工作目录 | 能读取 CLI 项目数据，未证明与桌面项目语义完全一致 |
| 桌面项目 ID -> project/read | -32602，`project not found: <id>`；列表中无该 ID | 桌面 ID 无法直接作为此服务的项目 ID，存在映射/命名空间差异 |
| 应用内 ChatGPT 读取工具 | 成功返回一轮摘要 | 应用自己能访问；该内部工具不能据此当作独立 Android 客户端的接口 |

## 未执行和保护项

- 未恢复、发送、停止或审批任何原会话；未更改模型、权限或项目归属。
- 未创建新的用户可见聊天，未使用真实会话进行攻击测试。
- 未修改桌面数据库、认证文件、全局配置，也未启动/停止共享守护进程或公开网络监听。
- 未验证 Android、蜂窝网络、代理共存、后台推送或真正的远端中继。
- 应用内工具与独立客户端访问权限不同，不能用前者证明后者已可用。

## 下一步决策

完整客户端需要继续确认受支持的桌面会话控制入口，或与用户明确调整首版范围。不能把复制历史、另开运行时或以目录分组替代原需求。当前不应承诺 ChatGPT 原会话、活动任务和审批已可控制。

可独立推进恢复层的隔离原型：只接 synthetic mock adapter，使用本机 loopback 与测试证书，验证事件补齐、幂等、未知结果窗口、授权撤销与审批竞态。此原型不能解除桌面接入门槛。

官方依据：[App Server](https://learn.chatgpt.com/docs/app-server)、[Remote connections](https://learn.chatgpt.com/docs/remote-connections)。上述项目方法来自安装版本 schema；公开文档与本机实验接口覆盖并不完全一致。
