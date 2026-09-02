# Task Face (face-task) Development Handbook

| Item | Content |
|---|---|
| Document | face-task implementation handbook: module/component breakdown, lotask4j integration contract, configuration model, M2.5 task breakdown |
| Status | **Execution-level basis** for M2.5 development; the plan-level authority is `05_任务面lotask4j托管方案.md` → `./task-lotask4j-hosting.md` (V1.1) — on conflict, 05 wins and this doc is updated |
| Companion | 05 (plan authority); `../user/task-guide.md` / `08_任务面API契约.yaml` (caller contract); `./backend-security-contract.md` (auth three modes) |
| Version | V1.0 (2026-09-01) |

---

## 1. Deliverables Overview

M2.5 delivers three deployment units plus one script asset class:

| Deliverable | Module | Deployment form |
|---|---|---|
| face-task data plane | `face-task` (existing placeholder module, filled in) | `token-gateway.face=task` group, no DB, resource cache disk mounted |
| **task-worker (new module)** | `task-worker` (new Maven module alongside face-task) | Independent process, independently scalable; pulls/reports via the lotask4j worker API; loads `scripts/` Groovy scripts |
| Script assets | repo-root `scripts/<modality>/<upstream>-v<n>.groovy` + `fixtures/` | Shipped with repo CI/releases; loaded by the Worker from the deployment package |
| SPI additions | `gateway-spi` | TaskFaceConfig extension (§4); TaskClient delegation surface unchanged (alternative form retained) |

## 2. face-task Package Structure (`fun.commons.tokengateway.task.*`)

| Package | Class | Responsibility |
|---|---|---|
| `task.controller` | `TaskController` | Four-modality create/poll endpoints (contract = `04` yaml; envelope uses ApiCode business codes) |
| | `ResourceProxyController` | `GET /v1/resources/{task_no}/{index}?exp=&sig=` streaming origin fetch + cache disk |
| | `LotaskWebhookController` | `POST /internal/lotask/webhook`: three-header signature-verifying receiver for terminal events (§3.3) |
| `task.relay` | `TaskRelayOrchestrator` | create pipeline: key validation (control plane) → route resolve pricing (control plane) → full pre-charge → Redis idempotency dedup → lotask4j submit (route snapshot encrypted into payload) → full refund on failure |
| `task.lotask` | `LotaskTaskClient` | lotask4j client API wrapper (§3.1): submit/get/cancel; jwt + HMAC four headers (reusing core signing capability) |
| | `RouteSnapshotCipher` | Route snapshot AES-GCM encrypt/decrypt (key held only by the Worker and this class, injected from environment) |
| `task.billing` | `TaskBillingSaga` | Pre-charge/refund/pre-charge-to-consumption; all idempotent by `pre_consume_id` |
| `task.notify` | `NotifyDispatcher` | notify_url callback (X-THMP-Signature + 1m/10m/1h backoff) |
| | `WebhookVerifier` | Three-header checks: constant-time signature verify + ±5min timestamp window + Event-Id dedup (reusing `RedisIdempotencyStore`); unsigned/invalid → verify-then-act requery |
| `task.schedule` | `TimeoutClockJob` | Timeout clock: scan in-flight tasks by task_type deadline → requery lotask4j terminal state at deadline → EXPIRED mapping + refund (R6 gateway-side compensation) |
| | `ReconcileJob` | Reconciliation fallback: find unclosed pre-charges by pre_consume_id → requery lotask4j terminal state and compensate (orphan pre-charge release) |
| `task.state` | `TaskStateMapper` | lotask4j state → gateway five-state mapping (05 §6) |
| `task.config` | `TaskFaceConfiguration` | face-task assembly (under the FaceTaskAssembly scan) |

**Dependency red line**: face-task carries no JDBC; lotask4j is the only state store; Redis holds only idempotency dedup / timeout-clock deadline indexes (rebuildable, not a state source of truth).

## 3. lotask4j Integration Contract (platform V4+ verified endpoints)

### 3.1 client domain (consumed by the gateway LotaskTaskClient, `@TenantDomain` + `@RequiresToken("TENANT")`)

| Purpose | Endpoint | Notes |
|---|---|---|
| Submit | `POST /api/v1/client/tasks/submit` | body: `{type, payload, idempotencyKey, callbackUrl, priority}`; returns `{id}` (OpenID-obfuscated string); idempotency key unique within partition + gateway-side Redis dedup (R2 compensation) |
| Query | `GET /api/v1/client/tasks/{id}` | State/progress/result; the poll endpoint only hits this; terminal responses idempotent |
| Cancel | `POST /api/v1/client/tasks/{id}/cancel` | Sends a cancel signal; the Worker detects it in its loop |
| Auth | jwt (recommended mode) + HMAC four headers on writes | Contract same as `./backend-security-contract.md` §4; submit is a POST-only sub-path (platform designed for signature scoping) |

`submit.callbackUrl` = the gateway's `POST /internal/lotask/webhook` (terminal events delivered via outbox with exponential-backoff redelivery).

### 3.2 worker domain (consumed by task-worker, `@TenantDomain`, each tenant consumes only its own tasks)

