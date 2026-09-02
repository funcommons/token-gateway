# lotask4j Tenant Onboarding Handbook (token-gateway Access)

| Item | Content |
|---|---|
| Document | Runbook for onboarding token-gateway onto the lotask4j platform: tenant provisioning + smoke environment (open item #1 landed) |
| Status | Operations runbook; concrete steps for the security model in `05_任务面lotask4j托管方案.md` → `./task-lotask4j-hosting.md` §8 |
| Platform prerequisite | **lotask4j V4+** (multi-tenancy + RLS + webhook HMAC; see its repo README "Authentication & Multi-tenancy") |
| Version | V1.0 (2026-09-02) |

---

## 1. Concept Mapping

| lotask4j concept | token-gateway side | Purpose |
|---|---|---|
| Tenant `name` | — | Integrator identity (e.g. `token-gateway`) |
| Tenant `tenantSecret` (one-time plaintext) | The credential behind `LOTASK_JWT_SECRET` + `LOTASK_SIGN_KEY` + `LOTASK_TENANT_SECRET` (same value — see §4) | client_credentials login / write-endpoint HMAC / webhook verification |
| Tenant id (assigned at creation) | `LOTASK_ACCESS_KEY` | Write-endpoint `X-Access-Key` header (platform DbSecretProvider looks up the key by tenant id) |
| task_type config | Worker claim type | `video` (timeout/concurrency/retries configured platform-side) |

> Credential discipline: the `tenantSecret` plaintext appears exactly once (create/reset response) — **store it in your secret manager / environment immediately; never in git or chat logs**.

## 2. Platform-Side Steps (admin operations)

Prerequisite: lotask4j deployed (PG + Flyway V1→V4 + Redis), admin plane reachable at `http://<lotask>:8080`.

### 2.1 Platform identity login

```bash
PLATFORM_TOKEN=$(curl -s -X POST http://<lotask>:8080/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'grant_type=client_credentials&client_id=PLATFORM&client_secret=<PLATFORM_CLIENT_SECRET>' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["access_token"])')
```

### 2.2 Create the gateway tenant (secret plaintext shown once)

```bash
curl -s -X POST http://<lotask>:8080/api/v1/admin/tenants \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"token-gateway","description":"token-gateway task-face hosting"}'
# → data: { "id": 42, "tenantSecret": "<one-time plaintext>" }   ← save it!
```

### 2.3 Register task_type `video`

```bash
curl -s -X POST http://<lotask>:8080/api/v1/admin/types \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "tenantId": 42,
    "typeKey": "video",
    "name": "Video generation",
    "concurrencyLimit": 50,
    "timeoutSeconds": 7200,
    "maxRetries": 1,
    "isEnabled": true
  }'
```

> Align `timeoutSeconds` with the gateway's `token-gateway.task.timeouts.video` (gateway timeout clock as primary, platform timeout as backstop). Register image/audio/tts similarly as needed.

### 2.4 Webhook callback (terminal events → gateway)

The gateway delivers `submit.callbackUrl` per task (`LOTASK_WEBHOOK_CALLBACK_URL`); nothing to preconfigure platform-side. Just ensure the platform instance can **reach the gateway's** `/internal/lotask/webhook`, and note the delivery signature key = the tenant `tenantSecret` (built in, no extra setup).

## 3. Gateway/Worker Environment Variables (filled from 2.2/2.3)

```bash
export LOTASK_URL=http://<lotask>:8080
export LOTASK_JWT_SECRET=<tenantSecret>      # LotaskAuthSigner self-mints HS256 bearer
export LOTASK_ACCESS_KEY=42                  # tenant id (X-Access-Key)
export LOTASK_SIGN_KEY=<tenantSecret>        # HMAC four-header secret (= tenant_secret)
export LOTASK_TENANT_SECRET=<tenantSecret>   # webhook verification (same key)
export LOTASK_WEBHOOK_CALLBACK_URL=http://<gateway>:9401/internal/lotask/webhook
export TGW_SNAPSHOT_CIPHER_KEY=<base64 32B>  # shared by gateway + Worker (route snapshot)
export TGW_RESOURCE_SIGN_KEY=<random>        # resource proxy sig
export TGW_NOTIFY_SIGN_KEY=<random>          # notify callback signature (agreed with callers)
export WORKER_EGRESS_ALLOWLIST=http://localhost:9999   # Worker egress whitelist (smoke: token-mock)
```

> Rotation: platform-side `POST /api/v1/admin/tenants/{id}/reset-secret` (old key 24h grace dual-verify + all sessions revoked) → update the three same-value `LOTASK_*` variables gateway-side and roll restarts; set `LOTASK_TENANT_SECRET_PREVIOUS` to the old key to accept deliveries within the grace window.

## 4. Why One Value for Three Variables

lotask4j V4+ credentials are **tenant-scoped**:

1. **Login**: `POST /api/v1/auth/token` (client_credentials, `client_id=tenant name, client_secret=tenantSecret`) → bearer. The gateway's `LotaskAuthSigner` **self-mints an HS256 JWT** using `LOTASK_JWT_SECRET` (= tenantSecret), which the platform validates by the same logic.
2. **Write-endpoint HMAC** (submit/cancel paths): `X-Access-Key=tenant id`; secret = the `tenant_secret` decrypted by `DbSecretProvider` per id; five-part stringToSign — same recipe as the gateway's `ThmpSignature`.
3. **Webhook verification**: the platform signs deliveries with the task-owning tenant's `tenant_secret` → the gateway's `WebhookVerifier` recomputes with `LOTASK_TENANT_SECRET`.

All three key sources are `asts_tenant.tenant_secret`, so deployments inject the same value into three variables; the only independent one is `LOTASK_ACCESS_KEY` (the tenant id).

## 5. Bring Up the Smoke Environment (full chain)

```bash
# ① Infrastructure (redis + token-mock)
docker compose -f docker-compose.smoke.yml up -d

# ② lotask4j (per its repo README; PG + V1→V4 migrations + redis-name=default pointing at ①)

# ③ Build + start apps (env vars per §3)
mvn package
java -jar demo-control-plane/target/demo-control-plane-*.jar &
java -jar app/target/token-gateway-app-*.jar &
java -jar task-worker/target/task-worker-*.jar &

# ④ Full-chain smoke (LLM face + task face + notify + reconciliation)
bash scripts/smoke.sh
```

Smoke coverage matrix: chat sync/streaming happy paths; 10202/10400/10106/10617 negative paths; task create→SUCCEEDED (token-mock natural cadence ~60s); proxy resource credential-free fetch + tampered sig → 400; notify verification; openHolds zero-diff reconciliation; Idempotency-Key rejection dedup.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Gateway fail-fast "task.lotask.url not configured" | `LOTASK_URL` is required when face=task |
| create 502 (lotask submit failed) | Platform logs for auth: 401=token invalid (client_id must be the tenant name); signature rejected=ACCESS_KEY/SECRET mismatch |
| Task stuck PENDING | Worker down / script missing (Worker logs `ScriptLoader`); platform `asts_task` state and worker heartbeat |
| No webhook received | Platform→gateway network; platform outbox delivery status; `LOTASK_WEBHOOK_CALLBACK_URL` reachability |
| No notify received | Gateway `[Notify]` logs; caller address reachability; backoff tiers 1m/10m/1h — be patient |
| Non-empty openHolds | `[Reconcile]`/`[TimeoutClock]` logs; whether platform terminal state and webhook arrived |
