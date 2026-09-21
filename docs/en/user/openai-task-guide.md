# OpenAI Task Face Onboarding Guide

| Item | Content |
|---|---|
| Document | OpenAI-protocol task face caller guide (async image generation background mode / async video sora job mode) |
| Prerequisites | [Product Overview](./overview.md) · [Quickstart](./quickstart.md) · [Conventions](./conventions.md) (auth / error codes / rate limit / idempotency) |
| Companion | Gateway-native protocol over the same engine: [OneToken Task Face Guide](./task-guide.md); LLM face: [LLM Face Guide](./llm-guide.md) |
| Version | V1.1 (2026-09-21, sora `seconds`→`duration` pricing alias and known limitations, issue #36); V1.0 (2026-09-14, issue #19) |

---

## 1. Positioning: One Task Engine, Two Protocol Shapes

The task-face execution engine (route pricing → full pre-deduction → Worker execution → terminal refund / resource proxy) exposes **two peer protocols** to callers:

- **OneToken protocol** (`/v1/onetoken/*`): the gateway-native task protocol, all four modalities
- **OpenAI protocol** (this guide, official `/v1/*` paths): OpenAI job shape — **usable directly from the OpenAI SDK**, no gateway-specific awareness required

Both protocols create the same task: the OpenAI `id` **is** the OneToken `task_no`; either protocol can poll/fetch resources.

> **Deployment boundary**: the video endpoints (`/v1/videos*`) work with `face=task|all`; the OpenAI-protocol **async image endpoints are only served by `face=task` deployments** (with `face=all`, `POST /v1/images/generations` belongs to the LLM face as synchronous pass-through — use the OneToken protocol or a dedicated task deployment for async images).

## 2. Endpoint Overview (OpenAI Protocol)

| # | Endpoint | Method | Description | Deployment |
|---|---|---|---|---|
| 1 | `/v1/videos` | POST | Create a video task (sora shape) → `video_generation` job | task/all |
| 2 | `/v1/videos/{id}` | GET | Poll the job | task/all |
| 3 | `/v1/videos/{id}/content` | GET | After completion, 307 to the signed video proxy URL | task/all |
| 4 | `/v1/images/generations` + `background:true` | POST | Create an async image task → `image_generation` job | **task only** |
| 5 | `/v1/images/generations` (background omitted) | POST | Synchronous wrapper (60s window, degrades to PROCESSING, see §5) | **task only** |
| 6 | `/v1/images/generations/{id}` | GET | Poll the image job | **task only** |

Dual auth headers (`Authorization: Bearer` preferred / `x-api-key`); create endpoints accept `Idempotency-Key` — same as [Conventions](./conventions.md).

## 3. Async Video Generation (sora job shape)

```bash
# ① Create (returns a job object synchronously; full pre-deduction, insufficient balance → 10617, no task)
curl -s http://localhost:9401/v1/videos \
  -H "Authorization: Bearer <credential>" -H "Content-Type: application/json" \
  -d '{"model":"sora-2","prompt":"a cat skateboarding through a neon city","seconds":"8","size":"1280x720"}'
# → {"id":"T20260914...","object":"video_generation","status":"queued","created_at":1760000000}

# ② Poll (3~5s cadence; terminal polls are idempotent)
curl -s http://localhost:9401/v1/videos/T20260914... -H "Authorization: Bearer <credential>"
# → {"id":"...","object":"video_generation","status":"in_progress"}
# terminal: status=completed / failed (with error{code,message})

# ③ Fetch the video (307 redirect to the signed proxy URL after completion, 24h validity; follow redirects)
curl -sL http://localhost:9401/v1/videos/T20260914.../content -H "Authorization: Bearer <credential>" -o out.mp4
```

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `model` / `prompt` | string | yes | Model name (route pricing key) / prompt |
| `seconds` | string | no | Duration (passed through, e.g. `"4"`, `"8"`; pricing-dimension mapping below) |
| `size` | string | no | Resolution (e.g. `"1280x720"`) |
| `notify_url` | string | no | Gateway extension: terminal callback (same signed-notify semantics as the OneToken protocol) |

Status mapping: `queued` (PENDING) → `in_progress` (RUNNING) → `completed` / `failed` (FAILED and EXPIRED collapse into failed with `error`; EXPIRED has been fully refunded).

**Pricing dimensions (sora shape, issue #36)**:

- `seconds` is alias-mapped into the pricing dimension `params.duration` for route pricing; an explicit `duration` takes precedence (`seconds` only fills in when `duration` is absent), and the `seconds` key itself never enters the pricing channel. The execution payload is unchanged — the Worker still receives `seconds` verbatim.
- Known limitation: `size` (e.g. `1280x720`) is **not mapped** — it never enters composite `ratio|resolution` tiers; sora-shape pricing only applies to the duration dimension or wildcard / flat-rate configurations.
- With no duration at all (neither `seconds` nor an explicit `duration`), a PER_SECOND configuration is rejected explicitly: capability-face 10608 → HTTP 500 `pricing_unavailable`.
- The token-route branch (`adapter=tokengo/openapi`) does not push dims pricing parameters yet.

## 4. Async Image Generation (background mode)

```bash
# ① Create (background:true = official OpenAI async semantics)
curl -s http://localhost:9401/v1/images/generations \
  -H "Authorization: Bearer <credential>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-image-2","prompt":"a cat wearing sunglasses","size":"1024x1024","background":true}'
# → {"id":"T20260914...","object":"image_generation","status":"queued","created_at":1760000000}

# ② Poll until completed
curl -s http://localhost:9401/v1/images/generations/T20260914... -H "Authorization: Bearer <credential>"
```

Completed response (OpenAI images background shape; `url` is a gateway-signed proxy URL, 24h validity):

```json
{
  "id": "T20260914...",
  "object": "image_generation",
  "status": "completed",
  "output": [
    { "content": [ { "type": "output_image", "image_url": { "url": "/v1/resources/T20260914.../0?exp=...&sig=..." } } ] }
  ]
}
```

Parameters match the synchronous wrapper (`model`/`prompt` required, `size`/`ratio`/`resolution` passed through, **`n` supports only 1**).

## 5. Synchronous Image Generation (background omitted)

`POST /v1/images/generations` without `background` (or `false`) takes the **synchronous wrapper**: it creates a task internally, polls to a terminal state and returns once — semantics identical to `/v1/onetoken/images/sync` (success `{created, data:[{url}]}`; failure 502 already refunded; 60s window elapsing degrades to `{status:"PROCESSING", task_no, poll_url}` for continued async polling). See [OneToken Task Guide §2.1](./task-guide.md).

## 6. Semantics (Shared with the OneToken Protocol)

| Item | Semantics |
|---|---|
| Billing | **Full pre-deduction on create** (priced by the routed model); FAILED/EXPIRED auto-refunded in full; completed is not refunded |
| Task id | The OpenAI `id` = the OneToken `task_no` (starts with `T`); the two protocols interoperate for polling |
| Resources | **Upstream raw URLs are never passed through**; always gateway-signed proxy URLs (24h exp+sig) |
| Timeout | Timeout clock expiry → EXPIRED → surfaced as `failed` + TIMEOUT error + refunded |
| Idempotency | Create endpoints accept `Idempotency-Key`; terminal polls are idempotent |

## 7. Acceptance Checklist

- [ ] `background:true` goes async (get a job id and poll); omitted goes sync (branch on PROCESSING after the 60s window)
- [ ] Stop polling at a terminal state; on `failed` read `error.message` (EXPIRED already refunded)
- [ ] Fetch video via `/content` 307; image `output[].content[].image_url.url` — after expiry (24h) re-query the task
- [ ] Record `X-Trace-Id` for troubleshooting; send `Idempotency-Key` on create calls
