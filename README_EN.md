# token-gateway

> Universal Model Capability Gateway — LLM sync face implemented; task face (4 modalities) planned in M2.5.

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)
[![CI](https://github.com/funcommons/token-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/funcommons/token-gateway/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/Docs-online-purple.svg)](https://funcommons.github.io/token-gateway/en/)

**Online Docs**: https://funcommons.github.io/token-gateway/en/ | [中文文档](./README.md)

## Source Layout

Maven multi-module (design doc §9; faces independently deployable):

- `gateway-spi` — capability-face SPI (M0 frozen): BackendAdapter + Capability + 7 face interfaces + task delegate + contract DTOs + config model + startup capability validation
- `gateway-core` — shared infrastructure (~70% across faces): envelope + cross-cutting (trace/rate-limit/idempotency/moderation) + backend RPC clients + THMP contract face; zero JDBC
- `face-llm` — LLM sync face: 6 endpoints + relay pipeline + SSE passthrough + protocol conversion; no DB, no local disk
- `face-task` — task face (M2.5a/c landed; Worker execution M2.5b in progress): task state hosted by the lotask4j platform (no DB); caller endpoints + billing saga + webhook verification + notify + resource proxy + timeout-clock/reconciliation fallback, only a resource cache disk
- `task-worker` — self-written task execution Worker (M2.5b): pulls/reports via the lotask4j worker API + Groovy three-hook sandbox (AST blacklist + egress allowlist + hook timeout); script source of truth in repo-root `scripts/`; independent process
- `app` — assembly: `token-gateway.face = llm | task | all` (same jar, different config per deployment group)

**Independent face deployment**: `face=llm` loads only the LLM face (no DB/disk); `face=task` loads only the task face (DB + disk); `face=all` runs both (default). Gated by `FaceLlmAssembly` / `FaceTaskAssembly` + `@ConditionalOnFace` (invalid face value fails fast at startup).

`face-llm` package layout (`fun.commons.tokengateway`):

| Package | Responsibility |
|---|---|
| `controller/` | LLM endpoints (chat/completions, messages, models, count-tokens, embeddings, images) |
| `relay/` | RelayOrchestrator pipeline (validate → distribute → billing saga) + usage extraction + access-log & channel-health reporting |
| `upstream/` | SsePassthroughInvoker (SSE passthrough + frame reassembly + heartbeat) |
| `format/` | OpenAI/Anthropic protocol converters (SSE state machines) |
| `rpc/` | Backend capability-face HTTP clients (token / channel / billing / moderation / access-log / chat-model) |
| `thmp/` | TokenHub contract face (HMAC signing / candidate resolve / shadow compare / gradual cutover / key decryption) |
| `moderation/` `ratelimit/` `idempotency/` `trace/` | Cross-cutting (moderation gate / rate limit / idempotency / tracing) |
| `contract/` | DTOs shared with backends |
| `framework/` | Self-contained ApiResponse / ApiCode / ApiError envelope |

## Build & Run

Prerequisites: backend capability services reachable (e.g. MMagiX monolith on :9400); Redis reachable (default localhost:6379).

```bash
mvn package                                                   # 256 tests
java -jar app/target/token-gateway-app-0.0.1-SNAPSHOT.jar     # listens on :9401
```

Key configuration (`app/src/main/resources/application.yml`): `gateway.backend.url` (backend RPC target), `gateway.backend.internal-token` (`dev-` prefix skips the signed header), `gateway.health-report.enabled` (channel health reporting, default on), `gateway.thmp.*` (TokenHub shadow/cutover, default off).

## Documentation

### User Docs (Callers)

| Doc | Description |
|---|---|
| [LLM Onboarding (en)](https://funcommons.github.io/token-gateway/en/user/llm-guide) · [中文](docs/用户文档/01_LLM面接入手册.md) | Caller integration (OpenAI/Anthropic SDK + error codes + backend config) |
| [Task Onboarding (en)](https://funcommons.github.io/token-gateway/en/user/task-guide) · [中文](docs/用户文档/02_任务面接入手册.md) | Task face (create/poll/notify/resource proxy) — M2.5a/c landed; Worker execution M2.5b in progress |
| [LLM API Contract](docs/用户文档/03_LLM面API契约.yaml) | OpenAPI contract (6 endpoints) |
| [Task API Contract](docs/用户文档/04_任务面API契约.yaml) | M2.5a/c landed; Worker execution M2.5b in progress |

### Developer Docs (Gateway / Backend Integrators)

| Doc | Description |
|---|---|
| [Design Proposal (en)](https://funcommons.github.io/token-gateway/en/dev/design) · [中文](docs/开发文档/01_设计方案.md) | Capability-face SPI · yml config model · adapter matrix · milestones |
| [Backend Onboarding (en)](https://funcommons.github.io/token-gateway/en/dev/backend-onboarding) · [中文](docs/开发文档/02_后端接入开发手册.md) | Implement the capability contract in any language to onboard |
| [Backend Security Contract (en)](https://funcommons.github.io/token-gateway/en/dev/backend-security-contract) · [中文](docs/开发文档/04_后端服务对接安全契约方案.md) | Auth three modes (jwt/key/none) · scenario tiers · per-request signing · credential rotation |
| [Task Face lotask4j Hosting (en)](https://funcommons.github.io/token-gateway/en/dev/task-lotask4j-hosting) · [中文](docs/开发文档/05_任务面lotask4j托管方案.md) | Platform-mediated execution · Groovy script adaptation · zero-modification onboarding (R1~R9 re-evaluated) |
| [Task Face Development Handbook (en)](https://funcommons.github.io/token-gateway/en/dev/task-face-dev-handbook) · [中文](docs/开发文档/06_任务面face-task开发手册.md) | Component breakdown · lotask4j integration contract · config model · M2.5 task breakdown |
| [Capability-Face Contract](docs/开发文档/03_能力面接口契约.yaml) | OpenAPI contract for backend endpoints + MQ log messages |
