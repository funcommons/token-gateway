# 任务面 lotask4j 托管方案

| 项 | 内容 |
|---|---|
| 文档 | 任务面任务状态托管方案（lotask4j 托管 + 平台中转执行 + 零改造接入清单） |
| 地位 | 任务面实现口径**权威文档**；替代设计方案原 M2.5"THMP 任务域移植"口径（2026-09-01 决议） |
| 配套 | 设计方案 `01_设计方案.md` §6.4/§7；任务面接入手册 `../用户文档/02_任务面接入手册.md`；安全契约 `04_后端服务对接安全契约方案.md` |
| 版本 | V1.1（2026-09-01：lotask4j 托管任务状态；按 lotask4j V4 租户化 + webhook 签名升级、零改造路径、脚本放 gateway 三处决议修订） |
| 平台前提 | **lotask4j V4+**（租户隔离 RLS + 三域鉴权 + webhook HMAC 签名已内置，commit `9c23025` 之后） |

---

## 1. 决策

| 决议 | 内容 |
|---|---|
| 任务状态托管 | **lotask4j（ASTS 异步慢任务平台）托管**：任务记录、状态机、重试、僵尸回收、调度兜底全部复用平台能力 |
| 上游对接形态 | **平台中转**：**自写 Worker**（token-gateway 侧执行器，经 lotask4j worker API 拉单/上报）执行上游调用（create/poll/result），网关**不直连上游执行任务** |
| 网关保留 | caller 四模态端点、计费 saga（先路由定价 → 全额预扣 → 终态退款）、notify（HMAC + 退避）、资源代理（sig 能力凭证） |
| 上游适配方式 | **Groovy 脚本**（每任务类型一个适配脚本，三钩子），新接上游零发版（§9）；脚本真源在 **token-gateway 仓 `scripts/`**，lotask4j 平台零感知 |
| lotask4j 改造策略 | **零改造接入**（§10）：R1 租户隔离 / R4 webhook 签名平台已内置；R2/R6/R7 由网关侧补偿；R8 平台脚本执行器取消（Worker 自写）；仅 R5 手动改态保留为可选增量 |
| 数据一致性 | 任务状态**单写 lotask4j**，计费状态在后端 billing 面；`task_no + pre_consume_id` 关联，**不做数据双写、不共享数据库** |
| 备选形态 | 私有化/无平台场景保留自有 DB 委托面备选（TaskClient SPI 已冻结，切换是适配器决策非架构分叉）。**retask4j 评估结论**：Redis 队列框架，无持久任务表/查询/管理面，状态托管不适用；仅作私有化轻量组合（自有 DB + retask4j 分发）的执行层备选 |

## 2. 总体架构

```mermaid
graph TB
    C[调用方] -->|Bearer / sk- key| GW[face-task 数据面<br/>四模态端点 · 计费 saga · notify · 资源代理]
    GW -->|控制层接口| CP[控制层<br/>token-validate · route 路由表]
    GW -->|billing 面| BIL[计费后端<br/>预扣/结算/退款]
    GW -->|submit/poll/cancel<br/>jwt + HMAC 签名| LT[lotask4j 平台 V4+<br/>任务表 · 状态机 · Reaper · outbox<br/>租户隔离 RLS · 三域鉴权]
    LT -->|poll/progress/result<br/>worker token| W[自写 Worker（token-gateway 仓）<br/>Groovy 三钩子 · 沙箱 · 超时钟]
    W -->|create/poll| UP[上游任务 API<br/>videos/images/audios/tts]
    LT -.->|webhook 终态事件<br/>HMAC 三头签名（平台内置）| GW
    C -->|GET 资源 sig URL| GW
    GW -->|流式回源+缓存| UP
```

**控制层决策不变**（2026-09-01 决议）：key 验证与路由表归控制层；create 时网关先 resolve 路由定价，**路由快照（base_url + 出站凭证 + model_mapping）经网关侧加密后随 submit 载荷下发 Worker**——定价时刻与执行用路由一致，Worker 不再二次寻址。

## 3. 直连 vs 中转的裁决

