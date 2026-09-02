# FAQ

## Credentials & Authentication

**401 although the credential looks valid?**
The credential was disabled on the backend side (or the tenant suspended) — disabling takes effect immediately. Check credential status with your platform operator.

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
