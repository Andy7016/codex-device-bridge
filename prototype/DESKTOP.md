# 真实 Codex 恢复主机

原 journal 和 loopback mTLS 服务已接到独立 Codex follower，见[贯通结果](../docs/results/2026-10-28-integrated-recovery.md)和[测试包](../docs/integrated-test-packet.md)。ChatGPT 尚未接入。

## 组件和前提

- `desktop.py`：typed service、受控 Node 子进程，提供 submit/status/sync/reconcile/catalog/history。
- `journal.py`：事务日志、单写入锁、原子保存原目标/消息 ID/内部执行 ID，撤销不复活，未分发队列 cancelled。
- `child_job.py`：Windows Job Object 绑定 worker 寿命，无法建立则停止发送。
- `host.py`：本机 mTLS server、本地 stdin 撤销/停止入口，无网络管理接口。
- `desktop/runtime_worker.mjs`：只提供 send/inspect；快照等待结束后请求交接许可，按原 ID 与精确输入核对。

Android v0.1 新增授权目录 catalog 和只读分页 history，worker 增加 history 投影。默认白名单文本，主机明确 `allowFreeText: true` 才允许普通文本，桌面权限保持原策略。客户端与连接文件见[Android 说明](../android/README.md)。

要求 Windows、Python 3.11+、Node.js 22、同一用户对桌面管道的访问权限及明确授权策略。升级桌面后先停止写入重新验证，尚无版本自动门禁。不要扩大 ACL、关闭沙箱或管理员常驻来解决访问失败。

## 配置和启动

下列路径和 ID 均为占位值；真实配置在忽略的 `.local/`。policy 使用[桌面格式](../desktop/README.md)，首轮只授权专用会话和精确文本。

```json
{
  "policy": "D:/path/to/repo/.local/desktop-target-policy.json",
  "journal": "D:/path/to/repo/.local/host/journal.sqlite",
  "attempts": "D:/path/to/repo/.local/host/attempts",
  "serverCert": "D:/path/to/repo/.local/host/server.pem",
  "serverKey": "D:/path/to/repo/.local/host/server.key",
  "peers": [{"device":"test-phone","certificate":"D:/path/to/repo/.local/host/phone.pem"}],
  "bootstrapGrants": [{"device":"test-phone","thread":"codex","rights":["read","send"]}],
  "retention": 8,
  "port": 0
}
```

证书由本地实验预置，尚无真实配对实现。bootstrapGrants 仅首次创建日志时应用；重启不覆盖旧授权或恢复已撤销身份。修改已有权限需另行实现可信本地管理流程。

仓库根目录：

```powershell
python -m prototype.host .local/desktop-host.json
```

启动输出 loopback 端口。stdin 保持打开；EOF 或本地 `{"action":"shutdown"}` 关闭服务。本地 `{"action":"revoke","device":"test-phone"}` 持久撤销身份，网络不能调用。工具不安装系统服务或自启动。

真实测试工具 `python -m tools.integrated_desktop_test` 会发送一次授权文本并故意结束自己的桥接进程；有持久声明时禁止重跑，不能当普通自动测试。

## 网络与恢复契约

证书指纹确定 device，JSON 不能选择身份。每请求一条不超过 8 KiB 的 JSONL，可重新建立 TLS 连接：

```json
{"method":"submit","params":{"thread":"codex","id":"stable-operation","payload":{"text":"exact host-authorized text"}}}
{"method":"status","params":{"id":"stable-operation"}}
{"method":"reconcile","params":{"id":"stable-operation"}}
{"method":"sync","params":{"thread":"codex","cursor":0}}
```

thread 是主机固定别名，不能填任意原 ID。字段严格校验，原始消息 ID、任意 RPC、模型/权限设置、网络 approve、文件路径均不接受；设备授权与主机策略同时生效。

| state | 含义 |
| --- | --- |
| accepted | 请求和不可变关联已持久保存，尚未交接 |
| dispatching | 持久尝试先于外部交接，可能送达 |
| unknown | 可能送达，禁止自动重发；reconcile 只读核对 |
| completed | 原消息交付有正向证据，模型状态另见 result.turnStatus |
| cancelled | 撤销时尚未分发的队列取消 |

找不到消息仍 unknown。reconciliation=unavailable/not_observed 不能生成新操作 ID 重发。原 operationId 改文本会冲突。sync 游标属于本项目日志，过期返回日志快照，不是桌面实时事件游标。

撤销与最后单次交接许可串行，清理获许可 worker 后释放锁；socket 释放结果前再查撤销。已进入原运行时的任务不自动取消，已交付数据无法追回。worker 最长 20 秒，超时先查状态；其他设备不共用该设备锁。

## 验证和限制

```powershell
python -m unittest discover -s tests -q
node --test desktop/adapter.test.mjs desktop/transport.test.mjs desktop/coordination.test.mjs desktop/observation.test.mjs
```

本轮 36 Python + 36 Node 通过。自动测试不连接真实账户；Windows 进程寿命测试用实际合成进程，其他平台跳过专属能力。

缺少生产配对/密钥更新、静态加密、签名更新、备份回滚防护和远端中继。真实审批尚未与持久网络审批整合。完整历史分页、消息增量与 Android 通知待实现。本机 mTLS 单跳不能替代经过中继的端到端加密。
