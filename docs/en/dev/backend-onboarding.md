# Universal LLM Gateway (token-gateway) Backend Onboarding Developer Guide

| Item | Content |
|---|---|
| Document | Backend onboarding developer guide (integrate your system as a capability-face service of the gateway) |
| Audience | Backend developers, **language-agnostic** (Go / Python / Node / Java all work) |
| Companion docs | Capability-face contract `03_能力面接口契约.yaml` → [capability-face interface contract](https://github.com/funcommons/token-gateway/blob/main/docs/开发文档/03_能力面接口契约.yaml); design document [`01_设计方案.md` → design.md](./design.md); caller documentation see [`../用户文档/` → ../user/llm-guide.md](../user/llm-guide.md) |
| Version | V1.0 (2026-08-31) |

---

## 1. What you are integrating with

```
Caller ──▶ token-gateway ──capability-face calls──▶ Your backend (you, the reader)
                        (the gateway is the client, you are the server)
```

- The gateway calls backends via **seven capability faces**: token validation / routing / billing / content moderation / access logging / audit / model catalog.
- You **implement a subset as needed**: only implement the capability faces you declare as enabled; the remaining faces are disabled in the gateway yml or pointed at other services.
- Onboarding = implementing the HTTP endpoints of the Capability-Face Interface Contract (or consuming the log MQ per §5). **You write no gateway-side code, and any language is fine.**

## 2. Onboarding model: choose your capability-face subset

| What you want | Must implement | Gateway-side config |
|---|---|---|
| Minimal onboarding (proxy + self-managed billing) | token-validate + route + models | `billing: passthrough` |
| Gateway saga billing | The above + three billing endpoints | `billing: direct` |
| Content moderation | moderation/scan | `moderation.enabled: true` |
| Log aggregation | access-log/record **or** consume the log MQ | `access-log.transport: rpc / mq` |
| Audit aggregation | audit/record | audit face config |
| Async tasks (backend holds task state itself) | task/create + task/poll | face=task + delegation form (see §4.8; the dumb upstream task API **does not need** to be implemented) |

Do not enable faces you have not implemented in the gateway yml (the gateway validates the intersection of switches and capabilities at startup; misconfiguration causes a hard startup failure).

## 3. General conventions (mandatory for every endpoint)

### 3.1 Envelope and success/failure determination

- All responses: **HTTP 200 + a 6-field envelope**: `{code, message, data, error, trace_id, timestamp}`; `code=0` means success.
- Business failures also return HTTP 200 + a non-zero code in the envelope (e.g., invalid credential = `{"code":10202,...}`).
- **HTTP 5xx / timeout = infrastructure failure**; the gateway will retry or trigger saga compensation (refund). Do not use 5xx to express business errors.

### 3.2 Error codes (you return to the gateway → the gateway passes through to the caller)

| code | Scenario | Mandatory face |
|---|---|---|
| 10200/10202 | Credential expired/invalid | token-validate |
| 10400 | No available channel for model / model does not exist | route |
| 10617 | Insufficient balance | billing/pre-consume |
| 10100~10106 | Request parameter errors | All |

### 3.3 Tracing and idempotency

- The request header `X-Trace-Id` must be passed through, and the same value must be returned in the envelope `trace_id` field — troubleshooting depends entirely on it.
- **settle / refund / access-log / audit must be idempotent**: repeated requests with the same `pre_consume_id` / `(trace_id, ts)` return the first result; no double charge or double refund.
- Timeout budgets (gateway-side; your service should be faster): route 3s / moderation 2s / billing 5s / log and audit are async with no limit.

### 3.4 Authentication (three ways the gateway calls you, configured via gateway yml `auth:`)

> The authoritative security specification is the Backend Integration Security Contract (04 doc): scenario tiers / per-request signing / credential rotation / acceptance checklist. This section is the verification summary.

| auth | What the gateway sends | Recommendation | Your verification method |
|---|---|---|---|
| `jwt` | `Authorization: Bearer <HS256 JWT>` | **Recommended (default)** | Three-step verification: ① verify the HS256 signature with the shared secret ② check `exp` is not expired ③ check `iss`/`caller` match the convention (production internal-token claims: `iss=mmagix, caller=gateway-webflux, tenant_id, user_id`); **if the backend rotates the secret, it must notify the gateway to re-mint in sync**. For cross-segment scenarios the gateway may upgrade to per-request signing (HMAC four-header, replay-proof) |
| `key` | `X-API-Key: <key>` | Acceptable (intranet only) | Constant-time comparison against a static key (do not use plain equals, to avoid timing side channels) |
| `none` | No header | Restricted | **localhost/sidecar same-host isolation only**; whitelist/network policy is defense-in-depth and cannot stop lateral movement within the segment — do not rely on it alone |

On verification failure return HTTP 401 + envelope (code=10300); the gateway treats this as a configuration error alert rather than a caller error. The gateway warns at startup when `auth=none` is paired with a non-localhost url.

## 4. Capability-face contract, face by face

> For the complete field-level definitions see `03_能力面接口契约.yaml` in the same directory ([link](https://github.com/funcommons/token-gateway/blob/main/docs/开发文档/03_能力面接口契约.yaml)); only the semantic essentials are listed here.

### 4.1 token-validate — `POST /gw/v1/token/validate`

- In: `{credential}` (the caller's raw credential, forwarded verbatim by the gateway).
- Out: `data: {tenant_id, user_id, active, masked_credential}`.
- Invalid/expired → envelope 10202/10200 (**not 5xx**).
- High-frequency hot face: local cache recommended (60s TTL) + instant disable support (trade-off between receiving disable notifications and a short TTL).

### 4.2 route — `POST /gw/v1/route/resolve`

- In: `{model, tenant_id, request_id}`.
- Out: `data: {candidates: [...]}`; each candidate `{base_url, credential, credential_type, protocol, model_mapping, priority}`; the gateway fails over by priority.
- **Return the upstream outbound credential desensitized** (if plaintext pass-through is acceptable, mark `credential_type: plain` and let the gateway decide whether to mask it in logs); empty `model_mapping` = pass the same model name straight through.
- No available channel → envelope 10400.

### 4.3 billing — three endpoints (the saga trio, when `billing: direct`)

| Endpoint | Semantics | Idempotency key |
|---|---|---|
| `POST /gw/v1/billing/pre-consume` | Pre-charge: return **10617** on insufficient balance; on success return `{pre_consume_id, amount}` | trace_id |
| `POST /gw/v1/billing/settle` | Settlement: settle the difference against actual usage; **must be idempotent** | pre_consume_id |
| `POST /gw/v1/billing/refund` | Compensation: fully refund the pre-charge (all upstreams failed / timed out); **must be idempotent** | pre_consume_id |

Saga guarantee: for each trace, exactly pre-charge − refund = actual consumption; your reconciliation basis = replay by events.

### 4.4 moderation — `POST /gw/v1/moderation/scan`

- In: `{content, content_type}`; out: `data: {action: PASS|BLOCK|SANITIZE, sanitized_content, reason}`.
- If your service times out / returns 5xx → the gateway decides whether to allow or block per its `fail-open` config — **it will not retry and slow down the main path**.
- Samples of BLOCK/SANITIZE decisions are recommended to be sent back to the audit face for traceability.

### 4.5 access-log — either RPC or MQ

- **rpc**: `POST /gw/v1/access-log/record`; the gateway pushes asynchronously in batches (does not block the main path; failures are quiet + alerted).
- **mq**: see §5; you act as the consumer.
- Core fields of a log entry: `trace_id, tenant_id, user_id, model, path, prompt_tokens, completion_tokens, cached_tokens, credit, latency_ms, status, ts`.

### 4.6 audit — `POST /gw/v1/audit/record`

- Outbound delivery of security/management events (tenant/key lifecycle, moderation blocks, configuration changes); failures do not block the main path.
- Your side should persist append-only (no physical deletion); retention period per compliance requirements.

### 4.7 models — `GET /gw/v1/models`

- Out: `data: {object: "list", data: [{id, owned_by}]}`; `id` is the model name the caller requests, and the input for routing binding with wildcard matching.

### 4.8 Task delegation face (optional — `POST /gw/v1/task/create` + `POST /gw/v1/task/poll`)

There are two forms in the task domain; **most backends implement neither**:

| Form | Who holds task state | What you need to implement |
|---|---|---|
| **Gateway local state machine** (default) | The gateway (face-task): after resolving your upstream via the route face, the gateway directly drives your upstream's create/poll, and takes care of terminal-state guarantees / notify / resource proxying | No task face — your upstream only needs to provide a "dumb" task API (POST create + GET query) |
| **Delegation face** | Your backend (you are the task platform yourself) | The two endpoints in this section |

Delegation face semantics:

- `task/create`: in `{task_no, model, params, notify_url, trace_id}` (task_no is generated by the gateway and dispatched after the pre-charge); out `data: {accepted, status}`. On creation failure return a business code (e.g., 10004); the gateway will fully refund and mark the task FAILED.
- `task/poll`: in `{task_no}`; out `data: {status: PENDING|RUNNING|SUCCEEDED|FAILED|EXPIRED, result: {resources[], usage}}`.
- **Idempotency**: repeated create with the same task_no returns the first result; poll is read-only.
- Resource proxying and the notify callback are always handled by the gateway (sig signature + upstream URL not passed through — semantics unchanged); your result.resources just returns the original resource URLs.

## 5. MQ log onboarding (`transport: mq`)

The gateway asynchronously delivers access logs to the MQ; you persist them as a consumer:

| Item | Convention |
|---|---|
| MQ type | `kafka` / `rocketmq`, pick one (gateway yml `access-log.mq.type`) |
| topic | `token-gateway-access-log` (configurable in yml) |
| Partition/order key | `trace_id` (logs of the same request are ordered; no guarantee across requests) |
| Delivery semantics | **at-least-once** — you will receive duplicate messages; dedupe idempotently by `(trace_id, ts)` |
| Message body | JSON, same structure as the `data` of `access-log/record` + `gw_sent_at` (gateway delivery timestamp) |
| Loss window | On broker failure the gateway buffers locally and retries; beyond the limit it falls back to a local file — reconciliation is based on the gateway-side in-memory count + local fallback file |

Consumer-side pseudocode:

```
for msg in consumer:
    entry = json.loads(msg.value)
    if not exists(trace_id=entry.trace_id and ts=entry.ts):   # idempotent dedup
        insert access_log(entry)
    ack()
```

## 6. Minimal onboarding walkthrough (Go example, three endpoints)

```go
package main

import (
    "encoding/json"
    "net/http"
)

func env(code int, msg string, data any) map[string]any {
    return map[string]any{"code": code, "message": msg, "data": data,
        "error": nil, "trace_id": "", "timestamp": nowMs()}
}

func main() {
    mux := http.NewServeMux()

    mux.HandleFunc("/gw/v1/token/validate", func(w http.ResponseWriter, r *http.Request) {
        // Production: verify X-API-Key / JWT; here we demo the minimal shape
        write(w, env(0, "success", map[string]any{
            "tenant_id": "9001", "user_id": "u1", "active": true, "masked_credential": "sk-****abcd"}))
    })

    mux.HandleFunc("/gw/v1/route/resolve", func(w http.ResponseWriter, r *http.Request) {
        write(w, env(0, "success", map[string]any{
            "candidates": []any{map[string]any{
                "base_url": "https://api.acme.io", "credential": "acme-key",
                "credential_type": "plain", "protocol": "openai",
                "model_mapping": nil, "priority": 1}}}))
    })

    mux.HandleFunc("/gw/v1/models", func(w http.ResponseWriter, r *http.Request) {
        write(w, env(0, "success", map[string]any{
            "object": "list", "data": []any{map[string]any{"id": "acme-mini", "owned_by": "acme"}}}))
    })

    http.ListenAndServe(":9500", mux)
}

func write(w http.ResponseWriter, body map[string]any) {
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(body)
}
```

Joint-debugging sequence (gateway yml points at `http://localhost:9500`, `adapter: openapi`, `billing: passthrough`):

```bash
curl -s http://localhost:9401/v1/models -H "Authorization: Bearer test"      # goes through your models face
curl -s http://localhost:9401/v1/chat/completions \
  -H "Authorization: Bearer test" -H "Content-Type: application/json" \
  -d '{"model":"acme-mini","messages":[{"role":"user","content":"hi"}]}'     # goes through validate + route → straight through to your upstream
```

## 7. Acceptance checklist

- [ ] All responses HTTP 200 + envelope; business errors expressed via code, never 5xx
- [ ] `X-Trace-Id` passed through + the same value returned in envelope `trace_id`
- [ ] settle/refund/logs idempotent (repeated requests yield the same result)
- [ ] Authentication verified per §3.4 (key/token constant-time comparison; jwt signature + exp + iss)
- [ ] token-validate hot face has a cache strategy and disable takes effect immediately
- [ ] route candidates sorted by priority; envelope 10400 when no candidates
- [ ] MQ consumption (if chosen) idempotent dedup + consumer lag monitoring
- [ ] Joint debugging with the gateway: verify each of the three vectors — validate failure / no route / insufficient balance

## 8. FAQ

| Symptom | Root cause | Resolution |
|---|---|---|
| Gateway startup failure reporting missing capability | A face enabled in yml is not implemented by you | Disable that face or implement it (§2 subset table) |
| settle receives duplicate requests | Gateway timeout retry | Normal — return the first result idempotently by pre_consume_id |
| Your 5xx causes the caller to receive 10004 | The gateway judges infrastructure failure as all-upstreams-failed | Always express business errors via the envelope code (§3.1) |
| Duplicate MQ logs | at-least-once semantics | Dedupe by (trace_id, ts) (§5) |
| All 401s from the gateway after rotating the JWT secret | Secrets out of sync on the two ends | Backend/gateway re-mint in sync (§3.4) |