| Purpose | Endpoint | Rate limit | Notes |
|---|---|---|---|
| Claim | `POST /api/v1/worker/tasks/poll` | 600/min | body: `{taskType, workerId}`; returns the task + lease/fencing token |
| Cancel detection | `GET /api/v1/worker/tasks/{id}/status` | 600/min | Worker checks for CANCELLING inside its loop |
| Progress | `POST /api/v1/worker/tasks/{id}/progress` | 1200/min | Reports upstream task ID / progress hints |
| Result | `POST /api/v1/worker/tasks/{id}/result` | 600/min | Terminal report triggers state machine CAS + outbox |

### 3.3 webhook inbound (platform → gateway)

The platform delivers three headers (key = the gateway tenant's `tenant_secret`, injected from environment):

```
X-ASTS-Event-Id:  {outbox row id}   — idempotent dedup key (retries reuse the same id)
X-ASTS-Timestamp: {epoch millis}    — reject if |now - ts| > 5min
X-ASTS-Signature: Base64(HmacSHA256(tenant_secret, timestamp + "\n" + rawBody))
```

Receiver rules (`WebhookVerifier`): ① all three headers present and signature valid → process; ② missing/invalid → **do not reject** — verify-then-act: requery `GET /api/v1/client/tasks/{id}` to confirm the terminal state (the platform silently degrades to unsigned delivery for tasks without tenant attribution); ③ Event-Id already seen → idempotent skip; ④ after key rotation, accept both keys within grace-hours. Actions per 05 §5.2: SUCCESS → convert resources to sig URLs + pre-charge to consumption; FAILED/CANCELLED → refund → notify; refund before notify.

## 4. Configuration Model (TaskFaceConfig Extension)

```yaml
token-gateway:
  face: task
  task:
    expire-scan: 24h                # existing: default timeout window (overridable per task_type)
    resource-cache-dir: /data/tgw-cache   # existing
    resource-sign-key: ${TGW_RESOURCE_SIGN_KEY}   # existing
    notify-retry: [1m, 10m, 1h]     # existing
    lotask:
      url: http://lotask4j:8080
      auth: jwt                     # one of the three modes; jwt recommended
      jwt-secret: ${LOTASK_JWT_SECRET}
      sign-key: ${LOTASK_SIGN_KEY}  # HMAC four headers on writes
      tenant-secret: ${LOTASK_TENANT_SECRET}   # webhook verification (= platform-side tenant_secret)
      connect-timeout: 3s
      read-timeout: 5s
    timeouts:                       # timeout clock: per-task_type override of the default window
      video: 2h
      image: 30m
```

Worker-specific config (task-worker module): `lotask.url/jwt-secret`, `worker.id`, `worker.poll-interval`, `worker.scripts-dir`, `snapshot-cipher-key` (= RouteSnapshotCipher key), upstream egress whitelist.

CapabilityValidator addition: with `face=task`, validate `lotask.url/tenant-secret/resource-sign-key` non-empty (missing → fail-fast; auth=none + non-localhost → warning, following existing rules).

## 5. M2.5 Task Breakdown

| Sub-phase | Content | Exit |
|---|---|---|
| **M2.5a Data-plane skeleton** | TaskFaceConfig extension + LotaskTaskClient (jwt+HMAC) + TaskController four-modality create/poll + TaskRelayOrchestrator (pre-charge/refund saga) + TaskStateMapper; poll queries lotask4j directly | create→poll works against a mocked lotask4j (or test instance); two negative-path unit tests: insufficient balance 10617 / submit failure refund |
| **M2.5b Worker + scripts** | task-worker module (poll/progress/result loop + lease renewal) + Groovy sandbox (blacklist/http binding/timeout cap) + ScriptLoader + `GroovyScriptTestHarness` + first real script (pick one video upstream) + dry-run endpoint | Script unit tests green in repo CI; end-to-end create→Worker execution→SUCCESS against a test instance |
| **M2.5c Terminal closure** | LotaskWebhookController + WebhookVerifier (verify/requery/dedup/dual-key) + TimeoutClockJob + ReconcileJob + NotifyDispatcher + ResourceProxyController | Four-modality smoke + webhook verification/unsigned-requery/redelivery-dedup drills + item-by-item degradation matrix drills (05 §11) + zero reconciliation discrepancy |

Ordering: a ∥ b can run in parallel (contract first); c depends on a+b.

## 6. Test Strategy

| Layer | Form |
|---|---|
| Unit tests | saga branches (10617 / submit-failure refund / refund idempotence), state mapping, all webhook three-header combinations, RouteSnapshotCipher round-trip |
| Contract tests | Reuse the existing Testcontainers Redis pattern (idempotency dedup); WireMock-recorded contracts for the lotask4j client/worker APIs |
| Script tests | `scripts/**/fixtures/*.json` + GroovyScriptTestHarness, in repo CI |
| Assembly tests | Follow the FaceTaskActivationTest pattern: face=task has task beans and no LLM beans |

## 7. Open Items (decide before development)

| # | Item | Suggested default |
|---|---|---|
| 1 | lotask4j tenant onboarding flow (create tenant / obtain tenant_secret / worker token) is a platform-ops handbook item, to be added on the lotask4j side | Hand-provision on a test instance first; write the runbook later |
| 2 | ~~Which upstream is first~~ **Resolved (2026-09-02): token-mock is the default integration/smoke upstream** (`scripts/video/token-mock-v1.groovy` landed; real vendor scripts follow its shape); token-mock admin supports forceStatus/failureRate fault drills | First production vendor TBD |
| 3 | Whether task-worker merges into the app module as `face=worker` | **Independent module with its own main** (Worker pool scaling cadence differs from the gateway, 05 §3) |
