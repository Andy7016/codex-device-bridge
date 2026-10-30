# Device Bridge 0.3.8 API

此文档对应 `companion/host.py`、`prototype/desktop.py`、`companion/pairing.py` 和 `relay/protocol.mjs`。本协议是受限业务 API，不是 OpenAI 官方 API 或任意 app-server RPC 转发器。

## 版本与传输

| 对象 | 当前版本 |
| --- | --- |
| Android / Companion | 0.3.8；Android versionCode 8 |
| 中继包及 `/healthz` | 0.3.0 |
| WSS 子协议 | `device-bridge.v1` |
| WSS 邀请二维码 | schemaVersion 2；非 WSS 旧邀请为 1 |
| 配对后的 WSS 手机档案 | schemaVersion 3；兼容旧档案 1/2 |

内层 TLS 上每连接一次 UTF-8 JSONL 请求，以换行结束。拒绝额外字段、重复 JSON 键、NaN/Infinity。请求和配对响应含换行最多 8192 字节；认证后的业务响应最多 65536 字节。

```json
{"method":"catalog","params":{}}
```

成功为 `{"ok":true,"result":...}`，失败为 `{"ok":false,"error":"safe_code"}`。已认证 WSS 响应可在 envelope 顶层附 `relayTicket` 供续期；配对成功的票据在 `result.relayTicket`。TLS/超时/EOF 也可能直接断连。手机身份来自 TLS 证书，不能从请求指定。

## 已认证业务方法

所有表中字段均为必填，只有注明的 `ifRevision` 可省略；`null` 必须按字段语义显式传入。`thread` 是本机策略别名，不是任意原会话 ID。

| method | params | result |
| --- | --- | --- |
| `catalog` | `{}` | `{sessions,capabilities}` |
| `history` | `{thread,before,limit}`，可加 `ifRevision` | 历史页或未变化页 |
| `message` | `{thread,cursor}` | `{id,text,nextPart,totalBytes,contentHash}` |
| `submit` | `{thread,id,payload:{text}}` | 操作对象 |
| `status` | `{id}` | 操作对象 |
| `reconcile` | `{id}` | 操作对象，可附 `reconciliation` |
| `sync` | `{thread,cursor}` | `{resetRequired,cursor,events}`，必要时 `snapshot` |

### catalog

`sessions` 中各项为 `label,title,sessionKey,kind,canSend,allowedMessages,freeText`。`sessionKey` 是固定目标 hash，用于缓存隔离；不能拿它选择任意目标。`canSend` 同时检查主机策略和手机 grant，`freeText` 还要求主机 `allowFreeText=true`。

```json
{
  "ok": true,
  "result": {
    "sessions": [{"label":"review-test","title":"专用交互测试","sessionKey":"<64hex>","kind":"codex","canSend":false,"allowedMessages":[],"freeText":false}],
    "capabilities": {"chatgpt":false,"approvals":false,"settings":false,"files":false}
  }
}
```

### history / message

`before:null` 请求最新页；否则传回原 `nextBefore`，不解析或改写游标。`limit` 是 1..8 的整数；Android 当前取 6。`before` 和 `message.cursor` 非空时为 1..128 字符。`ifRevision` 为之前返回的 SHA-256 revision。

历史页含 `messages,nextBefore,hasMore,runtimeStatus,historyComplete,source,sessionKey,revision,notModified`。消息含显示用的 id、角色和文本等投影字段；长消息另外返回 `nextPart,totalBytes,contentHash`。`notModified:true` 时保留已有缓存，不把省略的消息列表当成空历史。

预览约 2400 UTF-8 字节；后续片段最多约 24000 原始字节，并受 JSON 转义后 32000 字节字符串预算约束。下一片用 `message` 请求，直到 `nextPart:null`。游标绑定目标、类型、消息和内容 hash，主机内存最多 512 项、30 分钟有效；重启后失效。内容修改、过期或类型不匹配须重新加载。不得跨 hash 拼接。

历史只投影用户/助手文本和问题记录；`historyComplete` 不能解释为所有工具输出/附件均已传输。只读存储路径可返回 `runtimeStatus:"unknown"`；不能用该页决定发送就绪或审批有效性。

### submit / status / reconcile / sync

```json
{"method":"submit","params":{"thread":"review-test","id":"review-operation-001","payload":{"text":"仅回复 DEVICE_BRIDGE_ACK，不调用工具。"}}}
```

`id` 为 1..128 字符，`text` 非空且 UTF-8 ≤4096 字节；文本须为主机白名单项，或该目标启用了自由输入。权限检查覆盖已认证设备、会话、host 策略和最后交接。操作返回 `{device,id,state,result}`；`state` 为 `accepted/dispatching/unknown/completed/cancelled`。`completed` 是消息交付证据，模型状态另见 `result.turnStatus`。

同一设备、ID 和内容去重；换内容产生 `conflict`。发生可能写入后的断线，保留该 ID，先 `status`/`reconcile`。核对可返回 `reconciliation: observed/not_observed/unavailable`；后两者都不授权重发。

