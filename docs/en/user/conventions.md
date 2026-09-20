# Conventions

**Required reading for all callers**: authentication, response shapes, error codes, rate limiting, idempotency, tracing, timeouts. Identical across the LLM face and the task face.

## 1. Authentication

| Method | Header | Notes |
|---|---|---|
| Bearer (recommended) | `Authorization: Bearer <credential>` | OpenAI ecosystem default |
| API-Key header | `x-api-key: <credential>` | Anthropic ecosystem default; Bearer wins if both are sent |

- Credential validation is the first pipeline step; failure returns **10202 invalid token / 10200 unauthenticated** (HTTP 401 + error envelope).
- If the credential-validation service itself is transiently unavailable (infrastructure glitch/timeout), the gateway returns **10003** (HTTP 504) — a retryable error that does **not** mean your credential is bad; back off and retry, don't treat it as an auth failure.
- Which backend validates your credential is decided by model routing — **switching backends never changes how you call**.
- Credentials never appear in gateway logs or error messages.

## 2. Response Shapes (the success/failure rule)

| Shape | HTTP | Body | Verdict |
|---|---|---|---|
| Success (LLM non-streaming) | 200 | Upstream business shape (OpenAI/Anthropic), **no envelope** | `choices`/`content`/`data` present ⇒ success |
| Success (LLM streaming) | 200 | `text/event-stream` | Parse per chunk; ends with `[DONE]` / `message_stop` |
| Success (task face) | 200 | `{task_no, status, ...}` business shape | `task_no` present ⇒ success |
| Business/system error | 4xx/5xx | 6-field error envelope | `code != 0` ⇒ failure |

Error envelope (6 fields):

```json
{
  "code": 10202,
  "message": "invalid token",
  "data": null,
  "error": [{"field": null, "code": null, "message": "invalid token", "value": null}],
  "trace_id": "c0a80101-...",
  "timestamp": 1756600000000
}
```

> **Note**: never infer failure from heuristics other than HTTP status, and never expect a `code` field on success — success is a passthrough shape.

## 3. Error Code Quick Reference

| code | HTTP | Meaning | Caller action |
|---|---|---|---|
| 10001 | 500 | System busy | Retry with backoff |
| 10003 | 504 | Service call timeout / credential-validation service temporarily unavailable | Retry with backoff; check max_tokens for long texts |
| 10004 | 502 | Third-party failure (all upstreams failed / task platform unreachable) | Retry with backoff; contact the platform if persistent |
| 10100~10106 | 400 | Parameter error/missing/format/range/JSON; 10106 also for content-safety rejection; resource-proxy signature expiry/tampering returns 10100 | Fix the request, do not retry |
| 10200 | 401 | Unauthenticated / token expired | Use a valid credential |
| 10202 | 401 | Invalid token | Use a valid credential |
| 10300 | 403 | Forbidden (model not provisioned) | Contact the platform |
| 10400 | 404 | Model not found / no available channel; task not found | Check model / task_no |
| 10402 | 409 | State conflict (e.g. fetching resources of a non-SUCCEEDED task) | Handle per business logic |
| 10500 | 429 | Rate limited | Back off per `Retry-After` |
| 10501 | 409 | Duplicate submission (idempotency key hit: in progress or not replayable) | Retry the same request and await replay; or use a new Idempotency-Key |
| 10617 | 402 | Insufficient balance | Top up and retry; no task is created on the task face |
| 10700 | 200 | Partial success | Handle per the `data` details |

> When the upstream channel returns 4xx/5xx, the gateway passes through the real upstream HTTP status and envelope code (e.g. 401→10202, 429→10500, 5xx→10001/10004); the error message carries an `upstream error HTTP_<status>` prefix — use it to tell upstream failures apart from your own credential issues (no more one-size-fits-all 502).

## 4. Tracing

- Requests may carry `X-Trace-Id` (the gateway generates one if absent); the response **always** returns `X-Trace-Id`.
- For troubleshooting, the `trace_id` stitches gateway logs ↔ backend logs ↔ access logs.

## 5. Rate Limiting

- Dimensions: per credential (apiKey) + global; fixed window.
- On limit: **HTTP 429** + envelope (10500) + response headers:

| Header | Meaning |
|---|---|
| `Retry-After` | Suggested wait in seconds (clients should back off exponentially) |
| `X-RateLimit-Limit` | Window quota |
| `X-RateLimit-Remaining` | Remaining quota |
| `X-RateLimit-Reset` | Seconds until reset |

## 6. Idempotency (Replay Semantics)

- All write operations (every POST `/v1/**`) accept `Idempotency-Key: <uuid v4>`. Scope = credential + key; window = TTL (48h by default).
- **Replay semantics** (the standard way to recover from a timeout):
  - If the first request succeeds (HTTP 2xx, non-streaming, body ≤ 1MB), the gateway caches that first response; within the TTL, resending the same credential + key **replays it verbatim** (same status / Content-Type / body) with the **`Idempotency-Replayed: true`** response header — never double-charged, never double-created; consume it exactly like a fresh response.
  - If the first request is still in flight, or its response is streaming / oversized and cannot be cached → **409 + 10501** (message says "in progress") — retry shortly; once the first response is cached, retries turn into replays.
  - If the first request failed (non-2xx or internal error) → the placeholder is **released immediately** (failures hold no key) and the same key is processed **as a new request**.
  - Streaming responses (`text/event-stream`) and responses over 1MB **do not support replay**: the placeholder is held for the TTL and same-key retries get 409 + 10501 — streaming callers should not rely on idempotent replay; reconcile on your side after timeouts.
- **The key value never leaks into downstream parameters**: the key is used only for dedup and replay, never mapped into any downstream business parameter slot (e.g. task-face workId, billing requestId); the billing requestId accepts digits only — for non-numeric keys the gateway generates a numeric request ID automatically.
- Recommended: always send it for non-idempotent-safe calls (image generation, task creation, …); on 10501, check the message — "in progress" → retry the same request shortly and await replay, otherwise use a new key.

## 7. Timeout Budget

| Stage | Budget |
|---|---|
| Connect to upstream | 3s |
| First byte | 30s |
| Total | 300s (SSE long connections share this budget) |
| Gateway → backend capability calls | route 3s / billing 5s / moderation 2s (defaults, configurable) |

Timeouts return the 10003 envelope; total upstream failure returns 10004. Long-running task-face work is outside this budget — tasks execute asynchronously; poll or wait for the callback.
