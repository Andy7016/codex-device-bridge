# 0.3.8 后端实现与审阅入口

## 组件分工

| 层 | 实现 | 责任 |
| --- | --- | --- |
| Windows 本机窗口 | `companion/app.py`, `startup.py`, `configuration.py` | 初始化、导入策略、中继设置、配对确认、撤销；无网络管理接口 |
| 加密连接主机 | `companion/host.py`, `wire.py`, `websocket_transport.py` | 主动出站、内部 TLS、证书身份、严格 JSONL 和结果授权 |
| 业务/恢复层 | `prototype/desktop.py`, `journal.py`, `lease.py` | 双层授权、不可变关联、持久状态、去重、核对与撤销串行 |
| 历史读取 | `prototype/stored_history.py` | 私有 stdio app-server，只允许初始化及 `thread/turns/list`，不恢复或写入会话 |
| 原运行时控制 | `desktop/runtime_worker.mjs`, `coordination.mjs` | 受限 follower 发现并调用已有 owner，发送/检查；隐藏子进程及 Job Object 约束 |
| 共享中继 | `relay/protocol.mjs` | 登记/票据鉴权、固定路由、撤销元数据、连接及流量预算、密文字节转发 |
| 云适配器 | `relay/cloudflare.mjs`, `render.mjs` | Durable Object 或 Node 单实例的生命周期适配 |

新启动的只读 app-server 读取原会话存储，并不接管原 owner。发送仍依赖原桌面内部协调入口。旧 App Tools 路径需要真实有效的活动任务上下文，目前只用于接入研究，不提供 ChatGPT 常驻手机入口。

官方 [Codex App Server 文档](https://learn.chatgpt.com/docs/app-server)在 2026-10-30 核对：存储历史分页可独立于恢复会话。官方接口与本仓库的七个网络方法属于不同协议；不要将完整 app-server 或本地管道暴露到公网。

桌面控制实验曾使用桌面 `26.1023.6123.0` / CLI `0.165.0-alpha.1.2`；0.3.8 历史与静态问题记录检查使用桌面 `26.1027.3384.0` / CLI `0.166.0-alpha.5.1`。这些记录不能替代新版本下的真实发送验收。尚无自动版本门禁。

## 本地策略

`host.json` 引用一个策略文件；文件名不必是 `policy.json`。以下仅为合成示例，真实 ID 需由本机用户明确选择并导入，当前清单不会自动包含未来新建会话：

```json
{
  "targets": {
    "review-test": {
      "kind": "codex",
      "hostId": "local",
      "threadId": "REPLACE_WITH_AUTHORIZED_LOCAL_THREAD_ID",
      "displayName": "专用交互测试",
      "rights": ["read", "send"],
      "allowedMessages": ["仅回复 DEVICE_BRIDGE_ACK，不调用工具。"],
      "allowFreeText": false
    }
  }
}
```

最多 100 个明确目标，无通配授权。`allowFreeText: true` 允许该目标接受非空且 UTF-8 不超过 4096 字节的文本；设备仍必须具有 `send` grant。当前 UI 的发送选框只用于新配对确认，修改策略不会替已有手机追加 grant。更新已有授权需要受信任的本地管理过程，当前窗口没有独立的“编辑已配对设备权限”功能。

策略中即使出现 `approve`，当前网络服务也没有审批方法。不得把 synthetic journal 的审批测试方法当作可用产品 API。发送文本可能触发原会话工具；原模型环境和审批策略继续决定其执行权限。

## 数据与密钥

电脑身份目录包含配置、DPAPI 私有材料、证书、SQLite 恢复日志及授权；同一 Windows 用户是本机信任边界。当前没有抵御已控制该用户进程的隔离能力，也没有备份回滚保护。手机私钥位于 Android Keystore，档案、outbox 与历史缓存使用应用保险箱加密并禁用备份；缓存不存授权或投递证据。

中继登记是 `{hostId: {publicKeyJwk, tokenSha256}}`，最多 16 台电脑。电脑使用原始注册凭据，手机使用电脑签发的 ES256 路由票据；云端持有公钥和哈希，不持有内部 TLS 私钥。路由身份、IP、时刻和流量大小对云平台可见；默认应用日志不记录正文，平台日志仍由部署者管理。

## 两种可移植后端

Cloudflare 使用 `wrangler.jsonc`，绑定 `RELAYS` 和 SQLite Durable Object `DeviceRelay`；DO 保存有限撤销/计数元数据，连接使用 attachment 支持休眠恢复。Render 使用 `render.mjs` 和 `ws`，状态位于单实例内存；不支持将 Node 服务直接扩展为多个未协调实例。

双方使用 `device-bridge.v1`、相同 hello 和二进制内部 TLS 流。客户端更换路由时不改变密钥、设备授权或 operation ID。各平台可能冷启动、回收或限额，部署者需测量目标网络的 WSS 可达性和费用；本项目不提供公共服务保证。完整操作见 [relay/README.md](../../relay/README.md)。

## 构建与验证

Android 使用 Gradle wrapper（9.4.1）、AGP 9.2.1、Kotlin 2.2.10，compile SDK 37.0 / target 35 / min 26。本次构建使用 Android Studio JBR 25。在 `android/` 执行 `gradlew.bat :app:assembleDebug :app:lintDebug`，输出 `app/build/outputs/apk/debug/app-debug.apk`。不得以重命名文件代替核对 Manifest 版本。

Windows 源码运行与打包：

```powershell
python -m venv .local/companion-venv
.local/companion-venv/Scripts/python.exe -m pip install -r companion/requirements.txt
.local/companion-venv/Scripts/python.exe -m tools.prepare_companion --output .local/my-computer
.local/companion-venv/Scripts/python.exe -m companion.app --config .local/my-computer/host.json
```

打包另需 PyInstaller；0.3.8 实测 Python 3.13 / PyInstaller 6.16.0。使用 `tools/build_companion.ps1` 与 `python -m tools.package_companion --version 0.3.8`，先检查已有输出目录。含 `devicebridge-data` 的已运行目录受保护，不能覆盖构建或重新打包。旧 `tools/package_release.py` 固定为 0.2.0，是历史工具，不用于本次发行。

普通回归命令与有副作用的 live probe 边界见 [Agent 指南](../../agent/README.MD)。0.3.8 已有 76 项相关 Python 回归、最终 29 项定向检查、Android 构建/Lint（0 错误、4 警告）、4 项 API 35 设备测试，以及 Windows 离线自测证据。它们未覆盖所有真实手机/VPN组合，也未证明手机实时作答或审批可用。

## Review 优先级

优先核对：手机证书与设备绑定、host/grant 双层鉴权、撤销与最后交接/结果释放竞争、丢确认后禁止重放、游标与内容 hash 绑定、同名证书精确匹配、HTTP CONNECT 后的外层证书验证、所有缓冲和子进程上限。完整问题清单见 [SECURITY.md](../../SECURITY.md)。
