# 任务面 face-task 开发手册

| 项 | 内容 |
|---|---|
| 文档 | face-task 开发实施手册：模块/组件分解、lotask4j 对接契约、配置模型、M2.5 任务分解 |
| 地位 | M2.5 开发**执行层依据**；方案层口径以《05_任务面lotask4j托管方案》（V1.1）为准，冲突时以 05 为准并回改本文 |
| 配套 | 《05》方案权威；《05_任务面接入手册》/《08_任务面API契约.yaml》caller 契约；《04_后端服务对接安全契约方案》鉴权三式 |
| 版本 | V1.0（2026-09-01） |

---

## 1. 交付物总览

M2.5 交付三个部署单元 + 一类脚本资产：

| 交付物 | 模块 | 部署形态 |
|---|---|---|
| face-task 数据面 | `face-task`（现有占位模块填充） | `token-gateway.face=task` 分组，无 DB，挂资源缓存盘 |
| **task-worker（新增模块）** | `task-worker`（与 face-task 同级新 Maven 模块） | 独立进程独立扩缩；经 lotask4j worker API 拉单/上报；加载 `scripts/` Groovy 脚本 |
| 脚本资产 | 仓根 `scripts/<modality>/<upstream>-v<n>.groovy` + `fixtures/` | 随仓 CI/发版；Worker 从部署包加载 |
| SPI 增补 | `gateway-spi` | TaskFaceConfig 扩展（§4）；TaskClient 委托面不变（备选形态保留） |

## 2. face-task 包结构（`fun.commons.tokengateway.task.*`）

| 包 | 类 | 职责 |
|---|---|---|
| `task.controller` | `TaskController` | 四模态 create/poll 端点（契约=《04》yaml；信封 ApiCode 业务码） |
| | `ResourceProxyController` | `GET /v1/resources/{task_no}/{index}?exp=&sig=` 流式回源 + 缓存盘 |
| | `LotaskWebhookController` | `POST /internal/lotask/webhook`：三头验签接收终态事件（§3.3） |
| `task.relay` | `TaskRelayOrchestrator` | create 管线：key 验证（控制层）→ route resolve 定价（控制层）→ 全额预扣 → Redis 幂等去重 → lotask4j submit（路由快照加密入载荷）→ 失败全额退款 |
| `task.lotask` | `LotaskTaskClient` | lotask4j client API 封装（§3.1）：submit/get/cancel；jwt + HMAC 四头（复用 core 签名能力） |
| | `RouteSnapshotCipher` | 路由快照 AES-GCM 加解密（密钥仅 Worker 与本类持有，环境注入） |
| `task.billing` | `TaskBillingSaga` | 预扣/退款/预扣转消费；全部按 `pre_consume_id` 幂等 |
| `task.notify` | `NotifyDispatcher` | notify_url 回调（X-THMP-Signature + 1m/10m/1h 退避） |
| | `WebhookVerifier` | 三头校验：恒定时间验签 + ±5min 时间窗 + Event-Id 去重（复用 `RedisIdempotencyStore`）；无签名/验签失败 → verify-then-act 回查 |
| `task.schedule` | `TimeoutClockJob` | 超时钟：按 task_type deadline 扫描在途任务 → 到期反查 lotask4j 终态 → EXPIRED 映射 + 退款（R6 网关侧补偿） |
| | `ReconcileJob` | 对账兜底：按 pre_consume_id 查未闭环预扣 → 反查 lotask4j 终态补偿（孤儿预扣释放） |
| `task.state` | `TaskStateMapper` | lotask4j 状态 → 网关五态映射（《05》§6） |
| `task.config` | `TaskFaceConfiguration` | face-task 装配（挂到 FaceTaskAssembly 扫描下） |

**依赖红线**：face-task 不引 JDBC；lotask4j 是唯一状态存储；Redis 只放幂等去重/超时钟 deadline 索引（可重建，非状态真源）。

## 3. lotask4j 对接契约（平台 V4+ 实测端点）

### 3.1 client 域（网关 LotaskTaskClient 消费，`@TenantDomain` + `@RequiresToken("TENANT")`）

| 用途 | 端点 | 说明 |
|---|---|---|
| 提交 | `POST /api/v1/client/tasks/submit` | body：`{type, payload, idempotencyKey, callbackUrl, priority}`；返回 `{id}`（OpenID 混淆字符串）；幂等键分区内唯一 + 网关侧 Redis 去重（R2 补偿） |
| 查询 | `GET /api/v1/client/tasks/{id}` | 状态/进度/结果；poll 端点只走这里，终态幂等 |
| 取消 | `POST /api/v1/client/tasks/{id}/cancel` | 发取消信号，Worker 循环检测 |
| 鉴权 | jwt（推荐式）+ 写操作 HMAC 四头 | 契约同《04_后端服务对接安全契约方案》§4；submit 是 POST-only 子路径（平台为签名圈定设计） |

`submit.callbackUrl` = 网关 `POST /internal/lotask/webhook`（终态事件 outbox 投递，指数退避重投）。

### 3.2 worker 域（task-worker 消费，`@TenantDomain`，各租户只消费自己的任务）

| 用途 | 端点 | 限流 | 说明 |
|---|---|---|---|
| 抢占 | `POST /api/v1/worker/tasks/poll` | 600/min | body：`{taskType, workerId}`；返回任务 + lease/fencing 令牌 |
| 取消检测 | `GET /api/v1/worker/tasks/{id}/status` | 600/min | Worker 循环内检测 CANCELLING |
| 进度 | `POST /api/v1/worker/tasks/{id}/progress` | 1200/min | 上报上游任务 ID/进度提示 |
| 结果 | `POST /api/v1/worker/tasks/{id}/result` | 600/min | 终态上报触发状态机 CAS + outbox |

