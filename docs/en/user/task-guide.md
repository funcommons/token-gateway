# OneToken Task Face Onboarding Guide

> **Status: fully available end-to-end (2026-09-18).** Four-modality create/poll, billing saga
> (full pre-charge / terminal refund), terminal webhook receiver with signature verification,
> notify callbacks, resource proxy (`.{ext}` dual-form + browser-inline rendering), timeout
> clock and reconciliation fallback, and the self-written Worker (task-worker module:
> sandbox / poll loop / batch claiming) have all landed; available models per modality follow
> the platform's provisioned catalog (`GET /v1/models`). Authentication and cross-cutting
> specifications
> (rate limiting / idempotency / trace), and error codes are identical to the LLM face —
> see [LLM Face Onboarding Guide](./llm-guide.md) §3/§6/§7.

| Item | Content |
|---|---|
| Document | Task Face Onboarding Guide (videos / images / audios / tts — four asynchronous-task modalities) |
| Companion | LLM face [LLM Face Onboarding Guide](./llm-guide.md); API contract [Task Face API Contract](https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/09_任务面API契约.yaml); design proposal [Design Document](../dev/design.md) §6.4; hosting plan [Task Face lotask4j Hosting](../dev/task-lotask4j-hosting.md) |
| Version | V1.3 (2026-09-18, Worker/batch-pull landed + resource-proxy visibility & base64 semantics + 504/10003 validation semantics) |
| Implementation source | Task state hosted by the lotask4j platform (zero-modification onboarding, V4+ prerequisite) + self-written Worker with Groovy adaptation |

---

## 1. Task Face Positioning

- Deployment group `face: task | all` (mounts a resource cache disk, scales independently); shares credential/billing/moderation/logging/audit infrastructure with the LLM face.
- **Control-plane decisions**: key validation and the routing table are owned by the control plane (token-validate / route capability faces); the gateway data plane executes. Billing order = route-first pricing (different models, different prices), then full pre-deduction.
- Two forms: **lotask4j-hosted** (the default — task table / state machine / retry / zombie reaping hosted by the platform; a self-written Worker executes upstream via Groovy scripts; the upstream is a "dumb" task API) and
  **delegated face** (the backend owns task state itself and implements the `task/create` + `task/poll` capability face — see [Backend Onboarding Guide](../dev/backend-onboarding.md) §4.8; **reserved: the SPI contract exists, no implementation yet**).
- Resource proxying and notify are gateway-inherent: **upstream raw URLs are never passed through**; proxy URLs carry an exp+sig capability credential valid for 24h.

## 2. Endpoint Overview (Task Face)

| # | Endpoint | Method | Description |
|---|---|---|---|
| 1 | `/v1/onetoken/videos` · `/v1/onetoken/images` · `/v1/onetoken/audios` · `/v1/onetoken/tts` | POST | Create a task (isomorphic across the four modalities) |
| 2 | `/v1/onetoken/videos/{task_no}` (isomorphic across the four modalities) | GET | Poll task status |
| 3 | `/v1/onetoken/images/sync` | POST | Synchronous image generation wrapper (OpenAI shape; create + poll-to-terminal internally) |
| 4 | `/v1/resources/{task_no}/{index}?exp=&sig=` (or `/{index}.{ext}` dual form) | GET | Resource proxy (no credential required; sig is the capability credential; auto Content-Type for browser-inline rendering) |