`sync.cursor` 为桥接日志整数序号，保留窗口外返回 `resetRequired` 和快照；这是操作日志，区别于 `history` 的正文游标。未来客户端不能混用两者。

## 配对 API

只有以下三个方法允许在尚未授权手机证书的、电脑已认证的内部 TLS 上使用：

| method | 精确 params | 成功 result |
| --- | --- | --- |
| `pair.begin` | `{inviteId,inviteSecret,requestId,phoneName,clientCertificatePem}` | `{state:"challenge",challenge,confirmationCode,expiresAt}` |
| `pair.prove` | `{inviteId,inviteSecret,requestId,signature}` | `{state:"pending"或"approved",confirmationCode,expiresAt}`；WSS approved 另有 `relayTicket` |
| `pair.status` | 同 `pair.prove` | 同上；WSS approved 另有 `relayTicket` |

`requestId` 为 UUID，`phoneName` 1..80 字符且无控制符。手机证书为自签名 P-256/SHA-256、当前有效、最多 8192 字节。一个邀请只绑定一个精确请求和证书；同 begin 可恢复，不同绑定拒绝。

签名输入为 UTF-8，无末尾换行：

```text
device-bridge-pair-v1\n<inviteId>\n<requestId>\n<hostId>\n<challenge>\n<clientCertificateSha256>
```

其中 `\n` 表示换行字节；`signature` 是该输入的 DER ECDSA/SHA-256 签名，使用标准 Base64。六位码来自证明字节 SHA-256 取模 1000000 并补零。拒绝配对返回 `error:"denied"`，当前实现不会成功返回 `state:"denied"`。

邀请 120 秒有效，电脑本地确认授权 `read/send`。重启使未批准邀请失效；已批准结果可在邀请到期后额外 600 秒恢复，且仍检查设备未撤销。手机须在证明前持久化原请求材料，重试不能新建身份绕过绑定。

二维码文本为 `dbpair:` + 无填充 base64url 编码的 UTF-8 JSON，解码后最多 8192 字节。WSS 邀请精确根字段为 `schemaVersion,displayName,hostId,serverName,serverCertificatePem,serverCertificateSha256,relay,directEndpoints,inviteId,inviteSecret,expiresAt`。`schemaVersion=2`；`relay` 含 `host,port,certificatePem,transport,path,ticket`，其中 `transport="wss"`、`path="/v1/relay"`。`hostId` 为电脑公钥SPKI的SHA-256，`serverCertificateSha256` 为完整DER证书SHA-256，两者不可混淆；`serverName` 为 `device.<hostId前32位>.<后32位>.bridge.invalid`。邀请秘密、路由票据及二维码按私有材料管理。

配对后的 WSS 档案采用 schema 3，含原电脑身份/证书、`clientKeyAlias`、`clientCertificateSha256` 和候选路由；手机私钥留在Keystore，不进入档案。Android当前恢复配对时复用 `pair.prove`；`pair.status` 供仅查询已证明状态使用。

## WSS 中继 API

`GET /` 和 `GET /healthz` 是公开静态健康信息，不返回设备列表。升级入口是：

```text
wss://relay.example/v1/relay/<64hex-hostId>/host
wss://relay.example/v1/relay/<64hex-hostId>/client
Sec-WebSocket-Protocol: device-bridge.v1
Authorization: Bearer <host-registration-token-or-signed-client-ticket>
```

拒绝查询参数、Origin、任意路径和未登记身份。电脑 Bearer 校验登记 SHA-256；手机票据仅接受固定 ES256/JWT，并核对主机公钥、受众、时效及撤销。票据只授予路由能力，业务仍需内部 TLS 和本机授权。

客户端发送一条文本 hello：`{version:2,hostId,role}`；host 另带 `revoked`。之后仅转发二进制 TLS，匹配完成接收 `{"ready":true}` 加换行；协议细节以共享源码为准。

预算：每主机最多 2 个 host、4 个 client、8 个总通道；新连接最多 120 次/分钟；hello 5 秒、等待/活动通道各 45 秒绝对截止；单二进制帧/消息预算 16 KiB、通道双向生命周期合计 1 MiB。Node 发送积压超过 64 KiB 会关闭；云运行时的背压能力另有区别。超限或服务重启会断开，恢复不能重放业务操作。

## 错误及不可用能力

主机安全错误包括 `denied`、`conflict`、`history_changed`、`desktop_unavailable`、`desktop_history_too_large`、`invalid_request`、`internal_error`。它们不包含上游错误正文或凭据。不同错误应分别展示、重新读取或核对，不能统一转为重新发送。

没有网络 `approve`、模型/推理/权限设置、文件上传、任意命令或原始 RPC 方法。当前 scope 只支持 `kind=codex,hostId=local`。历史问题字段不是实时待答表单；synthetic loopback 的同名实验方法不构成公开能力。
