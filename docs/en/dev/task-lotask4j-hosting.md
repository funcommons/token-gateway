# Task Surface lotask4j Hosting Plan

| Item | Content |
|---|---|
| Document | Task surface task-state hosting plan (lotask4j hosting + platform-mediated execution + zero-modification onboarding checklist) |
| Status | **Authoritative document** for task surface implementation; supersedes the original design doc's M2.5 "THMP task domain porting" approach (2026-09-01 decision) |
| Companion | Design doc `01_设计方案.md` → `./design.md` §6.4/§7; task surface onboarding manual `../user/task-guide.md`; security contract `./backend-security-contract.md` |
| Version | V1.1 (2026-09-01: lotask4j hosts task state; revised for the lotask4j V4 tenant-isolation + webhook-signing upgrade, the zero-modification path, and scripts living in the gateway repo) |
| Platform prerequisite | **lotask4j V4+** (tenant isolation RLS + three-domain authz + webhook HMAC signing built in, after commit `9c23025`) |

---

## 1. Decision

| Decision | Content |
|---|---|
| Task state hosting | **Hosted by lotask4j (ASTS async slow-task platform)**: task records, state machine, retries, zombie reaping, and scheduling fallback all reuse platform capabilities |
| Upstream integration form | **Platform mediation**: a **self-written Worker** (executor on the token-gateway side, pulling/reporting via the lotask4j worker API) executes upstream calls (create/poll/result); the gateway **does not directly call upstream to execute tasks** |
| What the gateway keeps | caller four-modality endpoints, billing saga (route pricing first → full pre-charge → refund on terminal state), notify (HMAC + backoff), resource proxy (sig capability credential) |
| Upstream adaptation method | **Groovy scripts** (one adaptation script per task type, three hooks); onboarding a new upstream requires zero releases (§9); the script source of truth lives in **the token-gateway repo `scripts/`**, and the lotask4j platform is fully unaware of scripts |
| lotask4j modification strategy | **Zero-modification onboarding** (§10): R1 tenant isolation / R4 webhook signing are built into the platform; R2/R6/R7 are compensated on the gateway side; the R8 platform-side script executor is cancelled (Worker is self-written); only R5 manual state change remains as an optional increment |
| Data consistency | Task state is **written solely to lotask4j**; billing state lives in the backend billing surface; linked via `task_no + pre_consume_id`; **no dual writes, no shared database** |
| Alternative form | For private-deployment/no-platform scenarios, keep the self-owned DB delegation surface as an alternative (TaskClient SPI is frozen; switching is an adapter decision, not an architectural fork). **retask4j evaluation verdict**: a Redis queue framework with no durable task table/query/admin plane — unsuitable for state hosting; only a candidate execution-dispatch layer in the lightweight private-deployment combo (self-owned DB + retask4j dispatch) |

## 2. Overall Architecture

```mermaid
graph TB
    C[Caller] -->|Bearer / sk- key| GW[face-task data plane<br/>four-modality endpoints · billing saga · notify · resource proxy]
    GW -->|control-plane interface| CP[Control plane<br/>token-validate · route table]
    GW -->|billing plane| BIL[Billing backend<br/>pre-charge/settlement/refund]
    GW -->|submit/poll/cancel<br/>jwt + HMAC signature| LT[lotask4j platform V4+<br/>task table · state machine · Reaper · outbox<br/>tenant isolation RLS · three-domain authz]
    LT -->|poll/progress/result<br/>worker token| W[Self-written Worker (token-gateway repo)<br/>Groovy three hooks · sandbox · timeout clock]
    W -->|create/poll| UP[Upstream task API<br/>videos/images/audios/tts]
    LT -.->|webhook terminal events<br/>HMAC three-header signature (built-in)| GW
    C -->|GET resource sig URL| GW
    GW -->|streaming origin fetch + cache| UP
```

**Control-plane decision unchanged** (2026-09-01 decision): key validation and the route table belong to the control plane; at create time the gateway first resolves route pricing, and the **route snapshot (base_url + outbound credentials + model_mapping) is encrypted on the gateway side and delivered to the Worker with the submit payload** — pricing-time and execution-time routes are consistent, and the Worker performs no secondary routing resolution.

## 3. Direct Connection vs Mediation Ruling