| 维度 | 网关直连（lotask4j 仅当状态表） | **平台中转（自写 Worker 执行）** ✅ |
|---|---|---|
| 状态写者 | **双写者问题**：网关驱动状态迁移 + lotask4j 状态机 CAS 并存，lease/fencing 语义被破坏 | 单写者：状态迁移只有 lotask4j 状态机（version + execution_token CAS）；Worker 只经 worker API 上报，不直接写状态 |
| 执行保障 | 网关要自建轮询循环/重试/僵尸回收 = 把 lotask4j 已解决的问题重做一遍 | Reaper（lease 回收/过期 FAILED）、重试退避、attempt 上限全部复用 |
| 网关部署 | face-task 实例要维持长轮询调度，扩缩容影响在途任务 | 网关无执行循环，纯请求驱动；执行弹性在 Worker 池独立扩缩 |
| 结论 | 否决 | **采用** |

> Worker 为何自写而不是用 lotask4j 内置 Worker：脚本真源与沙箱归 token-gateway 仓（评审/CI/灰度与网关同节奏），lotask4j 平台保持零改造零感知；Worker 只消费 lotask4j 公开的 worker pull API（poll/progress/result），不依赖平台内部。

## 4. 职责矩阵

| 职责 | 归属 | 说明 |
|---|---|---|
| caller 四模态端点（create/poll/资源代理） | 网关 face-task | API 契约不变（任务面手册） |
| key 验证 / 路由表 / 定价 | 控制层（token-validate / route 面） | create 时刻决策并快照 |
| 计费 saga（全额预扣 / 终态退款） | 网关 → billing 面 | 先路由定价再预扣；FAILED/EXPIRED 全额退款 |
| 任务记录 / 状态机 / 重试 / 僵尸回收 | **lotask4j** | asts_task + TaskStateMachine + TaskReaper |
| 租户隔离 | **lotask4j V4 内置** | tenant_id 全链路收口 + PostgreSQL RLS 行级策略 + client/worker/管理端三域守卫；网关以独立租户接入 |
| 上游调用执行 | **自写 Worker（token-gateway 仓，Groovy 三钩子）** | create/poll/resultMapping（§9）；经 lotask4j worker API 拉单/上报 |
| 终态事件 → 触发 notify/退款 | lotask4j webhook → 网关 | **平台内置 HMAC 三头签名**（X-ASTS-Event-Id/Timestamp/Signature，§8）；网关验签 + verify-then-act 回查兜底 |
| notify 调用方回调（X-THMP-Signature + 退避） | 网关 face-task | 语义不变 |
| 资源代理（sig 24h + 流式回源 + 缓存盘） | 网关 face-task | Worker 上报原始资源 URL，网关转签名代理 URL，上游 URL 永不透传 |
| 非标运营功能（标签/手动成功/手动重试/退款入口） | **lotask4j 管理面**（可选增量 R5） | 退款入口触发网关 → billing 面 refund（幂等）；全部留审计事件；无 R5 时手动改态走平台 DB 运维通道降级（§11） |

## 5. 端到端流程

### 5.1 create（同步返回）

```
调用方 POST /v1/videos {model, params, notify_url}
  → 网关: 控制层 key 验证
  → 网关: 控制层 route resolve（先路由定价：模型不同价格不同）
  → 网关: 生成 task_no，按命中模型全额预扣（billing 面; 余额不足 → 10617, 不产生任务）
  → 网关: Redis 幂等去重（task_no 已存在 → 直接返回已建任务, 网关侧补偿 R2）
  → 网关: lotask4j submit {task_type: video, idempotency_key: task_no,
                           payload: {params, notify_url, route 快照(网关侧 AES-GCM 加密)}}
       ‑ 独占租户分区内 idempotency_key 唯一 + 网关侧去重 ⇒ 端到端幂等
  → 调用方 ← {task_no, PENDING, poll_url}   （submit 失败 → 全额退款 + 10004）
```

### 5.2 执行与终态（异步）

