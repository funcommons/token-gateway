# Backend Integration Security Contract

| Item | Content |
|---|---|
| Document | Security contract for gateway ↔ capability-backend integration (authentication, credentials, transport security — authoritative definition) |
| Status | **Authoritative** for security policy; design doc §5.2 and backend onboarding §3.4 are summary references of this document |
| Companion | Capability-face contract `03_能力面接口契约.yaml` ([link](https://github.com/funcommons/token-gateway/blob/main/docs/开发文档/03_能力面接口契约.yaml)); [Backend Onboarding Manual](./backend-onboarding.md) |
| Version | V1.0 (2026-08-31; decision: three auth modes — the static `token` mode removed) |

---

## 1. Roles and Trust Boundaries

```
Caller ──(A) caller credential──▶ token-gateway ──(B) gateway↔backend credential──▶ capability backend
```

This document governs segment **(B)**: service-to-service calls where the gateway is the client and the capability backend is the server. For segment (A) (caller credentials), exactly one rule carries through: **credentials never appear in logs** (see §6).

Trust boundary: no cross-host call may assume network safety — security must be guaranteed at the protocol layer (the three auth modes). Network-layer controls (unexposed ports / security groups / IP whitelists) are **defense in depth**, not substitutes.

## 2. Deployment Scenario Tiers

| Tier | Definition | Minimum requirement |
|---|---|---|
| S1 same-host isolation | Backend co-located with the gateway (same host/Pod), port bound to 127.0.0.1 | `none` acceptable |
| S2 intranet cross-host | Same trusted intranet, port reachable internally only | `key` acceptable, `jwt` recommended |
| S3 cross-segment / public | Across security domains, clouds, or the public internet | **`jwt` mandatory**; per-request signing should be added (§4) |

## 3. The Three Auth Modes

> Selected per capability face via gateway yml `auth:`. On verification failure the backend returns **HTTP 401 + envelope code=10300**, which the gateway treats as a **configuration-error alert** (not a caller error) — operations alerting, no retry.

### 3.1 `jwt` (recommended, default)

**Sent as**: `Authorization: Bearer <HS256 JWT>`

**Claims convention** (aligned with the production internal-token form):

| claim | Meaning | Example |
|---|---|---|
| `iss` | Issuer | `mmagix` |
| `caller` | Caller identity | `gateway-webflux` / `token-gateway` |
| `tenant_id` | Tenant context | `0` |
| `exp` / `iat` | Expiry / issued-at | Short exp + periodic rotation |

**Backend three-step verification**: ① verify the HS256 signature with the shared secret → ② check `exp` is not expired → ③ check `iss`/`caller` match the convention.

**Value**: caller identity semantics (audit attribution per caller), expiry, differentiated authorization; production already uses this form — zero alignment cost.

**Known limitation (must be understood)**: the production form is a **fixed signed JWT** (minted once, long-lived) — it stops "the credentialess" but **not replay after interception**. Acceptable on the intranet (S2); S3 must add §4 per-request signing.

**Rotation discipline**: if the backend rotates its secret it **must notify the gateway to re-mint in sync** (out-of-sync = fleet-wide 401, see the manual FAQ); a dual-secret grace window is recommended (verify both old and new for one rotation cycle).

### 3.2 `key` (acceptable, S2 intranet only)

**Sent as**: `X-API-Key: <static key>`

**Verification**: **constant-time comparison** (never plain `equals`/`==` — timing side channel; Java: `MessageDigest.isEqual`, Go: `subtle.ConstantTimeCompare`).

**Limits**: no identity semantics (unknown who is calling), no expiry, rotation requires coordinated change on both ends — **forbidden in S3**.

### 3.3 `none` (restricted, S1 only)

**Sent as**: no auth header.

**Applies to**: localhost/sidecar same-host isolation only (port bound to 127.0.0.1 + network policy as backstop). An IP whitelist may be layered on top, but **a whitelist alone is not authentication** — it cannot stop lateral movement once the segment is breached.

**Startup enforcement**: `auth=none` with a non-localhost/127.0.0.1 url → gateway startup warning (`CapabilityValidator`).

### 3.4 Selection Matrix

| Your scenario | Choose |
|---|---|
| Same-host sidecar / same Pod | `none` (+ port-binding constraint) |
| Intranet service-to-service, no identity need | `key` acceptable |
| Intranet service-to-service, audit attribution / tenant semantics | `jwt` (recommended) |
| Cross-segment / public | `jwt` + §4 per-request signing |
| ~~Static opaque token (`token` mode)~~ | **Removed** (2026-08-31 decision): existing systems re-mint as `jwt` during migration |

## 4. Cross-Segment Upgrade: Per-Request Signing (HMAC Four-Header)

For S3, layer per-request signing on top of the `jwt` identity — replay-proof and tamper-proof. The contract shape is the battle-tested THMP contract-face form (`ThmpSignature`, fwk4j-signature semantics):

**Four request headers**:

| Header | Content |
|---|---|
| `X-Access-Key` | Caller identifier (client-id) |
| `X-Timestamp` | Millisecond timestamp (server checks a ±5 min clock window) |
| `X-Nonce` | One-time random string (server-side Redis dedup, 10 min window; replay rejected) |
| `X-Signature` | `Base64(HMAC-SHA256(secret, stringToSign))` |

**String to sign** (`\n`-joined; body is MD5-hashed first to guarantee byte consistency):

```
stringToSign = HTTP_METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + md5Hex(BODY)
```

**Server verification order**: signature → clock window → nonce one-time (any failure → 401+10300). All comparisons are constant-time.

> On the gateway side this shape is the protocol form of `adapter=tokenhub` (configured under the `route:` face), not one of the three `auth:` modes. Third-party backends in S3 should reuse the same contract (already included in the capability-face contract yaml).

## 5. Credential Lifecycle Discipline

| Rule | Requirement |
|---|---|
| Injection | Always via environment variables — **never committed to the repo** (examples must use placeholders) |
| Display | Logs/admin surfaces show masked forms only (first 4 + last 4, `****` between) |
| Storage | Backend-side credentials are stored encrypted with AES-GCM cipher (cipher_by_biz equivalent); no plaintext at rest |
| Comparison | All secret comparisons use constant-time implementations |
| Rotation | jwt secrets rotate via dual-secret grace; on leak: revoke and re-mint immediately, audit calls within the leak window |
| Distribution | Never send plaintext over IM/email; use a secrets manager or an encrypted channel |

## 6. Caller Credential Protection (Segment-A Discipline, Enforced Across B)

- Caller credentials (token / sk- key) **only enter the `TokenValidator.validate` argument** — never in logs, exception messages, or audit payloads.
- `TokenContext.toString()` must be masked (SPI rule 4, already implemented in `gateway-spi`); the token-validate face returns only `masked_credential`.
- Any credential field hitting backend/gateway logs must be masked.

## 7. Error Semantics

| Situation | Response | Gateway handling |
|---|---|---|
| Backend rejects the gateway's credential | HTTP 401 + envelope `code=10300` | **Configuration error** — ops alert; no retry; not blamed on the caller |
| Gateway rejects the caller's credential | HTTP 401 + envelope `10200/10202` | Normal business flow, passed through to the caller |
| Signature clock-window overflow / nonce replay | HTTP 401 + envelope `code=10300` | Configuration-error alert (suspected replay → security audit) |

## 8. Audit and Compliance

- Credential lifecycle events (create/rotate/revoke), auth-failure storms, moderation blocks → emitted to the audit face (AUDIT capability).
- Audit storage is append-only; physical deletion is forbidden; retention per compliance requirements.
- Audit emission failures never block the main path (quiet + alert).

## 9. Acceptance Checklist

- [ ] Every capability face's `auth` follows the §3.4 matrix; S3 faces have §4 per-request signing
- [ ] jwt three-step verification complete; key comparisons are constant-time
- [ ] All credentials env-injected, no plaintext in the repo; no plaintext credentials in logs (spot-check startup + error logs)
- [ ] Every `auth=none` face points at localhost (no CapabilityValidator warning at startup)
- [ ] Secret rotation rehearsed once (dual-secret grace → full cutover → old secret revoked)
- [ ] 401+10300 wired into ops alerting (distinct from caller-401 business flow)
