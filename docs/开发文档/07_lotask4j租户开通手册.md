# lotask4j 租户开通手册（token-gateway 接入）

| 项 | 内容 |
|---|---|
| 文档 | token-gateway 接入 lotask4j 平台的租户开通与冒烟环境 runbook（未决项 #1 落地） |
| 地位 | 运维操作手册；《05_任务面lotask4j托管方案》§8 安全口径的落地步骤 |
| 平台前提 | **lotask4j V4+**（多租户 + RLS + webhook HMAC，见其仓 README「认证与多租户」） |
| 版本 | V1.0（2026-09-02） |

---

## 1. 概念对照

| lotask4j 概念 | token-gateway 侧 | 用途 |
|---|---|---|
| 租户 `name` | — | 接入方身份（如 `token-gateway`） |
| 租户 `tenantSecret`（一次性明文） | `LOTASK_JWT_SECRET` 的登录凭据 + `LOTASK_SIGN_KEY` + `LOTASK_TENANT_SECRET`（同一个值，见 §4 认证说明） | client_credentials 登录 / 写端点 HMAC / webhook 验签 |
| 租户 id（创建后分配） | `LOTASK_ACCESS_KEY` | 写端点 `X-Access-Key` 头（平台 DbSecretProvider 按租户 id 查钥） |
| task_type 配置 | Worker 拉单类型 | `video`（超时/并发/重试在平台侧配置） |

> 凭证纪律：`tenantSecret` 明文只在创建/重置响应出现一次，**立即入密钥管理系统/环境变量，不入仓不入聊天记录**。

## 2. 平台侧开通步骤（管理员操作）

前置：lotask4j 已部署（PG + Flyway V1→V4 + Redis），管理面可达 `http://<lotask>:8080`。

### 2.1 平台身份登录

```bash
PLATFORM_TOKEN=$(curl -s -X POST http://<lotask>:8080/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'grant_type=client_credentials&client_id=PLATFORM&client_secret=<PLATFORM_CLIENT_SECRET>' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["access_token"])')
```

### 2.2 创建网关租户（secret 明文仅此一次）

```bash
curl -s -X POST http://<lotask>:8080/api/v1/admin/tenants \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"token-gateway","description":"token-gateway 任务面托管"}'
# → data: { "id": 42, "tenantSecret": "<一次性明文>" }   ← 保存!
```

### 2.3 注册 task_type `video`

```bash
curl -s -X POST http://<lotask>:8080/api/v1/admin/types \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "tenantId": 42,
    "typeKey": "video",
    "name": "视频生成",
    "concurrencyLimit": 50,
    "timeoutSeconds": 7200,
    "maxRetries": 1,
    "isEnabled": true
  }'
```

> `timeoutSeconds` 与网关侧 `token-gateway.task.timeouts.video` 对齐（网关超时钟兜底，平台超时双保险）。image/audio/tts 同理按需注册。

### 2.4 配置 webhook 回调（终态事件 → 网关）

任务提交时网关在 `submit.callbackUrl` 逐任务下发（`LOTASK_WEBHOOK_CALLBACK_URL`），平台侧无需预配置；只需保证平台实例**能访问网关** `/internal/lotask/webhook`（同网段或公网入口），且平台投递签名密钥 = 租户 `tenantSecret`（内置，无需额外配置）。

## 3. 网关/Worker 侧环境变量（由 2.2/2.3 的产出填充）

