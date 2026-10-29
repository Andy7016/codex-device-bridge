# Device Bridge 中继部署包 v0.2

这是独立的TLS字节中继，不运行模型、不保存对话正文，也不持有电脑/手机内部TLS私钥。当前仅经过Windows回环测试，Linux容器与公网部署尚未执行。

建议先按月试用小型Linux VPS，以1个vCPU、1GiB内存和固定公网IPv4起测。无需GPU或数据库。先验证两端的运营商、VPN开关和长时间恢复，再决定长租。这个配置是试用起点，尚无容量基准。

域名可以后补。固定公网IP使用含该IP SAN的专用证书，并在电脑身份准备时通过 `--relay-certificate` 固定它；手机扫码后也会保存相同信任。不要将证书校验设为忽略。证书到期/更换需要有验证的更新流程，当前尚无自动轮换。

## 配置

电脑身份准备工具生成 `{hostId: SHA256(注册凭据)}` 的 `relay-registration.json`。把登记项合并到服务器私有配置，不要把电脑的 `private.dpapi`、恢复数据库或原桌面凭据上传服务器。

```json
{
  "certificate": "/run/device-bridge/relay.pem",
  "privateKey": "/run/device-bridge/relay.key",
  "registrations": {"64位电脑身份": "64位注册凭据哈希"}
}
```

证书和私钥需自行准备且与连接的域名/IP匹配。配置示意值不能原样运行。私钥、配置均应限制为服务用户可读，作为只读文件挂载，不放入镜像或源码仓库。

## 直接运行

Python3.11及以上，中继的所需模块只有标准库：

```sh
python -m companion.relay --config /run/device-bridge/relay.json --bind 0.0.0.0 --port 8443 --allow-public --service
```

`--service` 等待SIGINT/SIGTERM。生产环境使用服务管理器启动，限制服务用户权限，并根据选定端口配置网络入口。公网开放应在选定服务器后另行核实；本包不会自行修改防火墙。

## Docker准备

```sh
docker build -f companion/Dockerfile.relay -t device-bridge-relay:0.2.0 .
docker run --rm --name device-bridge-relay -p 443:8443 -v /srv/device-bridge:/run/device-bridge:ro device-bridge-relay:0.2.0
```

这两条是部署示例，尚未实测。容器使用UID/GID10001，挂载目录中配置、证书及私钥应允许该用户读取。生产还需固定基础镜像摘要、设置重启策略、日志保留及资源限制。

协议为普通TCP上的TLS，不能直接放在仅支持HTTP的代理/CDN后。当前32个连接、每电脑2个等待槽、等待/活动通道45秒、每通道双向合计1MiB；适用于小规模文本验证，没有完整公网滥用防护、集群与高可用能力。中继可观察连接元数据并阻断流量，无法解密手机与电脑之间的内部TLS。