```
自写 Worker poll 到任务 → RUNNING（lease + fencing, lotask4j 状态机 CAS）
  → Groovy create 钩子: 按 route 快照调上游创建 → 上游任务 ID 写入 progress
  → Groovy poll 钩子: 轮询上游直至终态（Worker 内循环, 间隔按 task_type 配置）
  → Groovy resultMapping 钩子: 上游结果 → {resources[], usage} 契约
  → Worker reportResult → lotask4j 终态落库 + outbox（终态事务内落行, 不丢）
  → webhook（HMAC 三头签名, outbox 指数退避重投）→ 网关:
       验签通过 → 按终态处理; 无签名/验签失败 → verify-then-act 回查 lotask4j 核实
       SUCCESS   → result.resources 转 sig 代理 URL 落缓存索引; 预扣转消费（不退）
       FAILED    → billing 面全额 refund（幂等）→ notify
       超时      → 网关超时钟判定（deadline 到期反查终态）→ 映射 EXPIRED → 全额退款 → notify
       CANCELLED → 映射 FAILED（运营取消, 全额退款）→ notify
  → notify_url 回调（X-THMP-Signature; 失败退避 1m/10m/1h）
```

### 5.3 poll（调用方轮询）

```
GET /v1/videos/{task_no}
  → 网关: key 验证（消费方只查自己的任务 —— lotask4j V4 租户隔离内置: tenant_id 全链路 + RLS）
  → 网关 → lotask4j GET /api/v1/client/tasks/{id}
  → 状态映射（§6）后返回; 终态返回存储结果（sig 代理 URL）, 不触上游
```

### 5.4 资源代理

与现契约一致：`GET /v1/resources/{task_no}/{index}?exp=&sig=`，校验 sig → 流式回源 + 本地缓存盘；上游原始 URL 永不透传。

## 6. 状态机映射

| 网关（调用方可见） | lotask4j | 说明 |
|---|---|---|
| PENDING | PENDING | 待 Worker 拉取 |
| RUNNING | RUNNING | 执行中（含 Worker 内上游轮询） |
| SUCCEEDED | SUCCESS | 终态 |
| FAILED | FAILED / CANCELLED | 终态（运营取消并入 FAILED, 全额退款） |
| EXPIRED | FAILED / 网关超时钟判定 | 终态；lotask4j 超时混在 FAILED 不落独立 error_code（零改造下不做 R6），由**网关侧超时钟**判定：create 时记 deadline（按 task_type 配置时长），到期反查 lotask4j 终态后映射 EXPIRED |

模型映射：`task_no` ↔ `idempotency_key`（外部幂等键）；`model+params` ↔ `payload`；`result.resources/usage` ↔ `result` JSONB（契约见 §7）；24h 过期窗口由网关侧超时钟承载（按模态配置时长）。

## 7. 计费与一致性

- **关联键**：`task_no`（= lotask4j idempotency_key）+ `pre_consume_id`（billing 面）；网关侧不建任务表。
- **无双写**：任务状态只在 lotask4j；计费状态只在 billing 后端。终态事件（webhook 验签后）驱动退款/消费，失败重投（outbox 指数退避）+ 网关对账任务兜底（按 pre_consume_id 查未闭环预扣 → 反查 lotask4j 终态补偿）。
- **webhook 丢失窗口**：outbox 重投上限后 FAILED → 网关对账兜底任务（原 MaintenanceScheduler 孤儿预扣释放语义的简化版——只剩"预扣-终态"对账，状态机不再兜底）。
- **notify 与退款顺序**：先退款成功（幂等）再 notify，避免调用方先收到 FAILED 但退款未到账。

## 8. 安全

| 点 | 方案 |
|---|---|
| 网关 → lotask4j | `jwt`（推荐式）+ 写操作 HMAC 四头签名（lotask4j framework4j-signature 能力，契约同安全契约 §4） |
| route 快照中的出站凭证 | **网关侧字段级加密**（零改造替代 R7）：提交前 AES-GCM 加密 route 快照，密钥仅 Worker 持有（环境注入）；lotask4j 落库/管理面/日志只见密文，平台零感知 |
| lotask4j → 网关 webhook | **平台已内置 HMAC 三头**（V4+）：`X-ASTS-Event-Id`（outbox 行 id，幂等去重键）、`X-ASTS-Timestamp`（±5min 防重放窗）、`X-ASTS-Signature` = `Base64(HmacSHA256(tenant_secret, ts + "\n" + rawBody))`，密钥 = 网关租户的 `tenant_secret`（环境注入）。网关侧：恒定时间比较验签 + 时间窗校验 + Event-Id 幂等去重（复用 `RedisIdempotencyStore`）；**无签名/验签失败不直接拒收**——无租户归属任务平台静默降级为无签名投递，须 verify-then-act 回查 lotask4j 核实；密钥轮换（reset-secret）后 grace-hours 内双钥验签 |
| 消费方查询隔离 | **平台已内置**（V4：tenant_id 全链路收口 + PostgreSQL RLS 行级策略 + 三域守卫）；网关以独立租户接入。独立实例部署建议保留，但动机是爆炸半径/升级节奏，不再是安全硬需求 |
| 调用方侧 | 不变（Bearer/x-api-key, 凭证不落日志） |