### 3.3 webhook 入站（平台 → 网关）

平台投递三头（密钥 = 网关租户 `tenant_secret`，环境注入）：

```
X-ASTS-Event-Id:  {outbox 行 id}   — 幂等去重键（重试投递同 id）
X-ASTS-Timestamp: {epoch millis}   — |now - ts| > 5min 拒收
X-ASTS-Signature: Base64(HmacSHA256(tenant_secret, timestamp + "\n" + rawBody))
```

接收规则（`WebhookVerifier`）：①三头齐全且验签过 → 处理；②缺头/验签失败 → **不拒收**，verify-then-act 回查 `GET /api/v1/client/tasks/{id}` 核实终态（平台对无租户任务静默降级无签名投递）；③Event-Id 已见 → 幂等跳过；④密钥轮换 grace-hours 内双钥验签。处理动作按《05》§5.2：SUCCESS→资源转 sig+预扣转消费；FAILED/CANCELLED→退款→notify；先退款后 notify。

## 4. 配置模型（TaskFaceConfig 扩展）

```yaml
token-gateway:
  face: task
  task:
    expire-scan: 24h                # 已有：默认超时窗口（按 task_type 可覆盖）
    resource-cache-dir: /data/tgw-cache   # 已有
    resource-sign-key: ${TGW_RESOURCE_SIGN_KEY}   # 已有
    notify-retry: [1m, 10m, 1h]     # 已有
    lotask:
      url: http://lotask4j:8080
      auth: jwt                     # 三式之一；jwt 推荐
      jwt-secret: ${LOTASK_JWT_SECRET}
      sign-key: ${LOTASK_SIGN_KEY}  # 写操作 HMAC 四头
      tenant-secret: ${LOTASK_TENANT_SECRET}   # webhook 验签（= 平台侧 tenant_secret）
      connect-timeout: 3s
      read-timeout: 5s
    timeouts:                       # 超时钟：按 task_type 覆盖默认窗口
      video: 2h
      image: 30m
```

Worker 独立配置（task-worker 模块）：`lotask.url/jwt-secret`、`worker.id`、`worker.poll-interval`、`worker.scripts-dir`、`snapshot-cipher-key`（= RouteSnapshotCipher 密钥）、上游出网白名单。

CapabilityValidator 增补：`face=task` 时校验 `lotask.url/tenant-secret/resource-sign-key` 非空（缺 → fail-fast；auth=none + 非 localhost → warning，沿用现有规则）。

## 5. M2.5 任务分解

| 子期 | 内容 | 出口 |
|---|---|---|
| **M2.5a 数据面骨架** | TaskFaceConfig 扩展 + LotaskTaskClient（jwt+HMAC）+ TaskController 四模态 create/poll + TaskRelayOrchestrator（预扣/退款 saga）+ TaskStateMapper；poll 直连 lotask4j 可查 | mock lotask4j（或测试实例）走通 create→poll；余额不足 10617 / submit 失败退款 两条负路径单测 |
| **M2.5b Worker + 脚本** | task-worker 模块（poll/progress/result 循环 + lease 续约）+ Groovy 沙箱（黑名单/http binding/超时上限）+ ScriptLoader + `GroovyScriptTestHarness` + 首个真实脚本（选一个上游 video）+ dry-run 端点 | 脚本单测随仓 CI 绿；测试实例端到端 create→Worker 执行→SUCCESS |
| **M2.5c 终态闭环** | LotaskWebhookController + WebhookVerifier（验签/回查/去重/双钥）+ TimeoutClockJob + ReconcileJob + NotifyDispatcher + ResourceProxyController | 四模态冒烟 + webhook 验真/无签名回查/重投去重演练 + 降级矩阵逐项演练（《05》§11）+ 对账零差异 |

依赖顺序：a ∥ b 可并行（contract 先行），c 依赖 a+b。

## 6. 测试策略

| 层 | 形态 |
|---|---|
| 单测 | saga 分支（10617/submit 失败退款/退款幂等）、状态映射、验签三头全组合、RouteSnapshotCipher 往返 |
| 契约测试 | Testcontainers 已有 Redis 模式复用（幂等去重）；lotask4j 用 WireMock 录 client/worker API 契约 |
| 脚本测试 | `scripts/**/fixtures/*.json` + GroovyScriptTestHarness，随仓 CI |
| 装配测试 | 沿用 FaceTaskActivationTest 模式：face=task 有 task bean 无 LLM bean |

## 7. 未决项（开发前需拍板）

| # | 项 | 建议默认 |
|---|---|---|
| 1 | lotask4j 租户开通流程（建租户/拿 tenant_secret/worker token）属平台运维手册，需在 lotask4j 侧补一篇 on-boarding | 先在测试实例手工开通，runbook 后补 |
| 2 | ~~首个上游选哪家~~ **已决议（2026-09-02）：联调/冒烟默认上游 = token-mock**（`scripts/video/token-mock-v1.groovy` 已落地，真实厂商脚本按其形状写）；token-mock 管理面支持 forceStatus/failureRate 故障演练 | 生产首个真实厂商另议 |
| 3 | task-worker 是否并入 app 模块以 `face=worker` 启动 | **独立模块独立 main**（Worker 池扩缩节奏与网关不同，《05》§3） |
