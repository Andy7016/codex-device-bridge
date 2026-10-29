# Device Bridge 电脑端与中继 v0.2

扫码绑定电脑公钥身份，手机自行生成 AndroidKeystore 私钥；电脑与手机主动连接中继，内部再建立 TLS。已配对设备和授权保存于电脑恢复日志，同一身份换 IP 或切换路由无需重新配对。当前版本为自用原型：尚未部署公网服务，也未完成实体手机与真实 VPN 组合验证。

## 电脑端

本机生成的便携目录为 `.local/distribution/DeviceBridge-Companion/`，运行其中的 `DeviceBridge-Companion.exe`。保留整个目录，双击后选择预先准备的 `host.json`。这是普通窗口程序，关闭窗口会停止服务；尚无系统托盘、安装器、开机启动或更新服务。程序未作发布者代码签名。真实 Codex 会话操作还需要电脑上可用的 Node.js 和原桌面应用。

首次准备身份（开发环境）：

```powershell
python -m venv .local/companion-venv
.local/companion-venv/Scripts/python.exe -m pip install -r companion/requirements.txt
.local/companion-venv/Scripts/python.exe -m tools.prepare_companion --output .local/my-computer --policy .local/desktop-target-policy.json --relay-host relay.example.com
.local/companion-venv/Scripts/python.exe -m companion.app --config .local/my-computer/host.json
```

`relay.example.com` 是占位域名，必须换成实际部署的可达中继。策略文件沿用明确授权的原会话，当前已有本地策略仍为专用测试范围；新程序不会自动扩大到全部项目和聊天。准备命令仅创建新目录，不启动监听、不修改全局配置、防火墙、代理或原桌面数据库。

私有目录包含 DPAPI 加密的电脑私钥/中继注册凭据、公开证书、恢复日志与授权记录。目录丢失或 Windows 用户凭据不可用时，无法靠设备名称恢复原身份。不要通过重新生成同名目录解决身份读取失败。手机密钥由手机生成，不从电脑导出。

电脑窗口操作：生成二维码 → 手机扫码 → 核对两端六位数字 → 选择允许读取的会话及可选发送权 → 确认。默认只读；发送仍受原会话策略和本机目标策略限制。撤销会阻止后续请求并关闭已识别的连接，不能收回已经交付的数据或取消已交接任务。

## 中继部署输入

需要一个两端均可访问的服务器地址与 TCP 端口（默认443）、与地址匹配的 TLS 证书和私钥。实际公网部署应在指定服务器后单独执行；本轮只运行隔离的回环测试。

暂无服务器时，可先准备按月试用的小型 Linux VPS，以1个 vCPU、1GiB内存、固定公网 IPv4作为起测配置；这是开发起点，尚未做负载基准。无需GPU、托管数据库或对象存储。优先验证电脑、手机在各自 VPN 开关及移动数据环境下的可达性，再考虑长租。可以暂不买域名：为固定公网IP生成含该IP SAN的专用自签名证书，通过 `--relay-certificate` 随二维码交付并固定校验。域名及系统信任证书可后续再规划，不能省略身份校验。

准备命令生成的 `relay-registration.json` 是 `{hostId: SHA256(注册凭据)}`。把登记项合并进中继的私有配置 `registrations`，无需把电脑私钥或原桌面凭据交给中继。示意配置如下，路径和身份均需替换：

```json
{
  "certificate": "/srv/device-bridge/tls/fullchain.pem",
  "privateKey": "/srv/device-bridge/tls/key.pem",
  "registrations": {"64位电脑身份": "64位注册凭据哈希"}
}
```

```sh
python -m companion.relay --config /srv/device-bridge/relay.json --bind 0.0.0.0 --port 443 --allow-public --service
```

中继默认为回环监听；上面是部署命令示例，不代表已经执行。`--service` 等待 SIGINT/SIGTERM，适合守护进程；省略此选项时输入 `shutdown` 或关闭标准输入会停止。当前中继使用普通 TCP 上的 TLS，不是 HTTPS/WebSocket，不能直接挂在仅支持 HTTP 的反向代理或 CDN 后；需要 TCP 转发能力。受限网络允许网页访问也未必允许此协议，需实测。

提供 `companion/Dockerfile.relay` 与只允许必要源码的 `.dockerignore`，以便后续部署；容器构建和服务器部署尚未实测。配置和证书应从 `/run/device-bridge/` 只读挂载，并允许容器 UID 10001 读取。不要把它们 COPY 到镜像。生产构建还需固定基础镜像摘要。

每个连接只转发一次内部 TLS/RPC，随后关闭。上限32个外部连接、每个电脑2个待匹配连接、待匹配45秒、活动通道45秒、每通道双向合计1MiB。原型适用于小规模自用，不具备完整的公网滥用防护、集群路由、后台推送和高可用运维能力。中继能够观察连接元数据并阻断流量，不能解密内部会话。

自签名实验中继可用 `--relay-certificate <PEM>` 将证书作为限定信任锚随二维码交付，同时仍检查主机名及叶证书指纹；生产默认使用系统信任及有效域名证书。不得关闭证书校验排查连接问题。

## 可选局域网通路

准备时明确添加 `--direct-host <电脑的私有IP> --direct-port 8765` 才启用该地址上的监听，不修改防火墙。二维码中的地址只是候选入口，手机始终用电脑逻辑名称及固定证书验证身份。直连不可用时尝试中继；应用请求开始写入后不会换路重发。IPv4 RFC1918、回环和 IPv6 ULA 为预期范围，其他可路由地址不应使用。

VPN 由用户现有网络策略管理。本程序不创建 Android VpnService，不自动绕过 VPN，不修改代理或断网保护规则。公网中继不可达时保留配对与未知操作，恢复后核对原操作。

## 构建与测试

Windows 打包：安装 `pyinstaller==6.16.0` 后运行 `tools/build_companion.ps1`，输出位于 `.local/distribution/`。打包仅收集程序模块和桌面适配器，不收集私有策略、证书、日志或会话数据库。

```powershell
.local/companion-venv/Scripts/python.exe -m unittest discover -s tests -q
node --test desktop/adapter.test.mjs desktop/transport.test.mjs desktop/coordination.test.mjs desktop/observation.test.mjs desktop/history.test.mjs
```

配对测试使用 Windows DPAPI，因此需要当前 Windows 用户上下文。新的测试边界和协议见 [pairing-v2-contract.md](../docs/pairing-v2-contract.md)。TLS证书当前有效期为一年；自动轮换尚未实现，临近到期需实施有验证的续期/迁移，不能忽略到期错误。
