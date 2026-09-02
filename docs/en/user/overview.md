# Product Overview

## What is token-gateway

token-gateway is a **universal model capability gateway**: a single entry point between callers and multiple model providers — protocol normalization, credential validation, route pricing, billing, moderation, rate limiting, idempotency, logging and audit, all in one place. Callers plug in with the familiar OpenAI / Anthropic SDKs by changing one `base_url`; long-running generation (video/image/audio/TTS) goes through the async task face (create → poll → notify → resource proxy).

```
Callers (OpenAI SDK / Anthropic SDK / curl / business backends)
        │  Authorization: Bearer <credential>  or  x-api-key: <credential>
        ▼
token-gateway (9401)  ── protocol normalization / rate limit / idempotency / moderation / billing saga / logs / audit
        │  routes to backend services per capability-face yml config
        ▼
Backends: route · token-validate · billing · moderation · access-log(rpc/mq) · audit · model-catalog
Task face: lotask4j hosts task state + a self-written Worker executes upstreams (Groovy script adaptation)
```

## Core Concepts

| Concept | Description |
|---|---|
| **Face** | The two data planes: the **LLM sync face** (chat/embeddings/image-generation, request-response) and the **task face** (four async-task modalities). Same jar, different config, independently deployable (`token-gateway.face=llm/task/all`) |
| **Capability face** | The contract family between gateway and backends: token-validate / route / billing / moderation / access-log / audit / model-catalog — each with its own address, separately deployable or all-in-one |
| **Credential** | The caller's identity is the credential (sk-* key); the gateway never issues credentials and **credentials never appear in logs or error messages** |
| **task_no** | Task-face task number (`T` + timestamp + random), returned at create; poll/notify/resource proxy all key on it |
| **Error envelope** | The unified 6-field failure envelope (`code/message/data/error/trace_id/timestamp`); `code` is a business code (see [Conventions](./conventions.md)) |
| **Resource proxy URL** | Access URL for task artifacts: gateway proxy URL carrying a 24h `exp+sig` capability credential — **upstream raw URLs are never passed through** |

## Why token-gateway

- **Protocol normalization**: OpenAI shape in → OpenAI shape out, Anthropic likewise; callers never see upstream differences
- **Accurate billing**: route-first pricing (different models, different prices), pre-charge before execution, settle on actual usage, automatic refunds on failure
- **Money safety**: full pre-charge + idempotent terminal refund + pre-charge–terminal reconciliation on the task face — no orphan pre-charges
- **Observability**: `X-Trace-Id` stitches gateway ↔ backend ↔ access logs end to end
- **Elastic deployment**: LLM face has no DB and no disk; the task face scales independently with a resource cache disk

## Documentation Map

| Doc | Audience |
|---|---|
| [Quickstart](./quickstart.md) | First call in 5 minutes |
| [Conventions](./conventions.md) | Auth / envelope / error codes / rate limit / idempotency (**required reading**) |
| [LLM Face Guide](./llm-guide.md) | Chat / embeddings / image-generation callers |
| [Task Face Guide](./task-guide.md) | Async task callers |
| [FAQ](./faq.md) | Troubleshooting quick reference |
| Developer docs (`../dev/`) | Gateway developers / backend integrators |