```bash
export LOTASK_URL=http://<lotask>:8080
export LOTASK_JWT_SECRET=<tenantSecret>      # LotaskAuthSigner 自铸 HS256 bearer
export LOTASK_ACCESS_KEY=42                  # 租户 id (X-Access-Key)
export LOTASK_SIGN_KEY=<tenantSecret>        # HMAC 四头 secret (= tenant_secret)
export LOTASK_TENANT_SECRET=<tenantSecret>   # webhook 验签 (同一密钥)
export LOTASK_WEBHOOK_CALLBACK_URL=http://<gateway>:9401/internal/lotask/webhook
export TGW_SNAPSHOT_CIPHER_KEY=<base64 32B>  # 网关与 Worker 同钥 (路由快照加解密)
export TGW_RESOURCE_SIGN_KEY=<随机串>         # 资源代理 sig
export TGW_NOTIFY_SIGN_KEY=<随机串>           # notify 回调签名 (与调用方约定)
export WORKER_EGRESS_ALLOWLIST=http://localhost:9999   # Worker 出网白名单 (冒烟: token-mock)
```

> 轮换：平台侧 `POST /api/v1/admin/tenants/{id}/reset-secret`（旧钥 24h 宽限双验 + 撤全部会话）→ 网关侧更新 `LOTASK_*` 三个同值变量并滚动重启；`LOTASK_TENANT_SECRET_PREVIOUS` 配旧钥承接宽限期内投递。

## 4. 认证语义说明（为什么三个变量同一个值）

lotask4j V4+ 的凭据体系以**租户**为粒度：

1. **登录**：`POST /api/v1/auth/token`（client_credentials，`client_id=租户名, client_secret=tenantSecret`）→ bearer。网关 `LotaskAuthSigner` 用 `LOTASK_JWT_SECRET`（= tenantSecret）**自铸 HS256 JWT** 走同一校验逻辑。
2. **写端点 HMAC**（submit/cancel 两路径）：`X-Access-Key=租户 id`，secret = `DbSecretProvider` 按 id 解密出的 `tenant_secret`，toSign 五段式与网关 `ThmpSignature` 同源。
3. **webhook 验签**：平台用任务归属租户的 `tenant_secret` 签名投递 → 网关 `WebhookVerifier` 用 `LOTASK_TENANT_SECRET` 复算。

三处密钥源都是 `asts_tenant.tenant_secret`，故部署时同一值注入三个变量（唯一独立的是 `LOTASK_ACCESS_KEY`=租户 id）。

## 5. 冒烟环境快速拉起（全链路）

```bash
# ① 基础设施 (redis + token-mock)
docker compose -f docker-compose.smoke.yml up -d

# ② lotask4j (按其仓 README 部署; PG + V1→V4 迁移 + redis-name=default 指向 ①)

# ③ 构建 + 起应用 (环境变量见 §3)
mvn package
java -jar demo-control-plane/target/demo-control-plane-*.jar &
java -jar app/target/token-gateway-app-*.jar &
java -jar task-worker/target/task-worker-*.jar &

# ④ 全链路冒烟 (LLM 面 + 任务面 + notify + 对账)
bash scripts/smoke.sh
```

冒烟覆盖矩阵：chat 同步/流式正路径、10202/10400/10106/10617 负路径、任务 create→SUCCEEDED（token-mock 自然节奏 ~60s）、代理资源免凭证拉取 + sig 篡改 400、notify 验签、openHolds 对账零差异、Idempotency-Key 拒绝式去重。

## 6. 故障速查

| 现象 | 排查 |
|---|---|
| 网关启动 fail-fast「task.lotask.url 未配置」 | face=task 时必配 `LOTASK_URL` |
| create 502 (lotask submit 失败) | 平台侧日志看鉴权：401=token 无效（检查 client_id=租户名）；签名拒绝=ACCESS_KEY/SECRET 错位 |
| 任务一直 PENDING | Worker 未起/脚本缺失（Worker 日志 `ScriptLoader`）；平台侧 `asts_task` 状态与 worker 心跳 |
| webhook 收不到 | 平台→网关网络；平台 outbox 表投递状态；`LOTASK_WEBHOOK_CALLBACK_URL` 可达性 |
| notify 未收到 | 网关日志 `[Notify]`；调用方地址可达性；退避档位 1m/10m/1h 耐心等 |
| 对账 openHolds 非空 | 看 `[Reconcile]`/`[TimeoutClock]` 日志；平台终态与 webhook 是否到达 |
