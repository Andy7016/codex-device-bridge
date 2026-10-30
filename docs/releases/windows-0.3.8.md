# Windows Companion 0.3.8 — experimental pre-release

Windows Companion 为 Android 提供受控会话通路：离线初始化设备身份、扫码配对、本机选择会话范围、出站 WSS 中继、可选局域网、持久操作日志和撤销。电脑保留原会话及执行环境，中继仅路由内部TLS流量。

本版支持长文本的有界按需续读与问题历史投影，沿用分页历史、隐藏读取worker、同名手机证书精确匹配，以及单次发送/unknown核对的恢复层。只读历史使用私有stdio app-server；真实发送使用已有原owner/follower，二者分别验收。

## 安装与升级

- Windows x64便携包，**未作发布者代码签名**。完整解压目录后运行 `DeviceBridge-Companion.exe`。
- 首次启动可在没有host.json、策略或可达中继时创建身份；尚无托盘、系统服务、自启动或更新器，关闭窗口即停止服务。
- 真实Codex接入需要原桌面环境、同一Windows用户及Node.js 22+。内部桌面协议升级后需要重新验证。
- 升级先正常关闭旧程序，打开0.3.8并选择原完整host.json。配置目录可以保留旧版本名；身份不按程序版本重新生成。
- 配套Android 0.3.8。部署自己的[Cloudflare或Render中继](../../relay/README.md)，将导出的登记JSON放入后端Secret，避免上传私有身份、策略或恢复日志。

## 尚未完成

手机真实审批、问题实时作答、模型/推理设置、附件和ChatGPT常驻未接通。没有安装版本自动门禁、签名更新或备份回滚保护。同一Windows用户的恶意进程不在本机隔离能力内；免费托管和网络切换未有广泛长期稳定性结论。

## 已有验证

76项相关Python回归及最终29项定向检查通过，Windows隐藏离线自测退出0，合成初始化/DPAPI/Tk通过。用户已确认此前内容读取与命令行弹窗修正。当前0.3.8进程与网络连接已核对，实际手机端更新后的完整发送/审批仍需另行验收；详见[验证报告](../results/2026-10-30-full-text-v038.md)。

ZIP：`device-bridge-companion-0.3.8-windows.zip`，25883992字节，SHA-256 `65d83581d8010094f51c964926aeca34a8c84611f19bb4a0b4b024890828d915`。发布此前验证过的独立干净归档，不重新打包含本机数据的运行目录。附构建清单、校验文件和第三方许可证。

## Developer review

从[工作流](../review/WORKFLOWS.md)、[后端实现](../review/BACKEND.md)、[API](../review/API.md)和[Agent README](../../agent/README.MD)进入。优先检查设备/策略双层鉴权、最后交接与撤销串行、结果释放再鉴权、子进程Job Object寿命、历史/消息游标绑定和中继资源预算。运行时代码来源 `cc9c65e`，发行标签另包含文档修订。
