# token-gateway LLM Face Onboarding Guide

| Item | Content |
|---|---|
| Document | LLM Face Onboarding Guide (chat / embeddings / synchronous image generation / model catalog + backend onboarding + adapter development) |
| Companion | Task face: see `./task-guide.md` (**planned M2.5, not yet implemented**); API contract: [03_LLM面API契约.yaml](https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/03_LLM面API契约.yaml); design doc: see `../dev/design.md` |
| Version | V1.2 (2026-08-31, split by face: LLM face / task face as separate volumes) |
| Codebase | `fun.commons.tokengateway` (LLM face, port 9401, live endpoints exactly as written here) |

---

## 1. Gateway Positioning & Topology

```
Caller (OpenAI SDK / Anthropic SDK / curl / business backend)
        │  Authorization: Bearer <credential>  or  x-api-key: <credential>
        ▼
token-gateway (9401)  ── protocol normalization / rate limiting / idempotency / moderation switch / billing saga / logging / auditing
        │  routes to backend services per the yml capability-face configuration
        ▼
Backend services (each capability face has its own address; may be deployed separately or all point to one monolith):
Routing · Credential validation · Billing · Moderation · Logging (rpc/mq) · Audit · Model catalog
(Protocol shape is single-selected via the yml adapter: mmagix / tokenhub / tokengo / generic openapi)
```

The gateway makes three promises:

1. **Protocol normalization**: OpenAI-shaped requests (`/v1/chat/completions`, etc.) are always returned in OpenAI shape regardless of which upstream they hit; Anthropic-shaped requests (`/v1/messages`) are always returned in Anthropic shape. Callers never perceive upstream differences.
2. **Separable services**: Callers never perceive the backend — addressing is resolved by the yml routing configuration keyed on `model` (wildcard binding, supports canary/shadow); billing/logging/audit and other services can be deployed independently at their own addresses.
3. **Credential is identity**: The gateway does not issue credentials; credential semantics are defined by the backend that gets hit (MMagiX token / TokenHub `sk-thmp-*` / TokenGo token).

---

## 2. Quick Start for Callers

### 2.1 OpenAI SDK (recommended — just change base_url)

```python
from openai import OpenAI

client = OpenAI(
    api_key="<your credential>",
    base_url="http://<gateway-host>:9401/v1"
)
resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "Hello"}],
)
print(resp.choices[0].message.content)
```

### 2.2 curl (synchronous)

```bash
curl -s http://localhost:9401/v1/chat/completions \
  -H "Authorization: Bearer <your credential>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

### 2.3 Streaming (SSE)

Add `"stream": true` to the request body; the response is `text/event-stream`, passing upstream chunks through segment by segment (OpenAI format `data: {...}` / terminated by `data: [DONE]`; Anthropic protocol endpoints use the `event:` + `data:` shape). Clients just parse standard SSE; the gateway does not buffer the full response.

### 2.4 Anthropic SDK

```python
import anthropic

