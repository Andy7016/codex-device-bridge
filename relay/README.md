# Device Bridge WSS 中继 v0.3.0

Cloudflare Workers + SQLite Durable Objects 与 Render Node Web Service 共用 `protocol.mjs`。客户端协议、ES256路由票据和内部mTLS相同；两种云服务的生命周期适配器分别为 `cloudflare.mjs`、`render.mjs`。

本中继不执行桌面RPC、不持有内部TLS私钥、不持久化对话载荷。正式接入前仍需验证用户网络下的HTTPS、WebSocket和断线恢复。免费托管不提供本项目所需的端到端可用性承诺。

## 协议与安全边界

- `GET /`、`GET /healthz`：公开的静态版本与协议信息，HTTP200。不返回电脑列表或授权信息。
- `GET /v1/relay/<64hex hostId>/host|client`：WSS升级，必须提供 `Sec-WebSocket-Protocol: device-bridge.v1` 与 `Authorization: Bearer ...`。拒绝浏览器Origin、查询参数、非固定路径和未登记主机。
- 电脑使用本地DPAPI中的256位注册凭据；云端只配置其SHA256。手机使用电脑P-256签名的固定ES256/JWT，绑定电脑、invite或手机证书指纹、受众、时间和随机标识。仅允许ES256，校验签名公钥SPKI与hostId一致。
- 路由票据是有限的接入能力，不是桌面授权。邀请票据在邀请到期后最多600秒失效，实际配对仍受原120秒邀请及本机确认规则约束。设备票据最长30天，日常认证响应续期；手机最多每小时持久化一次。离线超期后，可通过局域网认证续期，或扫描电脑为原设备生成的连接更新二维码，不必更换手机密钥。
- 电脑每条已认证host hello同步已撤销设备。电脑端撤销立即生效，云端同步通常在新host通道建立时发生（等待槽上限45秒，网络故障时可更久）。云端路由拒绝不能代替电脑端mTLS与每次操作/结果释放的授权检查。
- 每主机最多2条电脑通道、4条手机通道、8条总通道，最多120次新连接/分钟，登记最多16台电脑。hello5秒、等待/活动通道各45秒绝对截止，每条通道双向合计1MiB，二进制消息最大16KiB。Node发送积压超过64KiB关闭；不支持bufferedAmount的运行时仍受1MiB生命周期预算约束。
- hello是唯一允许的入站文本消息；配对后只接受二进制TLS字节，不广播、不接受任意转发目标。关闭任一端同时结束其配对通道；重连必须新通道、新内部TLS，禁止自动重发已尝试的业务操作。
- DO只持久化固定大小的撤销/连接计数元数据；连接绑定放attachment以供休眠重建。Node使用单实例内存状态，重启后由电脑hello恢复撤销信息。保持单实例；本版没有跨实例协调。
- 云服务可观察IP、路由身份、时间和流量大小，也可阻断通信。云平台API权限只用于部署，不进入手机或电脑连接资料。

## Cloudflare 部署

使用Node22及以上，在本目录执行：

```powershell
npm ci
npx wrangler login
npm run deploy:cloudflare
```

`wrangler.jsonc`对应Worker名称`device-bridge-relay`、绑定`RELAYS`及SQLite类`DeviceRelay`。上传到用户已有的同名Worker前，先确认账号和名称；这会替换Hello World代码。网页登录由用户完成，不把API Token粘贴到聊天或配置源码中。

未配置登记时仅健康检查可用，中继升级失败关闭。初次仅验证可达性，可以把Secret `RELAY_REGISTRATIONS_JSON`设为`{}`。需要连接电脑时：

1. 在新版Companion打开原`host.json`，在设置里填写`wss://你的Worker域名`，选择系统证书信任。
2. 点击“导出WSS中继登记”，得到`relay-registration-wss.json`。其中只有电脑公钥和注册凭据哈希；不要上传`private.dpapi`、原始注册凭据、会话策略或恢复数据库。
3. 在Worker的Settings → Variables and Secrets添加Secret：名称`RELAY_REGISTRATIONS_JSON`，值为导出文件的完整JSON；保存并部署。
4. 或在本目录执行`Get-Content -Raw '实际导出文件路径' | npx wrangler secret put RELAY_REGISTRATIONS_JSON`。多个电脑的登记需合并为一个JSON对象，总计最多16项。
5. 健康检查地址`https://你的Worker域名/healthz`。手机新配对用普通配对二维码；已配对手机选中原设备后用“连接更新二维码”。