## 9. Groovy 脚本适配方案

### 9.1 为什么脚本化

上游任务 API 形态各异（每家的 create 入参/poll 响应/资源字段都不同），Worker 骨架（lease/fencing/重试/上报）与上游协议无关。**Java 写死 = 每接一个上游发一次版；Groovy 脚本 = 新接上游零发版**。

### 9.2 脚本契约（每任务类型一个脚本，三钩子）

```groovy
// task_type: video —— 示例钩子签名（Binding 注入: ctx, http, log, json）
// ctx 暴露: payload(Map), routeSnapshot(Map, 已由 Worker 解密), upstreamTaskId(String), progress(Map)

def create(Map ctx) {
    // 调上游创建; 返回 [upstreamTaskId: "...", progressHint: 0]
}

def poll(Map ctx) {
    // 调上游查询; 返回 [state: "RUNNING"|"SUCCEEDED"|"FAILED", raw: 上游原始响应]
}

def resultMapping(Map ctx) {
    // 上游终态 raw → 网关契约
    // 返回 [resources: ["https://上游/..."], usage: [seconds: 5, resolution: "720p"]]
}
```

### 9.3 脚本写在哪（2026-09-01 决议：放 gateway，不放平台）

| 位置 | 用途 |
|---|---|
| **token-gateway 仓 `scripts/` 目录**（如 `scripts/video/kling-v1.groovy`） | **唯一真源**：评审、版本化、diff 可查；随 token-gateway CI/发版，与网关同节奏 |
| 自写 Worker 运行时加载 | Worker 启动/热更时从部署包内 `scripts/` 加载（GroovyClassLoader 编译缓存，脚本版本变更自动失效）；**lotask4j 平台零感知**——不需要 task_type_config 加列，不需要平台侧脚本执行器（原 R8 取消） |

放 gateway 而非平台的理由：脚本消费的路由快照/资源契约是网关契约；评审与灰度节奏跟网关走；平台保持零改造，未来换托管方（自有 DB 委托面）脚本零迁移。

### 9.4 脚本怎么测

| 层 | 形态 |
|---|---|
| 单测（token-gateway 仓，随网关 CI） | `GroovyScriptTestHarness`：GroovyShell 加载脚本 + fixtures（`scripts/video/fixtures/create-ok.json` 等上游响应样本）断言三钩子输出；mock `http` binding 不触真实网络 |
| 联调（Worker 测试端点） | Worker 暴露 `POST /admin/script-test/dry-run`：指定 task_type + fixture 或真实上游沙箱，返回钩子输出与耗时，不落任务 |
| 灰度 | 新脚本先绑测试 task_type（如 `video-canary`）小流量验证，再切正式 |

### 9.5 脚本安全约束

Worker 内 Groovy 沙箱：黑名单（`System/Runtime/Thread/File/socket` 直接访问）、只允许经 `http` binding（带超时/出网白名单）与 `json` 工具；脚本执行超时硬上限；编译失败/运行异常 → 任务 FAILED + error_code=SCRIPT_ERROR + 审计事件。

## 10. lotask4j 改造清单（零改造口径，2026-09-01 修订）

平台前提 **V4+**（租户隔离 RLS + 三域鉴权 + webhook HMAC 已内置）。对照原 R1~R9：

