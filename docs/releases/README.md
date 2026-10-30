# 0.3.8 公开预发行

Android 和 Windows 分别使用 `android-v0.3.8`、`windows-v0.3.8` 标签，同属同一套协议与审阅文档。两份发行均标记实验性 pre-release；中继源码版本继续是0.3.0，本次不修改已有云部署。

- [Android 说明](android-0.3.8.md)
- [Windows Companion 说明](windows-0.3.8.md)
- [工作流](../review/WORKFLOWS.md)、[后端](../review/BACKEND.md)、[API](../review/API.md)、[Agent 指南](../../agent/README.MD)

发行资产限定为各平台一个已验证程序包、`SHA256SUMS.txt`、`build-manifest.json` 和对应的 `third-party-licenses.zip`。不发布 AndroidTest APK、运行目录、host.json、登记材料、配对二维码、私钥、策略、日志或数据库。

下载后可在文件所在目录执行 `Get-FileHash -Algorithm SHA256 <文件名>`，与该发行的 `SHA256SUMS.txt` 对照。构建输入的运行时代码为 `cc9c65e`；发行标签还包含后续文档、公开发布边界及说明修订。两个主程序包保持此前验证过的字节，不通过重新打包正在运行的目录生成。

公开前执行了可达提交中的常见凭据/私有路径模式检查、已跟踪二进制审阅、APK/ZIP文件清单检查，以及版本/签名/哈希核对。这是有范围的发布前检查，不能替代独立安全审计。开发者可从API错误、撤销竞争、unknown恢复及网络切换开始review。