保持Workers Free进行初期验证，超过免费额度将拒绝相应操作。付费订阅、预算告警和CPU限制不等于账号总账单硬上限。不要把Workers级“Protect with Cloudflare Access”应用到本WSS入口：该策略目前会使WebSocket升级失败；若另加Access，需要专门设计hostname级接入及客户端认证。

本项目验证命令使用进程级环境变量把Wrangler缓存/日志限定在`.local`，不修改用户全局配置：

这些目录仅用于无账号的本地验证。账号登录和正式部署请另开终端，使用Wrangler默认的用户凭据目录；不要把OAuth凭据放进项目。

```powershell
$env:XDG_CONFIG_HOME = Join-Path (Resolve-Path '..') '.local/cloudflare-config'
$env:WRANGLER_LOG_PATH = Join-Path (Resolve-Path '..') '.local/cloudflare-logs'
$env:WRANGLER_SEND_METRICS = 'false'
npm run check:cloudflare
```

## Render 手动部署

1. 登录[Render Dashboard](https://dashboard.render.com/)，New → Web Service。
2. 连接GitHub，只授权私有仓库`vvcchh0/codex-device-bridge`即可，仓库保持private。
3. 填写：

| 字段 | 值 |
| --- | --- |
| Name | `device-bridge-relay`，如被占用可更名 |
| Branch | `main` |
| Region | `Singapore` |
| Runtime / Language | `Node` |
| Root Directory | `relay` |
| Build Command | `npm ci --omit=dev` |
| Start Command | `npm start` |
| Instance Type | `Free` |
| Health Check Path | `/healthz` |

4. 添加环境变量`NODE_VERSION=22.22.0`、`RELAY_BIND=0.0.0.0`、`RELAY_REGISTRATIONS_JSON={}`。不要自行设置`PORT`，平台会分配。
5. 点击Deploy Web Service。获得`https://实际名称.onrender.com`后，分别在电脑、手机Wi-Fi/移动网络、平板上开关代理测试`/healthz`，应返回版本JSON。
6. 接入设备时，在Environment把`RELAY_REGISTRATIONS_JSON`替换为Companion导出文件的完整JSON，选择Save and deploy。Companion中继地址填`wss://实际名称.onrender.com`，使用系统证书信任。
7. 新手机正常扫码配对；原手机扫描给原设备签发的连接更新二维码。更换后端时不更改设备身份、授权或操作ID。

也可通过仓库根目录的`render.yaml`创建Blueprint；仍需用户填写登记环境变量并确认使用Free。Render自动终止公网外层TLS，Node服务接收平台内部HTTP；自行托管Node时必须配置受信任TLS或可信TLS前置代理，不要将明文WS当作公网客户端入口。

免费实例15分钟无入站HTTP/WS流量会休眠，冷启动约1分钟；部署、平台重启会断线，出站带宽及构建有额度。只把它用于本轮可达性与协议验证，决定长期托管前测量重连与实际用量。

## 验证与已知范围

`npm test`是共享协议/Node适配器测试，`npm run check:cloudflare`是部署dry-run。Python `tests/test_wss_relay.py`用同一测试切换Node或本地workerd；`WSS_TEST_BACKEND=cloudflare`选择后者。它们使用临时回环端口和合成身份。

Android使用Java-WebSocket1.6.0的帧/累计消息上限、独立SSLEngine、指定Keystore身份与精确电脑证书固定。默认网络变化时中止旧承载并恢复只读核对，取消后禁止换路重试。Android 0.3.2起支持无需认证的显式HTTP CONNECT代理，隧道之后继续验证外层TLS；需要代理认证、重定向和畸形响应均失败关闭。SOCKS可按系统ProxySelector选择，VpnService/TUN由系统路由处理。后台推送、真实VPN组合、更多Android版本及公网持续运行仍需后续验证。

参考：[Cloudflare Hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)、[Workers Access与WebSocket限制](https://developers.cloudflare.com/workers/configuration/cloudflare-access/)、[Render Node部署](https://render.com/docs/deploy-node-express-app)、[Render环境变量](https://render.com/docs/configure-environment-variables)、[Render免费实例](https://render.com/docs/free)。
