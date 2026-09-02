# Conventions

**Required reading for all callers**: authentication, response shapes, error codes, rate limiting, idempotency, tracing, timeouts. Identical across the LLM face and the task face.

## 1. Authentication

| Method | Header | Notes |
|---|---|---|
| Bearer (recommended) | `Authorization: Bearer <credential>` | OpenAI ecosystem default |
| API-Key header | `x-api-key: <credential>` | Anthropic ecosystem default; Bearer wins if both are sent |

- Credential validation is the first pipeline step; failure returns **10202 invalid token / 10200 unauthenticated** (HTTP 401 + error envelope).
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
| 10003 | 504 | Service call timeout | Retry with backoff; check max_tokens for long texts |
| 10004 | 502 | Third-party failure (all upstreams failed / task platform unreachable) | Retry with backoff; contact the platform if persistent |
| 10100~10106 | 400 | Parameter error/missing/format/range/JSON; 10106 also for content-safety rejection; resource-proxy signature expiry/tampering returns 10100 | Fix the request, do not retry |
| 10200 | 401 | Unauthenticated / token expired | Use a valid credential |
| 10202 | 401 | Invalid token | Use a valid credential |
| 10300 | 403 | Forbidden (model not provisioned) | Contact the platform |
| 10400 | 404 | Model not found / no available channel; task not found | Check model / task_no |
| 10402 | 409 | State conflict (e.g. fetching resources of a non-SUCCEEDED task) | Handle per business logic |
| 10500 | 429 | Rate limited | Back off per `Retry-After` |
| 10501 | 409 | Duplicate submission (idempotency key hit) | Use a new Idempotency-Key |
| 10617 | 402 | Insufficient balance | Top up and retry; no task is created on the task face |
| 10700 | 200 | Partial success | Handle per the `data` details |

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

## 6. Idempotency

- All write operations (every POST `/v1/**`) accept `Idempotency-Key: <uuid v4>`.
- A repeated request with the same credential + key within the window is **rejected as a duplicate** (10501) — never double-charged, never double-created.
- Recommended: always send it for non-idempotent-safe calls (image generation, task creation, …).

## 7. Timeout Budget

| Stage | Budget |
|---|---|
| Connect to upstream | 3s |
| First byte | 30s |
| Total | 300s (SSE long connections share this budget) |
| Gateway → backend capability calls | route 3s / billing 5s / moderation 2s (defaults, configurable) |

Timeouts return the 10003 envelope; total upstream failure returns 10004. Long-running task-face work is outside this budget — tasks execute asynchronously; poll or wait for the callback.
