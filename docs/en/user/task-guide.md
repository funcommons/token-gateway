# token-gateway Task Face Onboarding Guide

> **Status: partially available (2026-09-02).** Implemented gateway-side: four-modality
> create/poll endpoints, billing saga (full pre-charge / terminal refund), terminal webhook
> receiver with signature verification, notify callbacks, resource proxy, timeout clock and
> reconciliation fallback (M2.5a/c). **Upstream task execution runs on the self-written Worker
> (task-worker module landed: sandbox / poll loop / sample script)** — until the first
> real upstream's Groovy script ships, create is accepted but tasks are not executed. Authentication, cross-cutting specifications
> (rate limiting / idempotency / trace), and error codes are identical to the LLM face —
> see [LLM Face Onboarding Guide](./llm-guide.md) §3/§6/§7.

| Item | Content |
|---|---|
| Document | Task Face Onboarding Guide (videos / images / audios / tts — four asynchronous-task modalities) |
| Companion | LLM face [LLM Face Onboarding Guide](./llm-guide.md); API contract [Task Face API Contract](https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/04_任务面API契约.yaml); design proposal [Design Document](../dev/design.md) §6.4; hosting plan [Task Face lotask4j Hosting](../dev/task-lotask4j-hosting.md) |
| Version | V1.1 (2026-09-02, M2.5a/c landed; Worker execution M2.5b in progress) |
| Implementation source | Task state hosted by the lotask4j platform (zero-modification onboarding, V4+ prerequisite) + self-written Worker with Groovy adaptation |

---

## 1. Task Face Positioning

- Deployment group `face: task | all` (mounts a resource cache disk, scales independently); shares credential/billing/moderation/logging/audit infrastructure with the LLM face.
- **Control-plane decisions**: key validation and the routing table are owned by the control plane (token-validate / route capability faces); the gateway data plane executes. Billing order = route-first pricing (different models, different prices), then full pre-deduction.
- Two forms: **lotask4j-hosted** (the default — task table / state machine / retry / zombie reaping hosted by the platform; a self-written Worker executes upstream via Groovy scripts; the upstream is a "dumb" task API) and
  **delegated face** (the backend owns task state itself and implements the `task/create` + `task/poll` capability face — see [Backend Onboarding Guide](../dev/backend-onboarding.md) §4.8).
- Resource proxying and notify are gateway-inherent: **upstream raw URLs are never passed through**; proxy URLs carry an exp+sig capability credential valid for 24h.

## 2. Endpoint Overview (Task Face)

| # | Endpoint | Method | Description |
|---|---|---|---|
| 1 | `/v1/videos` · `/v1/images` · `/v1/audios` · `/v1/tts` | POST | Create a task (isomorphic across the four modalities) |
| 2 | `/v1/videos/{task_no}` (isomorphic across the four modalities) | GET | Poll task status |
| 3 | `/v1/resources/{task_no}/{index}?exp=&sig=` | GET | Resource proxy (no credential required; sig is the capability credential) |

> Note: `/v1/images` (asynchronous task) and the LLM face's `/v1/images/generations` (synchronous image generation) are two different endpoints — do not confuse them.

## 3. Call Flow (Four Steps, Video as Example)

```bash
# ① Create (synchronously returns task_no: control-plane key validation → routing-table resolve
#    for pricing → full pre-deduction per the routed model; insufficient balance → 10617, no task created)
curl -s http://localhost:9401/v1/videos \
  -H "Authorization: Bearer <credential>" -H "Content-Type: application/json" \
  -d '{"model":"vid-1.5","params":{"duration":5,"resolution":"720p"},"notify_url":"https://you/callback"}'
# → {"task_no":"T20260831...","status":"PENDING","poll_url":"/v1/videos/T20260831..."}

# ② Poll (driven by the caller every 3–5s; terminal states are idempotent — repeated polling neither touches upstream nor triggers duplicate refunds)
curl -s http://localhost:9401/v1/videos/T20260831... -H "Authorization: Bearer <credential>"
# → {"status":"SUCCEEDED","result":{"resources":["<proxy URL>"],"usage":{...}}}

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
```

Scheduling backstops: the timeout clock (deadline → requery terminal state → EXPIRED + refund) and pre-charge–terminal reconciliation are owned by the gateway (TimeoutClockJob / ReconcileJob); the task state machine / retry / zombie reaping are hosted by lotask4j.
