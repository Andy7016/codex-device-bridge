# v0.3.6大会话历史读取

延续v0.3.4资源/窗口排查与v0.3.5同名证书修正。用户确认手机已通过身份验证、能够加载55条目录，但历史内容显示本机请求处理失败。

## 复现与边界

- 当前策略UTF-8无BOM，Windows包中7个运行时模块齐全，同一启动环境可以找到Node。没有重新配对、切换身份或修改策略来修复读取。
- 在用户已授权只读的首条会话上直接调用原受限worker，limit=1，只输出错误码和计数、不输出或保存正文。先复现invalid_coordination_frame，再用固定错误分类收窄为coordination_frame_too_large。
- 对同一原运行时协调连接仅记录帧声明字节数，得到235、328、291、291、230及10523359。最后一帧约10.5MB，超过旧8MiB硬上限；并非目录大小、手机TLS或UTF-8格式问题。没有抓取网络包或保存会话正文。
- 原8MiB实现对每个分片拼接整个已收缓冲，造成多余复制。新StreamFrameDecoder读取4字节长度后检查0/16MiB边界，只分配一次有界正文缓冲，逐片填入，完整帧才交给JSON解析；支持分片头与多帧同包，关闭时释放不完整帧。
- 16MiB仅适用于本机协调快照；App Tools单响应8MiB、Python/Node stdio8KiB、手机请求8KiB和业务响应64KiB保持。没有公开原始RPC。owner、来源、协议版本与精确会话ID检查保持，未知结果仍不授权重发。
- 超出16MiB时返回固定desktop_history_too_large并给出本机诊断；其他运行时不可用返回desktop_unavailable。异常正文不发到手机。

## 验证

- 44项Node检查通过，包含新增分片/合包、9MiB合成帧与超限长度声明在分配正文前拒绝，以及已有跨会话授权、错误owner/版本、审批与不重发检查。超限攻击只传本地合成长度头，不发送巨量流量。
- 63项Python检查通过，覆盖TLS配对/恢复、同名证书与撤销拒绝、离线配置/GUI关闭、子进程和恢复语义，以及固定历史错误从worker经TLS返回且不泄露异常正文。
- 修正源码后，同一真实已授权会话limit=1读取成功，返回1条消息、hasMore=true、投影JSON300字节；没有把10.5MB快照转发手机或写入结果文件。没有发消息、处理真实审批或更改原会话设置。
- 官方[App Server读取概览](https://learn.chatgpt.com/docs/app-server#api-overview)通过openaiDeveloperDocs搜索/读取核对；本轮实际使用的是既有版本固定的本机协调follower入口，没有改成第二个app-server或复制会话。

Windows包与手机最终结果在完成后补充。