| Dimension | Gateway direct connection (lotask4j as state table only) | **Platform mediation (self-written Worker executes)** ✅ |
|---|---|---|
| State writer | **Dual-writer problem**: gateway-driven state transitions coexist with the lotask4j state machine CAS, breaking lease/fencing semantics | Single writer: state transitions happen only in the lotask4j state machine (version + execution_token CAS); the Worker only reports via the worker API and never writes state directly |
| Execution guarantee | The gateway would have to build its own polling loop/retry/zombie reaping = redoing what lotask4j already solved | Reaper (lease reclaim / expired → FAILED), retry backoff, and attempt caps all reused |
| Gateway deployment | face-task instances must maintain long-polling scheduling; scaling affects in-flight tasks | The gateway has no execution loop and is purely request-driven; execution elasticity scales independently in the Worker pool |
| Verdict | Rejected | **Adopted** |

> Why a self-written Worker instead of lotask4j's built-in Worker: the script source of truth and the sandbox belong to the token-gateway repo (review/CI/canary on the gateway's cadence), keeping the lotask4j platform zero-modification and fully unaware; the Worker consumes only lotask4j's public worker pull API (poll/progress/result) and does not depend on platform internals.

## 4. Responsibility Matrix

| Responsibility | Owner | Notes |
|---|---|---|
| caller four-modality endpoints (create/poll/resource proxy) | Gateway face-task | API contract unchanged (task surface manual) |
| key validation / route table / pricing | Control plane (token-validate / route plane) | Decided and snapshotted at create time |
| Billing saga (full pre-charge / terminal-state refund) | Gateway → billing plane | Route pricing first, then pre-charge; full refund on FAILED/EXPIRED |
| Task records / state machine / retry / zombie reaping | **lotask4j** | asts_task + TaskStateMachine + TaskReaper |
| Tenant isolation | **Built into lotask4j V4** | tenant_id closed across the whole chain + PostgreSQL RLS row-level policies + client/worker/admin three-domain guards; the gateway onboards as a dedicated tenant |
| Upstream call execution | **Self-written Worker (token-gateway repo, Groovy three hooks)** | create/poll/resultMapping (§9); pulls/reports via the lotask4j worker API |
| Terminal events → trigger notify/refund | lotask4j webhook → gateway | **Platform built-in HMAC three-header signature** (X-ASTS-Event-Id/Timestamp/Signature, §8); the gateway verifies the signature with verify-then-act requery as fallback |
| notify caller callback (X-THMP-Signature + backoff) | Gateway face-task | Semantics unchanged |
| Resource proxy (sig 24h + streaming origin fetch + cache disk) | Gateway face-task | Worker reports raw resource URLs; the gateway converts them to signed proxy URLs; upstream URLs are never passed through |
| Non-standard operations features (tags/manual success/manual retry/refund entry) | **lotask4j admin plane** (optional increment R5) | The refund entry triggers gateway → billing plane refund (idempotent); all actions leave audit events; without R5, manual state changes degrade to the platform DB operations channel (§11) |

## 5. End-to-End Flows

### 5.1 create (synchronous response)

```
Caller POST /v1/videos {model, params, notify_url}
  → Gateway: control-plane key validation
  → Gateway: control-plane route resolve (route pricing first: different models have different prices)
  → Gateway: generate task_no, full pre-charge for the matched model (billing plane; insufficient balance → 10617, no task created)
  → Gateway: Redis idempotency dedup (task_no already exists → return the existing task; gateway-side compensation for R2)
  → Gateway: lotask4j submit {task_type: video, idempotency_key: task_no,
                           payload: {params, notify_url, route snapshot (AES-GCM encrypted gateway-side)}}
       ‑ idempotency_key unique within the dedicated tenant partition + gateway-side dedup ⇒ end-to-end idempotence
  → Caller ← {task_no, PENDING, poll_url}   (submit failure → full refund + 10004)
```

### 5.2 Execution and terminal state (async)