> Note: task-face endpoints live under `/v1/onetoken/*` since v0.8.0 (issue #20, decoupled from OpenAI official endpoints); `/v1/onetoken/images` (async task), `/v1/onetoken/images/sync` (sync wrapper) and the LLM face's `/v1/images/generations` (sync pass-through) are three different endpoints — do not confuse them.

### 2.1 Synchronous Image Generation Wrapper (`POST /v1/onetoken/images/sync`)

An OpenAI-Images-shaped **synchronous** entry: the gateway internally creates an image task and polls it to a terminal state, so the caller gets the result in one request. Body: `model` + `prompt` (required), `size` / `ratio` / `resolution` (optional, passed through), `n` (**only 1** supported — task-face single-image semantics, >1 → 400). Supports `Idempotency-Key`.

Three outcomes:

| Case | HTTP | Response |
|---|---|---|
| Terminal success within the sync window (default 60s) | 200 | `{created, data:[{url}]}` — OpenAI images shape; `url` is a gateway-signed proxy URL (24h) |
| Upstream failure / EXPIRED | 502 | Error envelope (10004 semantics) with the upstream message; fully refunded |
| Sync window elapsed without a terminal state | 200 | `{status:"PROCESSING", task_no, poll_url}` — degrades to async: keep polling `poll_url` |

Billing/idempotency follow the standard task semantics (full pre-deduction on create, refund on failure/expiry). OpenAI's official `background:true` async mode is not yet pass-through-pollable (see issue #19).

## 3. Call Flow (Four Steps, Video as Example)

```bash
# ① Create (synchronously returns task_no: control-plane key validation → routing-table resolve
#    for pricing → full pre-deduction per the routed model; insufficient balance → 10617, no task created)
curl -s http://localhost:9401/v1/onetoken/videos \
  -H "Authorization: Bearer <credential>" -H "Content-Type: application/json" \
  -d '{"model":"vid-1.5","params":{"duration":5,"resolution":"720p"},"notify_url":"https://you/callback"}'
# → {"task_no":"T20260831...","status":"PENDING","poll_url":"/v1/onetoken/videos/T20260831..."}

# ② Poll (driven by the caller every 3–5s; terminal states are idempotent — repeated polling neither touches upstream nor triggers duplicate refunds)
curl -s http://localhost:9401/v1/onetoken/videos/T20260831... -H "Authorization: Bearer <credential>"
# → {"status":"SUCCEEDED","result":{"resources":["<proxy URL>"],"usage":{...}}}
#    A 504+10003 on create/poll = credential-validation service transiently unavailable
#    (retryable infrastructure error) — back off and retry; don't kill the task as a bad
#    key (only 401+10202 means that).

# ③ Resource fetch (result.resources contains gateway proxy URLs carrying a 24h-valid exp+sig; browsers/download clients can fetch directly without credentials)
curl -sL "<proxy URL>" -o out.mp4
```

## 4. Semantic Key Points

| Item | Semantics |
|---|---|
| Billing | Route-first pricing (priced per the resolved model), full amount **pre-deducted at creation**; FAILED / EXPIRED automatically receive a **full refund**; SUCCEEDED is not refunded (pre-deduction is the payment) — there is no usage settlement step |
| State machine | `PENDING → RUNNING → SUCCEEDED / FAILED / EXPIRED`; no terminal state after 24h → EXPIRED + full refund |
| Polling | After a terminal state, returns the stored result idempotently (`POLL_HITS=0`, upstream not touched); upstream query errors leave the status unchanged — just retry with backoff |
| notify | If `notify_url` is provided at creation, a terminal-state callback is sent; `X-THMP-Signature` (HMAC) can be used to verify it; failures are re-sent by the gateway with backoff (1m/10m/1h tiers) — callers need no fallback |
| Resource proxy | **Upstream raw URLs are never passed through**; proxy URLs expire after 24h (exp+sig) — after expiry, re-fetching the task can re-sign; expired/tampered signature → 10100, task not SUCCEEDED → 10402 |
| Moderation switch | The task face shares the `moderation.enabled` configuration with the LLM face |
| Idempotency | The create endpoint supports `Idempotency-Key` (same as LLM face §6.3) |

## 5. Gateway-Side Task Configuration (effective when face=task/all)

```yaml
token-gateway:
  face: task
  task:
    expire-scan: 24h                     # Task expiry window (timeout → EXPIRED + full refund)
    resource-cache-dir: /data/tgw-cache  # Resource proxy cache directory (disk mounted on face=task instances)
    resource-sign-key: ${TGW_RESOURCE_SIGN_KEY}
    notify-retry: 1m,10m,1h              # notify re-send backoff tiers
    submit-task-type: modality           # Dispatch granularity: modality (default, zero change)
                                         # | model = dispatch tasks by request-body model code
                                         # (required when Worker scripts claim by modelCode, else
                                         # tasks stay PENDING unclaimed; after switching, timeouts
                                         # keys must be built per model code)
```

Scheduling backstops: the timeout clock (deadline → requery terminal state → EXPIRED + refund) and pre-charge–terminal reconciliation are owned by the gateway (TimeoutClockJob / ReconcileJob); the task state machine / retry / zombie reaping are hosted by lotask4j.
