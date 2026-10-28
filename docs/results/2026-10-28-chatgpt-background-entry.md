# ChatGPT 合法常驻入口调查

2026-10-28，桌面 `26.1023.6123.0`。已验证活动任务上下文中的原 ChatGPT 历史和消息，尚未找到并验证可供独立桥接服务合法常驻的入口。该缺口不影响 Codex 恢复层贯通。

| 路径 | 当前证据 | 判断 |
| --- | --- | --- |
| 官方 Remote connections | 文档说明访问同一主机的项目、原会话、权限与工具，提供账户/工作区验证和设备配对 | 官方产品通路；当前文档未给本项目可复用的第三方常驻控制协议 |
| Local computer access with Work Cloud | 开启同步后创建的新任务适用，既有任务保留原模式 | 无法直接满足桌面既有 ChatGPT 原会话接入 |
| 新启 Codex app-server | 此前读取 ChatGPT 返回 thread not loaded | 不能作为已接入原运行时的证明 |
| Codex 协调路由 | 本轮以诚实独立客户端对原 ChatGPT 测试 ID 只读 owner discovery，返回 no-client-found | 此时没有可发现 owner，不能推广为永久无任何入口 |
| Desktop App Tools | 原会话读写已成功；schema 必需 callerSource/threadId/turnId，调用链包含任务读取和 inProgress 取消检查 | 任务工具通路；保留真实活动上下文前提，不用伪造或失效 ID 构建后台服务 |
| 渲染层服务 | 静态检查发现 window-ready、renderer dispatch 和 callerSource 限制；所查 canCallTool 拒绝 callerSource=chatgpt | 未发现独立 ChatGPT 控制客户端注册契约；未注入或绕过 |

callerSource=chatgpt 指调用方身份，并非 ChatGPT 目标不可操作。此前使用合法 Codex 发起任务控制 ChatGPT 测试目标已成功，两者语义不同。

本轮通过官方文档 MCP 搜索并读取 Remote connections、Developer commands，只读检查安装包相关管道、调度和服务注册代码。必要资源副本仅放忽略目录，未提交厂商程序。探针 `tools/probe_chatgpt_control.mjs` 只对授权目标作 owner discovery。

未追加 ChatGPT 消息、编造 caller/turn ID、改变全局配置、导出认证材料、调用私有云端写接口或注入进程，未改变现有 Remote/ToDesk 配置。

后续优先寻找有明确身份、授权与生命周期契约的桌面扩展/本地控制能力，或官方第三方 host 协议。候选入口需先证明能绑定原测试会话、无活动代理任务时运行，并保留权限和可撤销性，再做真实写入。

当前没有 ChatGPT 伪常驻模式。保持长期代理任务、重放旧上下文或复制登录凭据没有解决合法常驻契约。UI 交互可以作为需协调桌面使用的补充，本轮未验证其后台稳定性。

来源：[Remote connections](https://learn.chatgpt.com/docs/remote-connections)、[Developer commands](https://learn.chatgpt.com/docs/developer-commands)。查阅日期 2026-10-28；内部入口结论限定本机版本和已查路径。
