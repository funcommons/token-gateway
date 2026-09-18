# 安全政策

## 报告漏洞

发现安全漏洞请**不要公开提 issue**，使用 GitHub 的 [私密安全通告（Security Advisory）](https://github.com/funcommons/token-gateway/security/advisories/new) 私下报告。

请在报告中包含：

- 影响版本（tag 或 commit）
- 复现步骤 / PoC（最小化）
- 影响评估（机密性/完整性/可用性，实际可利用性）

我们会在收到后尽快确认（目标 72 小时内响应），并在修复发布前与你同步进展。披露节奏默认协调披露：修复版本发布后公开通告并致谢报告人（可匿名）。

## 支持版本

| 版本 | 支持状态 |
|---|---|
| 最新发布 tag（见 [Releases](https://github.com/funcommons/token-gateway/releases)） | ✅ 接收安全修复 |
| 历史版本 | ❌ 请升级到最新版 |

## 安全设计要点（供评估参考）

- **凭证零泄露**：调用方凭证不出现在网关日志与错误信息；路由快照（含上游凭证）经 AES 加密后才落平台（`task.snapshot-cipher-key`）
- **内部 RPC 鉴权**：网关↔控制层走签名 JWT（`dev-` 前缀无签名模式仅限本地开发）
- **资源代理**：上游原始 URL 永不透传，代理 URL 携带 24h exp+sig 能力凭证，篡改/过期拒绝（10100）
- **回调验签**：lotask webhook 按租户密钥验签 + 回查平台核实，支持双值窗密钥轮转；notify 回调带 `X-THMP-Signature`
- **Worker 沙箱**：Groovy 三钩子脚本运行在 AST 黑名单 + 出网白名单（缺省全禁 fail-closed）+ 钩子超时硬上限内
- **密钥管理**：所有密钥环境变量注入，禁止入仓