client = anthropic.Anthropic(
    api_key="<your credential>",
    base_url="http://<gateway-host>:9401"   # the SDK appends /v1/messages automatically
)
msg = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hello"}],
)
```

---

## 3. Authentication

| Method | Header | Notes |
|---|---|---|
| Bearer (recommended) | `Authorization: Bearer <credential>` | Default for the OpenAI ecosystem |
| API-Key header | `x-api-key: <credential>` | Default for the Anthropic ecosystem; if both headers are sent, Bearer takes precedence |

- Credential validation happens at the first pipeline step (the `TOKEN_VALIDATE` capability face); on failure it returns **10202 invalid token / 10200 unauthenticated** (HTTP 401 + error envelope).
- Which backend the credential is validated against is determined by the backend the model routing hits — **switching backends does not change how you call**.
- Credentials never appear in any gateway logs or error messages.

---

## 4. Endpoint Overview (LLM Face)

| # | Endpoint | Method | Protocol Shape | Streaming |
|---|---|---|---|---|
| 1 | `/v1/chat/completions` | POST | OpenAI | ✅ `stream:true` |
| 2 | `/v1/messages` | POST | Anthropic | ✅ `stream:true` |
| 3 | `/v1/messages/count_tokens` | POST | Anthropic | — |
| 4 | `/v1/embeddings` | POST | OpenAI | — |
| 5 | `/v1/images/generations` | POST | OpenAI (synchronous image generation) | — |
| 6 | `/v1/models` | GET | OpenAI | — |

> Task-face endpoints (async tasks for videos/images/audios/tts) see `./task-guide.md` (planned M2.5).
> For the full field contract see [03_LLM面API契约.yaml](https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/03_LLM面API契约.yaml).

### 4.1 Chat Completions Notes

- Request: `model` (required; the gateway falls back to the default model when omitted) + `messages` (required) + standard OpenAI optional parameters (`max_tokens` / `temperature` / `tools` / `stream`, etc.).
- The `tools` call chain passes through an Anthropic tool-chain sanitizer (cross-protocol tool-calling compatibility).
- Success response = **upstream payload passed through as-is (after protocol normalization), no envelope**; `prompt_tokens / completion_tokens / cached_tokens` inside `usage` are the basis for billing settlement.
- Upstreams that lack `usage`: settled by estimated tokens (the estimate is slightly higher than actual, and the error direction costs the caller nothing).
- Billing saga: pre-charge before forwarding (insufficient balance → 10617, HTTP 402 semantic envelope) → forward → settle by actual usage; automatic full refund on total failure.

### 4.2 Embeddings / Images Notes

- `/v1/embeddings`: `model` + `input` (string or array); returns the OpenAI embedding shape.
- `/v1/images/generations`: `model` + `prompt` + optional `size`/`n`; billed per generated image.
- Neither supports streaming; when the upstream is Anthropic-shaped, responses are likewise normalized to OpenAI shape.

### 4.3 Models Notes

`GET /v1/models` returns the model catalog available to the caller (`object:"list"` + `data[]`), sourced from the `MODEL_CATALOG` capability face. Common reason for an empty catalog: the tenant owning the credential has no models provisioned.

---

## 5. Response Shapes (Ironclad Rules for Success/Failure Judgment)

| Shape | HTTP | Body | Judgment |
|---|---|---|---|
| Success (non-streaming) | 200 | Upstream business shape (OpenAI/Anthropic), **no envelope** | Presence of `choices`/`content`/`data` means success |
| Success (streaming) | 200 | `text/event-stream` | Parse segment by segment; ends at `[DONE]`/`message_stop` |
| Business/system error | 4xx/5xx | 6-field envelope | `code != 0` means failure |

Error envelope:

```json
{
  "code": 10202,
  "message": "Invalid token",
  "data": null,
  "error": [{"field": null, "code": null, "message": "Invalid token", "value": null}],
  "trace_id": "c0a80101-...",
  "timestamp": 1756600000000
}
```

> **Note**: Do not use heuristics other than `HTTP status` to judge failure, and do not assume a success response carries a `code` field — success is a pass-through shape.

---

## 6. Cross-Cutting Conventions

### 6.1 Tracing

- Requests may carry `X-Trace-Id` (generated by the gateway if absent); the response header **always returns** `X-Trace-Id`.
- When troubleshooting, providing the `trace_id` links gateway logs ↔ backend logs ↔ access logs end to end.

### 6.2 Rate Limiting

- Dimensions: per-credential (apiKey) + global, two tiers; fixed window.
- On limit breach: **HTTP 429** + envelope (10500) + response headers:

| Header | Meaning |
|---|---|
| `Retry-After` | Suggested seconds to wait (clients should back off exponentially) |
| `X-RateLimit-Limit` | Window quota |
| `X-RateLimit-Remaining` | Remaining quota |
| `X-RateLimit-Reset` | Seconds until reset |

### 6.3 Idempotency

- Write operations (all POST `/v1/**`) may carry `Idempotency-Key: <uuid v4>`.
- Duplicate requests with the same credential + same key are **reject-style deduplicated** within the window (10501 duplicate submission) and are not billed twice.
- Recommendation: always send it for non-idempotent-safe calls (image generation, etc.).

### 6.4 Timeout Budgets

| Stage | Budget |
|---|---|
| Connect to upstream | 3s |
| First byte | 30s |
| Total duration | 300s (SSE long connections share the same budget) |
| Gateway → backend capability calls | Registry configuration (route 3s / billing 5s / moderation 2s defaults) |

Timeouts return the 10003 (service call timeout) envelope; total upstream failure returns 10004.

---

## 7. Error Code Quick Reference

| code | HTTP | Meaning | Caller Action |
|---|---|---|---|
| 10001 | 500 | System busy | Back off and retry |
| 10003 | 504 | Service call timeout | Back off and retry; for long texts check max_tokens |
| 10004 | 502 | Third-party service exception (all upstreams failed) | Back off and retry; contact the platform if failures persist |
| 10100~10106 | 400 | Parameter error/missing/format/range/JSON; 10106 is also used for content-safety rejection | Fix the request; do not retry |
| 10200 | 401 | Unauthenticated / token expired | Use a valid credential |
| 10202 | 401 | Invalid token | Use a valid credential |
| 10300 | 403 | Insufficient permission (model not provisioned) | Contact the platform for provisioning |
| 10400 | 404 | Model does not exist / no available channel | Check the model name |
| 10402 | 409 | State conflict | Handle per business logic |
| 10500 | 429 | Too many requests | Back off per `Retry-After` |
| 10501 | 409 | Duplicate submission (idempotency key hit) | Use a new Idempotency-Key |
| 10617 | 402 | Insufficient balance | Top up, then retry |
| 10700 | 200 | Partial success | Handle per the details inside `data` |

---

## 8. Backend Service Onboarding (yml configuration; no registration concept)

> **Configuration is onboarding**: configure backend services by **capability face** in the project yml (url + auth + switch), one address per service type;
> logging/billing/moderation and other services may be deployed separately. A change = edit yml + restart. For the full field semantics see "Design Doc" §5.

### 8.1 Protocol Adapter Selection (single global choice)

| Your backend is | adapter | Notes |
|---|---|---|
| MMagiX main application | `mmagix` | All seven capabilities, production shape (M1) |
| TokenHub (thmp-app) | `tokenhub` | Route face HMAC four-header contract; passthrough/contract dual modes |
| TokenGo | `tokengo` | OpenAI-compatible pass-through (M3 alignment in progress) |
| **Any third-party system in any language** | `openapi` | **Built-in generic adapter** — just implement the capability-face contract per the Backend Onboarding Developer Manual (`../dev/backend-onboarding.md`); language-agnostic |
| Special protocol that the contract cannot express | `custom:<spiName>` | Java SPI path (§9) |

### 8.2 Configuration Example (Separated Service Deployment)

```yaml
token-gateway:
  adapter: mmagix
  route:                    # routing / dispatch
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
    routes:
      - models: ["gpt-*", "*"]
  token-validate:           # credential validation (may share the address with route)
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
  billing:                  # billing service (independent deployment example)
    url: http://billing-svc:9410
    auth: jwt
    jwt-secret: ${GW_BILLING_JWT_SECRET}
  moderation:               # content moderation (can be disabled entirely)
    enabled: true
    fail-open: true
    url: http://moderation-svc:9420
    auth: key
    key: ${GW_MODERATION_KEY}
  access-log:               # logging service (two transport types: rpc | mq)
    enabled: true
    transport: rpc          # rpc = synchronous call to log service / mq = Kafka|RocketMQ async
    url: http://log-svc:9430
    auth: none
    # when transport: mq, configure instead:
    # mq:
    #   type: kafka          # kafka | rocketmq
    #   bootstrap: kafka-1:9092
    #   topic: token-gateway-access-log
  audit:                    # audit service
    url: http://audit-svc:9440
    auth: jwt
    jwt-secret: ${GW_AUDIT_JWT_SECRET}
  model-catalog:
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
```

All seven pointing to the same address = **monolith mode** (e.g., MMagiX 9400, one address shared by seven faces); different hosts = **separated mode**. Deployment topology changes require no code changes — only yml changes.

### 8.3 Three Backend Auth Modes

> Authoritative definition: Backend Integration Security Contract (`../en/dev/backend-security-contract.md`).

| auth | How it is sent | Recommendation | Applicability |
|---|---|---|---|
| `jwt` | HS256-signed JWT (`Authorization: Bearer`, claims include iss/caller/tenant_id) | **Recommended (default)** | When identity semantics are needed — **the production internal-token is exactly this form**; changing the secret requires synchronized re-minting on the gateway; add per-request signing for cross-segment calls |
| `key` | `X-API-Key: <static key>` | Acceptable (intranet only) | Simple shared secret (constant-time comparison) |
| `none` | No auth header sent | Restricted | localhost/sidecar same-host isolation only; whitelisting is defense-in-depth, not a substitute |

Credentials must always be injected via environment variables and never committed to the repo; logs only ever show masked values. The `token` static-token mode was removed (existing systems re-mint as jwt).

### 8.4 Key Switches and the Three billing Values

| Configuration | Behavior |
|---|---|
| `moderation.enabled` | Whether to run content-moderation scanning; `fail-open` controls pass-through when the moderation dependency fails (aligned with the fail-open doc's wording) |
| `access-log.enabled` | Whether logs are persisted (off = in-memory counting only); `transport` selects rpc synchronous / mq asynchronous (Kafka\|RocketMQ, at-least-once) |
| `billing` | `direct` = gateway saga billing (goes through the billing service) / `passthrough` = backend self-billing (THMP sk- closed loop) / `off` = no billing (intranet/BYOK) |
| `health-report` | Channel health signal reporting (record-success/failure); when off, compensate on the monitoring side |

---

## 9. Third-Party Adapter Development Guide (Java SPI Path)

> The preferred language-agnostic path is the `openapi` generic adapter in §8.1 (implement the capability-face contract per the Backend Onboarding Developer Manual — Go/Python/Node all work). This chapter only applies when the protocol is special and the generic contract cannot express it.

### 9.1 Dependencies and Interfaces

Create a new Maven module that **depends only on `gateway-spi`** (not on the gateway core):

```java
public class AcmeAdapter implements BackendAdapter {
    public String backendId() { return "acme"; }
    public Set<Capability> capabilities() {
        return Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE, Capability.BILLING);
    }
}
// Implement one or more of the six interfaces by capability: TokenValidator / RouteResolver / ModerationScanner
//                                                              / BillingClient / AccessLogSink / ModelCatalog
```

Two registration options: `META-INF/services` (ServiceLoader) or Spring `@Bean` (within the assembly module).

### 9.2 Ironclad Rules (violating any = onboarding acceptance fails)

1. Adapters must be **stateless**; caches/connection pools are self-managed and must be reloadable by the registry.
2. Errors must be thrown up with envelope error codes (10202/10004/10617 semantics); **never swallow errors or alter semantics**.
3. Timeouts must use the budgets injected from registry configuration; **no hardcoded timeout overrides of your own**.
4. `toString` of credential-bearing objects such as `TokenContext` must be redacted.
5. Capability declarations must be honest — declaring `BILLING` means you must fully implement the preConsume/settle/refund trio.

### 9.3 Self-Test Checklist

- [ ] validate: invalid credential → 10202; expired → 10200
- [ ] resolve: unknown model → 10400; returned DistributeVO fields complete (baseUrl/credential/modelMapping)
- [ ] billing (if declared): insufficient pre-charge → 10617; settle and refund are idempotent
- [ ] End-to-end smoke script passes (inline backend stubs in compose)

---

## 10. Onboarding Acceptance Checklist (Caller)

- [ ] base_url points at the gateway (9401); SDK auth header uses Bearer or x-api-key
- [ ] Success judgment follows §5 (pass-through shape, no envelope); error handling covers the three high-frequency codes 10202/10500/10004
- [ ] On 429, read `Retry-After` and back off exponentially (no tight retry loops)
- [ ] Non-idempotent calls carry `Idempotency-Key`
- [ ] Streaming consumption parses standard SSE, handling `[DONE]` and mid-stream disconnects
- [ ] Record the response header `X-Trace-Id` for troubleshooting

## 11. FAQ

| Symptom | Root Cause | Action |
|---|---|---|
| 401 even though the credential looks valid | The credential hit on the backend side is disabled / the tenant is deactivated | Check the credential status on the backend; disabling takes effect immediately |
| 10400 model does not exist | The model name is not in any registered backend route | Verify with `GET /v1/models`; contact the platform to add a route |
| Response has no `code` field | Normal — success is a pass-through shape | Judge per §5 |
| Stream disconnects mid-way with no settlement basis | Upstream disconnected | The gateway settles by estimation of what was produced; the caller simply retries |
| Frequent 429s | The per-credential window is saturated | Request a quota increase or throttle the client |
