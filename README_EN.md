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
mvn verify                                                    # 369 tests + coverage gate
java -jar app/target/token-gateway-app-0.2.0.jar              # listens on :9401
```

**Full-chain smoke** (LLM face + task face positive/negative paths + notify + reconciliation, 11 steps / 28 assertions — 29 with notify verify keys configured on both sides, five processes, zero real dependencies):

```bash
docker compose -f docker-compose.smoke.yml up -d              # redis + token-mock
# start lotask4j + gateway/worker/demo control plane per docs/en/dev/lotask4j-tenant-onboarding.md §5
bash scripts/smoke.sh                                         # PASS/FAIL matrix, nonzero exit = failure
```

**Coverage gate**: JaCoCo `check` is bound to `verify` — `mvn verify` fails below per-module thresholds (gateway-core 86% / task-worker 80% / face-llm 75% / face-task 72% / gateway-spi 77%, ratcheted upward as coverage grows).

Key configuration (`app/src/main/resources/application.yml`): `gateway.backend.url` (backend RPC target), `gateway.backend.internal-token` (`dev-` prefix skips the signed header), `gateway.health-report.enabled` (channel health reporting, default on), `gateway.thmp.*` (TokenHub shadow/cutover, default off).

**Embedded mode (spring-boot-starter)**: reference the starter in your own WebFlux application to assemble the gateway (published via JitPack, tag = version):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.funcommons.token-gateway</groupId>
    <artifactId>token-gateway-spring-boot-starter</artifactId>
    <version>v0.3.0</version>
</dependency>
```

The host must be on the **WebFlux stack** (the starter stays inactive in MVC hosts); `token-gateway.face = llm | task | all` grouping semantics apply, `token-gateway.enabled=false` turns it off, and `token-gateway.worker.enabled=true` also assembles the task-executing Worker into the host (a full embedded task-face loop — no separate Worker process). See "Embedded Mode" in [docs/en/user/quickstart.md](docs/en/user/quickstart.md).

## Documentation

### User Docs (Callers)

| Doc | Description |
|---|---|
| [Overview (en)](https://funcommons.github.io/token-gateway/en/user/overview) · [中文](docs/用户文档/01_产品简介.md) | Positioning / core concepts / doc map |
| [Quickstart (en)](https://funcommons.github.io/token-gateway/en/user/quickstart) · [中文](docs/用户文档/02_快速开始.md) | First call in 5 minutes (LLM + task face) |
| [Conventions (en)](https://funcommons.github.io/token-gateway/en/user/conventions) · [中文](docs/用户文档/03_通用约定.md) | Auth / error envelope & codes / rate limit / idempotency (**required**) |
| [LLM Guide (en)](https://funcommons.github.io/token-gateway/en/user/llm-guide) · [中文](docs/用户文档/04_LLM面接入手册.md) | LLM face (6 endpoints + SDK examples + acceptance checklist) |
| [Task Guide (en)](https://funcommons.github.io/token-gateway/en/user/task-guide) · [中文](docs/用户文档/05_任务面接入手册.md) | Task face (4-modality create/poll/notify/resource proxy + callback verification) |
| [FAQ (en)](https://funcommons.github.io/token-gateway/en/user/faq) · [中文](docs/用户文档/06_FAQ.md) | Troubleshooting quick reference |
| [LLM API Contract](docs/用户文档/07_LLM面API契约.yaml) | OpenAPI contract (6 endpoints) |
| [Task API Contract](docs/用户文档/08_任务面API契约.yaml) | OpenAPI contract (M2.5 landed) |

### Developer Docs (Gateway / Backend Integrators)

| Doc | Description |
|---|---|
| [Design Proposal (en)](https://funcommons.github.io/token-gateway/en/dev/design) · [中文](docs/开发文档/01_设计方案.md) | Capability-face SPI · yml config model · adapter matrix · milestones |
| [Backend Onboarding (en)](https://funcommons.github.io/token-gateway/en/dev/backend-onboarding) · [中文](docs/开发文档/02_后端接入开发手册.md) | Implement the capability contract in any language to onboard |
| [Backend Security Contract (en)](https://funcommons.github.io/token-gateway/en/dev/backend-security-contract) · [中文](docs/开发文档/04_后端服务对接安全契约方案.md) | Auth three modes (jwt/key/none) · scenario tiers · per-request signing · credential rotation |
| [Task Face lotask4j Hosting (en)](https://funcommons.github.io/token-gateway/en/dev/task-lotask4j-hosting) · [中文](docs/开发文档/05_任务面lotask4j托管方案.md) | Platform-mediated execution · Groovy script adaptation · zero-modification onboarding (R1~R9 re-evaluated) |
| [Task Face Development Handbook (en)](https://funcommons.github.io/token-gateway/en/dev/task-face-dev-handbook) · [中文](docs/开发文档/06_任务面face-task开发手册.md) | Component breakdown · lotask4j integration contract · config model · M2.5 task breakdown |
| [lotask4j Tenant Onboarding (en)](https://funcommons.github.io/token-gateway/en/dev/lotask4j-tenant-onboarding) · [中文](docs/开发文档/07_lotask4j租户开通手册.md) | Tenant provisioning · credential injection · full-chain smoke runbook |
| [Capability-Face Contract](docs/开发文档/03_能力面接口契约.yaml) | OpenAPI contract for backend endpoints + MQ log messages |
