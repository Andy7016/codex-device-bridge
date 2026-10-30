# Android HTTP CONNECT 验证范围

日期：2026-10-30。用户已确认实体手机错误为显式HTTP代理不受支持；实现该已配置通路。

- 仅修改Android WSS外层承载，HTTP代理来自系统ProxySelector；不改设备身份、云端登记、操作审批、内部TLS或恢复规则。
- CONNECT只发送目标authority和Host，无中继Bearer、手机证书、Cookie或代理密码。支持无认证HTTP代理；407失败关闭，无认证重试、重定向或直连回退。
- CONNECT响应限8KiB、64行、严格CRLF；代理建立、头读取和外层TLS共享8秒绝对截止。取消应关闭尚在连接的原始/SSL socket。
- 测试只在项目API35模拟器及127.0.0.1合成中继运行；代理固定转发同一回环夹具端口，不能指定外部目标。每轮最多100连接、8MiB、15分钟，单个畸形响应最多16KiB。
- 覆盖200成功、407/403/3xx、超大/畸形响应、响应慢滴、取消、TLS主机名/证书拒绝、无凭据泄漏；通过代理重跑真实Keystore、丢ACK核对、取消和实际403提示。
- 代理选择仅在测试进程内临时替换，并在finally恢复；不修改系统/VPN/浏览器代理。保留用户运行中的Companion，不连接原会话或发送真实消息。
- 关闭本轮服务、映射和专用模拟器，撤销新夹具设备；交付主APK无测试资产且签名与前版一致。公网实体手机接入需安装后由用户确认，不以本地结果代替。

依据：[RFC9110 CONNECT](https://www.rfc-editor.org/rfc/rfc9110.html#name-connect)、[Java-WebSocket1.6.0客户端源码](https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/client/WebSocketClient.java)。Context7未提供匹配的Java-WebSocket项目，使用固定版本上游源码核对SocketFactory优先级及TLS包装行为。