| # | 原改造项 | 现状 / 替代 | 结论 |
|---|---|---|---|
| R1 | 租户/调用方隔离 | ✅ **平台已实现**：tenant_id 全链路收口 + PostgreSQL RLS 行级策略 + client/worker/管理端三域守卫 | **取消改造**；网关以独立租户接入；独立实例部署保留为建议（爆炸半径/升级节奏，非安全硬需求） |
| R2 | 外部幂等键全局唯一 | 独占租户分区内唯一 + **网关侧 Redis 去重**（submit 前查 task_no 映射） | **网关侧补偿** |
| R3 | 结果资源契约 `{resources[], usage}` | 由 Worker resultMapping 钩子保证（契约约定，非平台改造） | **Worker 侧保证** |
| R4 | webhook 签名 | ✅ **平台已实现**：outbox 投递带 HMAC 三头（X-ASTS-Event-Id/Timestamp/Signature，密钥=tenant_secret）+ 指数退避重投 | **取消改造**；网关验签 + verify-then-act 回查兜底（§8） |
| R5 | 管理面手动改态 / 手动重试 / 任务标签 | 未实现 | **唯一保留的可选增量**（P1）；无它时运营改态走平台 DB 运维通道降级（§11），审计事件由运维通道记录 |
| R6 | 超时语义独立（error_code=TIMEOUT） | **网关侧超时钟**：create 时记 deadline（按 task_type 时长），到期反查 lotask4j 终态 → 映射 EXPIRED → 退款 | **网关侧补偿** |
| R7 | payload 字段级加密 | **网关侧加密**：提交前 AES-GCM 加密 route 快照，密钥仅 Worker 持有，平台只见密文 | **网关侧补偿**（平台零感知） |
| R8 | Groovy 脚本执行器（平台侧） | **自写 Worker 内置沙箱执行器**；脚本真源在 token-gateway 仓（§9.3） | **取消平台改造** |
| R9 | RocketMQ 事件（可选） | webhook 够用（终态事件 + outbox 可靠投递） | 维持不引入（P2） |

**结论：零改造可上线。** 唯一可选增量 R5（运营手动改态/标签）不阻塞 M2.5。

## 11. 降级与可用性

| 故障 | 口径 |
|---|---|
| lotask4j 不可达（create） | 拒请求 + 全额退款（10617 以外的失败 → 10004），不产生"扣了钱没任务" |
| lotask4j 不可达（poll） | 返回上次缓存状态 + 退避提示（状态不变, 调用方重试）；不触 billing |
| webhook 无签名/验签失败 | 不直接拒收：verify-then-act 回查 lotask4j 核实终态后处理（Event-Id 已见 → 幂等跳过） |
| webhook 延迟/丢失 | outbox 退避重投; 超限后网关对账兜底任务按 pre_consume_id 反查补偿 |
| Worker 全部不可用 | 任务积压 PENDING（平台监控告警）; 网关超时钟到期 → 反查终态 → EXPIRED → 退款 |
| 运营手动改态（无 R5） | 走平台 DB 运维通道 + 手工审计记录；改态后 webhook 照常触发退款/notify（幂等） |
| lotask4j 长期下线 | 任务面整体不可用（读降级）; LLM 面不受影响（独立部署分组的意义） |

## 12. M2.5 修订（对设计方案 §10 的替换）

| 项 | 原口径（THMP 移植） | 新口径（lotask4j 托管） |
|---|---|---|
| 状态机/任务表/调度兜底 | THMP 移植 + 17 号 DDL | **不建**——lotask4j 托管（**零改造接入**, V4+ 平台前提） |
| face-task 模块内容 | TaskService/状态机/资源代理/notify/MaintenanceScheduler | caller 端点 + 计费 saga + notify（验签接收）+ 资源代理 + LotaskTaskClient + 超时钟/对账兜底；**自写 Worker + `scripts/` Groovy 适配脚本** |
| face-task 数据库 | 独占任务表库 | **无 DB**（状态在 lotask4j）；仅保留资源缓存盘 |
| 出口标准 | 四模态 create→poll→资源代理冒烟；face=task 独立部署；计费对账零差异 | 同上 + lotask4j **零改造接入验收**（独立租户 + webhook 验签 + 网关侧补偿 R2/R6/R7 生效）+ webhook 三头验真与回查兜底演练 + 降级矩阵逐项演练 |
