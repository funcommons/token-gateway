# 贡献指南

感谢关注 token-gateway。提交 issue / PR 前请先读本指南。

## 环境准备

- JDK 17+，Maven 3.9+（或直接用 CI 同款 `mvn`）
- 本地 Redis（限流/幂等依赖；`docker run -p 6379:6379 redis` 即可）
- 本地联调可跑 [demo-control-plane](demo-control-plane/)（端口 9400 内存控制层，`GATEWAY_BACKEND_URL` 指向它即可无真实后端开发）

## 开发流程

1. **issue 先行**：功能/缺陷先开 issue 对齐方案，重大改动（新协议面、契约变更、Breaking）先在 issue 里给出设计再动手
2. **分支**：从 `main` 拉特性分支，命名 `fix/<issue>-<slug>` 或 `feat/<issue>-<slug>`
3. **测试同行**：行为变更必须带测试（本项目 TDD 惯例：先红后绿）；`mvn verify` 含 JaCoCo 覆盖率门禁（模块阈值见 README），全量测试须绿
4. **文档同更**：面向调用方的行为变化同步更新 `docs/用户文档/`（中英），契约变化同步 `docs/用户文档/08_*.yaml` / `09_*.yaml`；面向部署的同步 `docs/开发文档/08_部署运维手册.md`
5. **CHANGELOG**：用户可感知的变化在 `CHANGELOG.md` `[Unreleased]` 段补条目（Keep a Changelog 格式；发布说明按 [`docs/_templates/发布说明模板.md`](docs/_templates/发布说明模板.md)）；测试报告/架构决策（ADR）同目录有对应模板
6. **提交信息**：`类型(作用域): 摘要`，如 `fix(auth): ...` / `feat(task): ...`；正文说明动机与验证方式（含 issue 引用 `(#N)`）

## 代码约定

- WebFlux 响应式管线：禁止在管线内阻塞调用
- 错误语义：业务失败抛 `RelayException`（HTTP 状态 + `ApiCode` 信封码），不得吞错改语义；凭证校验失败（401/10202）与校验服务不可用（504/10003）语义分离（issue #22 先例）
- 密钥/凭证：环境变量注入，禁止入仓；凭证不出现在日志与错误信息
- 文档以代码为唯一事实源：端点、错误码、配置项、测试数以代码/CI 输出为准

## 提交 PR

- CI 全绿（测试 + 覆盖率门禁）是合并前提
- 描述里写清：动机（issue 链接）、变更点、验证方式（测试/冒烟输出）
- Breaking 变更需在 CHANGELOG 标注，并评估 starter 嵌入方影响

## 报告安全问题

**请勿公开提 issue**。按 [SECURITY.md](./SECURITY.md) 的渠道私下报告。
