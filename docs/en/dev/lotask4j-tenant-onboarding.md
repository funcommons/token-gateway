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
| Tenant `name` (chosen at creation) | `LOTASK_ACCESS_KEY` + `LOTASK_TENANT_NAME` | Write-endpoint `X-Access-Key` header (platform DbSecretProvider looks up by tenant **name**, **not id**) and login client_id |
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
export LOTASK_TENANT_NAME=<tenant name>      # client_credentials login subject (= tenant name from 2.2)
export LOTASK_JWT_SECRET=<tenantSecret>      # login client_secret (LotaskAuthSigner exchanges + caches bearer)
export LOTASK_ACCESS_KEY=<tenant name>       # X-Access-Key (= tenant name, NOT id!)
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

1. **Login**: `POST /api/v1/auth/token` (client_credentials, `client_id=tenant name, client_secret=tenantSecret`) → bearer.
   **Real login is mandatory — self-minted JWTs are rejected** (V4+ field-tested): platform-issued tokens carry a session fingerprint (`hash`/`jti` claims, validated by the framework4j accesstoken interceptor); a self-minted minimal JWT gets 401. The gateway/worker `LotaskAuthSigner` does login + in-process caching + renewal 5min before exp + 30s cooldown on login failure; the login endpoint is idempotent, so a duplicate login at concurrent cold start is harmless.
2. **Write-endpoint HMAC** (submit/cancel paths): `X-Access-Key=tenant name` (`DbSecretProvider` looks up `asts_tenant.name`, **not id** — the platform yml comment saying "= tenant id" is wrong, the `DbSecretProvider` implementation is authoritative); secret = the decrypted `tenant_secret`; five-part stringToSign — same recipe as the gateway's `ThmpSignature`. Note: all three domain APIs (client/worker) are annotated `@RequiresToken("TENANT")` — the bearer login covers every domain; HMAC is only enforced on client-domain write endpoints.
3. **Webhook verification**: the platform signs deliveries with the task-owning tenant's `tenant_secret` → the gateway's `WebhookVerifier` recomputes with `LOTASK_TENANT_SECRET`.

All three key sources are `asts_tenant.tenant_secret` (login / HMAC / webhook — inject the same value into the three variables); `LOTASK_ACCESS_KEY` = tenant `name`, same value as `LOTASK_TENANT_NAME`.

## 5. Bring Up the Smoke Environment (full chain)

```bash
# ① Infrastructure (redis + token-mock)
docker compose -f docker-compose.smoke.yml up -d

# ② lotask4j (per its repo README; PG + V1→V4 migrations + redis-name=default pointing at ①)

# ③ Build + start apps (shared env vars per §3; per-process overrides inline below)
mvn package
source /tmp/tgw-smoke/env   # or export all §3 variables any way you like
# platform runs in a container → it must reach the gateway via host.docker.internal
export LOTASK_WEBHOOK_CALLBACK_URL=http://host.docker.internal:9406/internal/lotask/webhook

# demo control plane :9405 — carries the notify verify key (same value as gateway)
nohup env SERVER_PORT=9405 TGW_NOTIFY_SIGN_KEY="$TGW_NOTIFY_SIGN_KEY" \
  java -jar demo-control-plane/target/demo-control-plane-*.jar > /tmp/tgw-smoke/control-plane.log 2>&1 &

# gateway :9406 — backend.url must point at the control plane (default is 9400, override mandatory!)
nohup env SERVER_PORT=9406 GATEWAY_BACKEND_URL=http://localhost:9405 \
  TOKEN_GATEWAY_TASK_NOTIFY_SIGN_KEY="$TGW_NOTIFY_SIGN_KEY" \
  java -jar app/target/token-gateway-app-*.jar > /tmp/tgw-smoke/gateway.log 2>&1 &

# worker :9411 (yml default port)
nohup java -jar task-worker/target/task-worker-*.jar > /tmp/tgw-smoke/worker.log 2>&1 &

# ④ Full-chain smoke (defaults 9400/9401; pass env vars when ports are shifted)
CP=http://localhost:9405 GW=http://localhost:9406 LOTASK=http://localhost:<port> bash scripts/smoke.sh
```

Smoke coverage matrix (11 steps, 28 assertions — 29 with notify verify keys on both sides): chat sync/streaming happy paths; 10202/10400/10106/10617 negative paths; task create→SUCCEEDED (token-mock natural cadence ~60s); proxy resource credential-free fetch + tampered sig → 400; unknown task_no poll → 404/10400; webhook tampering (real lotaskId + forged signature claiming FAILED → verify-then-act re-checks the platform, state untouched, no notify); timeout clock EXPIRED (Redis-injected past deadline → TIMEOUT + full refund + notify; audio modality has no worker script so the platform task stays QUEUED — zero race); notify verification; openHolds zero-diff reconciliation (settle + refund dual path); Idempotency-Key rejection dedup.

> **Ops note**: running processes boot from jars under `target/` — running `mvn clean`/a rebuild deletes the underlying files. Processes do not die immediately but degrade into zombies on lazy class loading (`NoClassDefFoundError`: alive on the surface, Redis/login failing, health endpoint flaky). Restart all three processes per the recipe above after a rebuild.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Gateway fail-fast "task.lotask.url not configured" | `LOTASK_URL` is required when face=task |
| create 502 (lotask submit failed) | Inspect the 401 body: "no auth token"=bearer missing (check LOTASK_TENANT_NAME/JWT_SECRET, real login required); "logged in elsewhere"=same-tenant multi-process session kicking (gateway & worker must point at the same Redis to share the token store); "unknown AccessKey"=X-Access-Key wrong (tenant **name**, not id); "signature header missing/invalid"=HMAC four headers or sign-key mismatch |
| Task stuck PENDING | Worker down / script missing (Worker logs `ScriptLoader`); platform `asts_task` state and worker heartbeat |
| No webhook received | Platform→gateway network; platform outbox delivery status; `LOTASK_WEBHOOK_CALLBACK_URL` reachability |
| No notify received | Gateway `[Notify]` logs; caller address reachability; backoff tiers 1m/10m/1h — be patient |
| Non-empty openHolds | `[Reconcile]`/`[TimeoutClock]` logs; whether platform terminal state and webhook arrived |
