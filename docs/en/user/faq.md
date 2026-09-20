# FAQ

## Credentials & Authentication

**401 although the credential looks valid?**
The credential was disabled on the backend side (or the tenant suspended) — disabling takes effect immediately. Check credential status with your platform operator.
> Since v0.10.0, transient unavailability of the credential-validation service no longer returns 401 but **504 + 10003** (retryable); a persistent 401/10202 genuinely means a credential problem.

**What does 504 / 10003 mean?**
The credential-validation service was transiently unavailable (infrastructure glitch/timeout) — it does **not** mean your credential is bad. Back off and retry; if it persists, contact the platform to check control-plane availability.

**Can I pass BASE64 reference images to OneToken image generation?**
Yes — `params` is passed through untouched (base64 data URLs included); whether it works depends on the target model/worker script, so prefer URLs. Hard limit: request body ≤ **128 MB** by default (gateway inbound; tune via `GATEWAY_MAX_BODY_SIZE`); the outbound payload has no gateway-side cap (bounded by the inbound limit). Task **results** are unaffected: if the upstream returns an inline `data:...;base64` image, the resource proxy decodes and re-hosts it — callers always receive proxy URLs.

**What if I send both Bearer and x-api-key?**
Bearer wins. Send only one to avoid troubleshooting ambiguity.

## Calls & Responses

**The response has no `code` field — did it fail?**
No — success responses are passthrough shapes (no envelope). See [Conventions §2](./conventions.md): `choices`/`content`/`task_no` present ⇒ success; envelopes appear only on errors.

**10400 model not found?**
The model name matches no registered route. Check `GET /v1/models` first; an empty catalog means your tenant has no provisioned models.

**Stream broke mid-way?**
The gateway settles on estimated output already produced (the error direction never costs you extra) — just retry. Clients should handle missing `[DONE]` disconnects.

**Frequent 429?**
Your credential's window is saturated. Back off per `Retry-After`; ask the platform for a quota raise if needed.

**How do I switch to the OpenAI SDK's default error parsing?**
Ask your platform operator to set the gateway's `gateway.error-shape` to `openai`: error responses under the `/v1/chat` and `/v1/messages` prefixes become the OpenAI `{"error":{message,type,param,code}}` shape + top-level `trace_id`, directly parseable by OpenAI SDKs (shape sample and `error.type` mapping: [Conventions §4](./conventions.md)). ⚠️ Note: the 6-field envelope is the established public contract — align with **every existing integration** before switching, or it is a breaking change; the task face / internal endpoints are unaffected and always use the envelope.

**Why am I seeing capability-face codes like 4090/10402?**
When LLM-face routing or pre-deduction fails, an original capability-face code that hits the passthrough whitelist (`gateway.error-passthrough-codes`) is returned to you as-is with a semantic HTTP status. Default whitelist: 4090→403 (risk-control rejection), 10601→402 (insufficient balance), 10602→404, 10603→404, 10402→409 (state conflict); misses still follow the legacy gateway mapping (see [Conventions §3](./conventions.md)).

## Task Face

**create returned a task_no but it stays PENDING?**
Execution requires a Worker plus the adaptation script for that upstream. Ask the platform whether the upstream's script is live (Worker logs under `script.*`); long-PENDING tasks are judged EXPIRED by the timeout clock and fully refunded.

**Was I refunded after FAILED?**
Yes. Terminal events refund (idempotently) before notify; if the webhook is lost, the timeout clock / reconciliation job compensates. Duplicate refunds are prevented by `pre_consume_id` idempotence.

**Proxy URL expired?**
Proxy URLs are valid for 24h (exp+sig). GET the task again for a freshly signed URL; the task result itself stays queryable.

**Can I rely on notify only and skip polling?**
You can, but callback + polling is recommended: the callback gives you real-time delivery, polling is the fallback if callbacks are lost (after the retry tiers are exhausted, the gateway only logs).

## Troubleshooting

**What makes a good incident report?**
The `X-Trace-Id` response header (or `trace_id` in the envelope) + request time + task_no (task face). One trace_id stitches gateway and backend logs.
