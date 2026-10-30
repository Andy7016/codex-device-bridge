# Android v0.3.2 HTTP CONNECT

基线b03de67。用户报告v0.3.1显示显式HTTP代理不受支持；同一手机浏览器健康检查可达，VPN覆盖全部应用。该提示来自APP的主动拒绝分支。

## 实现与边界

- Android系统ProxySelector选择HTTP代理时，使用固定目标SocketFactory建立CONNECT。只向代理发送目标authority及Host，不发送中继Bearer、Cookie、手机证书或Proxy-Authorization。
- 代理连接后，以原SSLContext、逻辑中继域名和端口包装TLS，启用TLS1.2/1.3与HTTPS主机名校验。外层TLS完成验证后才将已连接SSLSocket交给Java-WebSocket1.6.0，随后发送原WSS鉴权。
- 200以外响应失败关闭；407有专用提示。没有代理认证重试、重定向或失败后的中继直连回退，不改系统代理/VPN配置。仅支持无需登录认证的普通HTTP代理。
- CONNECT响应总上限8KiB、64个头，逐字节读到CRLFCRLF，拒绝裸换行、折行、无效头名及控制字符。目标authority单独校验，避免注入。未把代理任意文本显示到UI。
- 代理连接、响应和TLS共享8秒绝对截止；取消登记原始与SSL Socket，在锁外先关闭原始Socket。平台DNS解析本身无法强制终止，返回后再次检查截止/取消，防止迟到连接；中继域名由代理解析，本机仅解析代理地址。
- 原有直连/SOCKS选择、内层TLS与证书固定、请求写入边界、unknown核对、取消和不重发规则保持原样。无需重配Companion或云端登记。

## 实际验证

环境为项目专用Android API35模拟器、合成回环代理、Node适配器与真实本地workerd。测试前范围见http-connect-validation-packet.md；没有公网攻击测试或真实会话操作。

- 共17项不同Android用例通过：代理边界7、错误分类4、外层TLS3、真实WSS/Keystore/恢复3。
- 代理边界覆盖407/403/302、畸形/折行/超大响应、目标注入、目标不作本地DNS解析、伪造明文TLS、慢滴与CONNECT后TLS合计8秒截止、工厂返回前取消。确认CONNECT不含鉴权等敏感请求头，没有跟随Location或另行重试。
- 外层TLS测试确认有效隧道保留中继逻辑身份，错误主机名及不受信任证书被拒绝。
- 真实WSS三项分别经HTTP CONNECT接入Node和本地Cloudflare通过：AndroidKeystore双向TLS、丢ACK后按原ID核对、原身份连接迁移及错误内层主机名/指纹拒绝；实际403在业务写入前准确报告；取消后不启动第二路由。重复运行不累计为不同用例。
- 两个后端实验均记录runtimeSends=1、droppedAck=true、tlsFailure=null；结束后enabledDevices=0。所有新服务、反向映射及专用模拟器已停止，用户正在运行的Companion保持原状。
- 只读独立审查未发现具体阻断问题；库对SocketFactory的调用顺序由固定版本上游源码和实际成功转发共同验证，不作为完整安全审计声明。
- Android构建通过，Lint为0错误/4警告。产品APK无assets，版本Code5/Name0.3.2，DEX包含新工厂及错误码。签名v2验证通过，证书与v0.3.1一致，可覆盖安装保留密钥和操作记录。默认与普通用户文件视图的三个关键源文件哈希一致。

## 交付与未验证项

文件：device-bridge-0.3.2-debug.apk，11346897字节。
SHA256：20fad2a68457c79de2c6da4e799460ffc86b579130bbadaad7abb33b940b7764。

本轮没有改动公网Cloudflare代码、登记或Companion。未使用实体手机验证其实际代理；用户覆盖安装后仍需重新扫码确认。代理认证、HTTPS代理、更多Android版本、运营商/VPN组合不在已验证范围。APK仍为调试签名验证包。

依据：[RFC9110 CONNECT](https://www.rfc-editor.org/rfc/rfc9110.html#name-connect)、[固定版本客户端源码](https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/client/WebSocketClient.java)。