```
Self-written Worker polls a task → RUNNING (lease + fencing, lotask4j state machine CAS)
  → Groovy create hook: call upstream create per the route snapshot → upstream task ID written into progress
  → Groovy poll hook: poll upstream until terminal state (in-Worker loop, interval configured per task_type)
  → Groovy resultMapping hook: upstream result → {resources[], usage} contract
  → Worker reportResult → lotask4j persists terminal state + outbox (row written in the terminal transaction, never lost)
  → webhook (HMAC three-header signature, outbox exponential-backoff redelivery) → Gateway:
       Signature valid → process by terminal state; unsigned/invalid → verify-then-act: requery lotask4j to confirm
       SUCCESS   → convert result.resources to sig proxy URLs and store the cache index; pre-charge converts to consumption (no refund)
       FAILED    → billing plane full refund (idempotent) → notify
       Timeout   → gateway timeout clock verdict (requery terminal state at deadline) → map to EXPIRED → full refund → notify
       CANCELLED → mapped to FAILED (operations cancellation, full refund) → notify
  → notify_url callback (X-THMP-Signature; backoff on failure 1m/10m/1h)
```

### 5.3 poll (caller polling)

```
GET /v1/videos/{task_no}
  → Gateway: key validation (consumers can only query their own tasks — tenant isolation built into lotask4j V4: tenant_id end-to-end + RLS)
  → Gateway → lotask4j GET /api/v1/client/tasks/{id}
  → Return after state mapping (§6); terminal states return the stored result (sig proxy URLs) without touching upstream
```

### 5.4 Resource proxy

Consistent with the existing contract: `GET /v1/resources/{task_no}/{index}?exp=&sig=`; validate sig → streaming origin fetch + local cache disk; raw upstream URLs are never passed through.

## 6. State Machine Mapping

| Gateway (caller-visible) | lotask4j | Notes |
|---|---|---|
| PENDING | PENDING | Waiting for Worker pickup |
| RUNNING | RUNNING | Executing (including in-Worker upstream polling) |
| SUCCEEDED | SUCCESS | Terminal |
| FAILED | FAILED / CANCELLED | Terminal (operations cancellation merged into FAILED, full refund) |
| EXPIRED | FAILED / gateway timeout-clock verdict | Terminal; lotask4j mixes timeouts into FAILED with no dedicated error_code (R6 not done under zero-modification), so the **gateway-side timeout clock** decides: record a deadline at create (duration per task_type), requery the lotask4j terminal state at the deadline, then map to EXPIRED |

Model mapping: `task_no` ↔ `idempotency_key` (external idempotency key); `model+params` ↔ `payload`; `result.resources/usage` ↔ `result` JSONB (contract in §7); the 24h expiry window is carried by the gateway-side timeout clock (duration configured per modality).

## 7. Billing and Consistency

- **Correlation keys**: `task_no` (= lotask4j idempotency_key) + `pre_consume_id` (billing plane); the gateway does not maintain a task table.
- **No dual writes**: task state lives only in lotask4j; billing state lives only in the billing backend. Terminal events (webhook, after signature verification) drive refund/consumption, with redelivery on failure (outbox exponential backoff) + a gateway reconciliation task as fallback (find unclosed pre-charges by pre_consume_id → look up the lotask4j terminal state and compensate).
- **webhook loss window**: after the outbox redelivery cap is exceeded → FAILED → the gateway reconciliation fallback task (a simplified version of the original MaintenanceScheduler orphan pre-charge release semantics — only "pre-charge–terminal state" reconciliation remains; the state machine no longer has a fallback).
- **notify vs refund ordering**: refund succeeds first (idempotent), then notify, so the caller never receives FAILED before the refund has landed.

## 8. Security

