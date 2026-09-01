# token-gateway

> 大模型网关服务（通用模型能力网关 · 当前实现 LLM 同步面；任务四模态面规划中 M2.5）

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)
[![CI](https://github.com/funcommons/token-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/funcommons/token-gateway/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/Docs-online-purple.svg)](https://funcommons.github.io/token-gateway/)

**在线文档**：https://funcommons.github.io/token-gateway/ | [English README](./README_EN.md)

## 源码

Maven 多模块（设计方案 §9 分模块方案，face 独立部署）：

```
token-gateway/
  gateway-spi    # 能力面 SPI（M0 冻结）：BackendAdapter + Capability + 七面接口 +
                 # task 委托面 + contract DTO + 能力面配置模型 + 启动期开关∩能力校验
  gateway-core   # 共享基建（两面同源 ~70%）：信封 framework + 横切(trace/限流/幂等/审核) +
                 # 后端 RPC 客户端 + THMP 契约面 + 配置装配；零 JDBC
  face-llm       # LLM 同步面：6 端点 + RelayOrchestrator 管线 + SSE 透传 + 协议转换；
                 # 无数据库无本地盘，弹性扩缩
  face-task      # 任务面（占位，M2.5）：任务状态由 lotask4j 平台托管（无 DB），
                 # 仅资源缓存盘；caller 端点 + 计费 saga + notify + 资源代理
  app            # 装配：token-gateway.face = llm | task | all（同 jar 异配置部署分组）
```

**face 独立部署**：`token-gateway.face=llm` 只装 LLM 面（无 DB 无盘）；`=task` 只装任务面（挂库挂盘）；`=all` 单组合跑（默认）。由 `FaceLlmAssembly` / `FaceTaskAssembly` + `@ConditionalOnFace` 按 face 条件装配（face 值非法启动 fail-fast）。

`face-llm` 包结构（`fun.commons.tokengateway`）：

| 包 | 职责 |
|---|---|
| `controller/` | LLM 面端点（chat/completions、messages、models、count-tokens、embeddings、images） |
| `relay/` | RelayOrchestrator 公共编排（validate → distribute → saga 计费）+ usage 提取 + 访问日志上报 |
| `upstream/` | SsePassthroughInvoker（SSE 透传 + 帧重组 + 心跳） |
| `format/` | OpenAI/Anthropic 协议转换（SSE 转换器状态机） |
| `rpc/` | 后端能力面 HTTP 客户端（token/channel/billing/moderation/access-log/chat-model） |
| `thmp/` | TokenHub 契约面（HMAC 签名 / 候选解析 / 影子比对 / 灰度切流 / key 解密） |
| `moderation/` `ratelimit/` `idempotency/` `trace/` | 横切层（审核闸门 / 限流 / 幂等 / 链路追踪） |
| `contract/` | 与后端共享的 DTO 契约 |
| `framework/` | 自含 ApiResponse / ApiCode / ApiError 信封 |

## 构建与运行

前置：后端能力面服务（如 MMagiX 单体）已在 9400 端口启动；Redis 可达（默认 localhost:6379）。

```bash
mvn package                                                   # 194 个单测
java -jar app/target/token-gateway-app-0.0.1-SNAPSHOT.jar     # 监听 9401
```

关键配置（`app/src/main/resources/application.yml`）：`gateway.backend.url`（后端 RPC 目标）、`gateway.backend.internal-token`（`dev-` 开头跳过签名头）、`gateway.health-report.enabled`（渠道健康上报，默认开）、`gateway.thmp.*`（TokenHub 影子/切流，默认关闭）。

## 文档

### 用户文档（网关调用方）

| 文档 | 说明 |
|---|---|
| [docs/用户文档/01_LLM面接入手册.md](docs/用户文档/01_LLM面接入手册.md) | LLM 面调用方接入（OpenAI/Anthropic SDK + 错误码 + 后端配置） |
| [docs/用户文档/02_任务面接入手册.md](docs/用户文档/02_任务面接入手册.md) | 任务面接入（四模态 create/poll/notify/资源代理）——**规划中 M2.5，未实现** |
| [docs/用户文档/03_LLM面API契约.yaml](docs/用户文档/03_LLM面API契约.yaml) | LLM 面 OpenAPI 契约（6 端点） |
| [docs/用户文档/04_任务面API契约.yaml](docs/用户文档/04_任务面API契约.yaml) | 任务面 OpenAPI 契约——**规划中 M2.5，未实现** |

### 开发文档（网关开发与后端接入方）

| 文档 | 说明 |
|---|---|
| [docs/开发文档/01_设计方案.md](docs/开发文档/01_设计方案.md) | 设计方案（能力面 SPI · yml 能力面配置 · 适配器矩阵 · 分模块与部署分组 · 分期路线） |
| [docs/开发文档/02_后端接入开发手册.md](docs/开发文档/02_后端接入开发手册.md) | 后端接入开发手册（实现能力面契约即接入，不限语言） |
| [docs/开发文档/04_后端服务对接安全契约方案.md](docs/开发文档/04_后端服务对接安全契约方案.md) | 后端对接安全契约（鉴权三式 jwt/key/none · 场景分级 · 逐请求签名 · 凭证轮换） |
| [docs/开发文档/05_任务面lotask4j托管方案.md](docs/开发文档/05_任务面lotask4j托管方案.md) | 任务面 lotask4j 托管方案（平台中转执行 · Groovy 脚本适配 · lotask4j 改造清单 R1~R9） |
| [docs/开发文档/03_能力面接口契约.yaml](docs/开发文档/03_能力面接口契约.yaml) | 能力面 OpenAPI 契约（后端需实现的端点 + MQ 日志消息） |