| Point | Approach |
|---|---|
| Gateway → lotask4j | `jwt` (recommended style) + HMAC four-header signature on write operations (lotask4j's framework4j-signature capability; contract same as security contract §4) |
| Outbound credentials in the route snapshot | **Gateway-side field-level encryption** (zero-modification replacement for R7): AES-GCM encrypt the route snapshot before submit; the key is held only by the Worker (injected from the environment); lotask4j persists/admin/logs only ciphertext — the platform is fully unaware |
| lotask4j → gateway webhook | **Platform built-in HMAC three-header signature (V4+)**: `X-ASTS-Event-Id` (outbox row id, idempotent dedup key), `X-ASTS-Timestamp` (±5min anti-replay window), `X-ASTS-Signature` = `Base64(HmacSHA256(tenant_secret, ts + "\n" + rawBody))`, keyed by the gateway tenant's `tenant_secret` (injected from the environment). Gateway side: constant-time signature comparison + timestamp window check + Event-Id idempotent dedup (reusing `RedisIdempotencyStore`); **unsigned/invalid webhooks are not rejected outright** — tasks without tenant attribution are silently delivered unsigned by the platform, so the gateway does verify-then-act requery against lotask4j instead; after key rotation (reset-secret), accept both keys within grace-hours |
| Consumer query isolation | **Built into the platform** (V4: tenant_id closed across the whole chain + PostgreSQL RLS row-level policies + three-domain guards); the gateway onboards as a dedicated tenant. Independent instance deployment remains recommended, but the motivation is blast radius/upgrade cadence, no longer a hard security requirement |
| Caller side | Unchanged (Bearer/x-api-key, credentials never logged) |

## 9. Groovy Script Adaptation Plan

### 9.1 Why scripting

Upstream task APIs vary widely (each provider's create parameters/poll responses/resource fields differ), while the Worker skeleton (lease/fencing/retry/reporting) is agnostic to the upstream protocol. **Hardcoding in Java = one release per new upstream; Groovy scripts = zero releases for new upstreams**.

### 9.2 Script contract (one script per task type, three hooks)

```groovy
// task_type: video —— example hook signatures (Binding injects: ctx, http, log, json)
// ctx exposes: payload(Map), routeSnapshot(Map, already decrypted by the Worker), upstreamTaskId(String), progress(Map)

def create(Map ctx) {
    // Call upstream create; return [upstreamTaskId: "...", progressHint: 0]
}

def poll(Map ctx) {
    // Call upstream query; return [state: "RUNNING"|"SUCCEEDED"|"FAILED", raw: raw upstream response]
}

def resultMapping(Map ctx) {
    // Map upstream terminal raw → gateway contract
    // Return [resources: ["https://upstream/..."], usage: [seconds: 5, resolution: "720p"]]
}
```

### 9.3 Where scripts live (2026-09-01 decision: in the gateway, not the platform)

| Location | Purpose |
|---|---|
| **token-gateway repo `scripts/` directory** (e.g. `scripts/video/kling-v1.groovy`) | **Single source of truth**: reviewed, versioned, diff-able; shipped with token-gateway CI/releases on the gateway's cadence |
| Self-written Worker runtime loading | The Worker loads scripts from the deployment package's `scripts/` at startup/hot-reload (GroovyClassLoader compile cache, auto-invalidates on script version change); **the lotask4j platform is fully unaware** — no task_type_config columns needed, no platform-side script executor (former R8 cancelled) |

Why the gateway and not the platform: scripts consume the route snapshot / resource contract, which are gateway contracts; review and canary cadence follow the gateway; the platform stays zero-modification, and if the hosting backend ever changes (self-owned DB delegation surface), scripts migrate with zero changes.

### 9.4 How scripts are tested

| Layer | Form |
|---|---|
| Unit tests (token-gateway repo, with gateway CI) | `GroovyScriptTestHarness`: GroovyShell loads the script + fixtures (`scripts/video/fixtures/create-ok.json` and other upstream response samples) assert the three hooks' outputs; mock the `http` binding so no real network is touched |
| Integration (Worker test endpoint) | The Worker exposes `POST /admin/script-test/dry-run`: specify task_type + fixture or a real upstream sandbox; returns hook outputs and latency without persisting a task |
| Canary | Bind the new script to a test task_type (e.g. `video-canary`) for low-traffic validation first, then cut over to production |

### 9.5 Script security constraints

Groovy sandbox inside the Worker: blacklist (direct access to `System/Runtime/Thread/File/socket`), only allow the `http` binding (with timeouts/outbound whitelist) and the `json` utility; hard cap on script execution time; compile failure/runtime exception → task FAILED + error_code=SCRIPT_ERROR + audit event.

## 10. lotask4j Modification Checklist (zero-modification restatement, revised 2026-09-01)

Platform prerequisite **V4+** (tenant isolation RLS + three-domain authz + webhook HMAC built in). Against the original R1~R9:

| # | Original modification | Status / replacement | Verdict |
|---|---|---|---|
| R1 | Tenant/caller isolation | ✅ **Implemented by the platform**: tenant_id closed across the whole chain + PostgreSQL RLS row-level policies + client/worker/admin three-domain guards | **Cancelled**; the gateway onboards as a dedicated tenant; independent instance deployment remains recommended (blast radius/upgrade cadence, not a hard security requirement) |
| R2 | Globally unique external idempotency key | Unique within the dedicated tenant partition + **gateway-side Redis dedup** (check the task_no mapping before submit) | **Gateway-side compensation** |
| R3 | Result resource contract `{resources[], usage}` | Guaranteed by the Worker resultMapping hook (a contract convention, not a platform change) | **Worker-side guarantee** |
| R4 | webhook signing | ✅ **Implemented by the platform**: outbox delivery carries HMAC three headers (X-ASTS-Event-Id/Timestamp/Signature, keyed by tenant_secret) + exponential-backoff redelivery | **Cancelled**; the gateway verifies signatures with verify-then-act requery as fallback (§8) |
| R5 | Admin-plane manual state change / manual retry / task tags | Not implemented | **The only remaining optional increment** (P1); without it, operations state changes degrade to the platform DB operations channel (§11), with audit recorded by that channel |
| R6 | Independent timeout semantics (error_code=TIMEOUT) | **Gateway-side timeout clock**: record a deadline at create (per task_type duration), requery the lotask4j terminal state at the deadline → map to EXPIRED → refund | **Gateway-side compensation** |
| R7 | Field-level payload encryption | **Gateway-side encryption**: AES-GCM encrypt the route snapshot before submit; the key is held only by the Worker; the platform sees only ciphertext | **Gateway-side compensation** (platform fully unaware) |
| R8 | Groovy script executor (platform side) | **Self-written Worker with a built-in sandbox executor**; script source of truth in the token-gateway repo (§9.3) | **Platform modification cancelled** |
| R9 | RocketMQ events (optional) | Webhooks suffice (terminal events + outbox reliable delivery) | Remains not introduced (P2) |

**Verdict: zero-modification onboarding is shippable.** The only optional increment R5 (operations manual state change/tags) does not block M2.5.

## 11. Degradation and Availability

| Failure | Behavior |
|---|---|
| lotask4j unreachable (create) | Reject the request + full refund (failures other than 10617 → 10004); never produces "charged but no task" |
| lotask4j unreachable (poll) | Return the last cached state + a backoff hint (state unchanged, caller retries); does not touch billing |
| webhook unsigned / signature invalid | Not rejected outright: verify-then-act requery against lotask4j to confirm the terminal state, then process (Event-Id already seen → idempotent skip) |
| webhook delayed/lost | outbox backoff redelivery; after the cap, the gateway reconciliation fallback task compensates by looking up pre_consume_id |
| All Workers unavailable | Tasks pile up as PENDING (platform monitoring alert); gateway timeout clock deadline → requery terminal state → EXPIRED → refund |
| Operations manual state change (without R5) | Via the platform DB operations channel + manual audit record; the webhook after the state change still triggers refund/notify (idempotent) |
| lotask4j down long-term | Task surface fully unavailable (read degradation); the LLM surface is unaffected (the point of the independent deployment group) |

## 12. M2.5 Revision (replacing design doc §10)

| Item | Original approach (THMP porting) | New approach (lotask4j hosting) |
|---|---|---|
| State machine / task table / scheduling fallback | THMP porting + DDL #17 | **Not built** — hosted by lotask4j (**zero-modification onboarding**, V4+ platform prerequisite) |
| face-task module contents | TaskService/state machine/resource proxy/notify/MaintenanceScheduler | caller endpoints + billing saga + notify (signature-verifying receiver) + resource proxy + LotaskTaskClient + timeout-clock/reconciliation fallback; **self-written Worker + `scripts/` Groovy adaptation scripts** |
| face-task database | Dedicated task-table database | **No DB** (state in lotask4j); only the resource cache disk remains |
| Exit criteria | Four-modality create→poll→resource proxy smoke test; face=task independently deployed; billing reconciliation with zero discrepancies | Same as above + lotask4j **zero-modification onboarding acceptance** (dedicated tenant + webhook signature verification + gateway-side compensations R2/R6/R7 in effect) + webhook three-header verification and requery-fallback drills + item-by-item drills of the degradation matrix |
