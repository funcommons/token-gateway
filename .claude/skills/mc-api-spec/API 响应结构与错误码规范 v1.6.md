# API 响应结构与错误码规范 v1.6

> 版本：v1.6
> 修订日期：2026-06-17


---

## 1. 概述

本规范定义系统所有 HTTP API 接口的统一响应格式（Response Structure）与错误码（Error Code）标准。

核心目标：

- **精简高效**：移除冗余字段，降低网络传输开销。
- **类型安全**：状态码使用数字类型，便于客户端高效判断。
- **可观测性**：通过 `trace_id`（body + Header 双通道）贯穿全链路追踪，与数据库 SQL 注释中的 `traceid` 保持一致。
- **健壮性**：防止 null 指针异常；ID、金额等大整数（BigInt）必须序列化为字符串，避免 JS 精度丢失。
- **前后端协作友好**：业务结果完全由 `code` 决定，前端无需双轨判断 HTTP 状态。
- **大厂对齐**：HTTP Header 规范、限流元数据、幂等键等向 Stripe / GitHub / AWS 看齐。

## 2. 适用范围

本规范适用于：

- 所有 RESTful HTTP API（同步请求/响应）
- 网关层之后的业务服务响应

**不适用于**：

- 网关/负载均衡自身返回的非业务错误（如 502 Bad Gateway、504 Gateway Timeout）—— 这些响应不携带业务信封，前端需通过 HTTP 状态识别
- GraphQL、WebSocket、消息队列等非 REST 通道（另行规范）
- 文件下载（二进制流响应）

### 2.1 API 版本控制（v1.6 新增）

参考 Stripe（`/v1/`）、GitHub（`/v3/`）、Twitter（`/2/`），本规范要求：

| 规则 | 说明 |
|---|---|
| 路径前缀 | 所有 API 路径必须以 `/v{N}/` 开头，如 `/v1/orders`、`/v2/users` |
| 兼容变更 | 新增字段、新增可选参数、新增端点 → **不升版本**，保持向后兼容 |
| 破坏性变更 | 删除字段、改字段类型、改语义、改默认行为 → **必须升版本**（`/v1/` → `/v2/`） |
| 旧版本维护 | 旧版本宣布废弃后至少维护 **6 个月**，响应头返回 `Sunset` 与 `Deprecation`（RFC 8594） |
| 版本协商 | 仅通过 URL 路径表达；不支持 Header / 查询参数协商（避免歧义） |

## 3. HTTP Status 与 code 的关系

### 3.1 核心原则

| 场景 | HTTP Status | 响应体 | 说明 |
|---|---|---|---|
| **业务成功** | 200 | 业务信封（`code=0`） | 标准成功响应 |
| **业务失败** | **200** | 业务信封（`code≠0`） | **业务异常统一 HTTP 200**，前端只看 `code` |
| **基础设施异常** | 4xx / 5xx | 无业务信封（网关/框架原始错误） | 详见 §3.3 |

### 3.2 客户端判断顺序

前端处理响应时遵循以下顺序：

```
1. HTTP Status ≠ 200
   → 基础设施异常（网络/网关/路由层），走全局错误兜底（如提示"网络异常，请稍后重试"）
2. HTTP Status = 200 且 code = 0
   → 业务成功，使用 data
3. HTTP Status = 200 且 code ≠ 0
   → 业务失败，按 code 分类处理（见 §7）
```

伪代码示例（TypeScript）：

```ts
async function callApi<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (res.status !== 200) {
    throw new InfraError("网络或网关异常", { traceId: res.headers.get("X-Trace-Id") });
  }
  const body = await res.json();
  if (body.code === 0) return body.data as T;
  if (body.code === 10700) throw new PartialSuccessError(body.data);
  throw new BizError(body.code, body.message, body.error);
}
```

> ⚠️ **禁止**：不得通过 HTTP 4xx/5xx 携带业务信封。例如登录失败必须 `HTTP 200 + code=10203`，不能 `HTTP 401 + code=10203`。

### 3.3 路由层 / 网关层错误（不进入业务代码）

以下错误由 **HTTP 协议、Web 框架（如 Spring MVC）、网关（如 Nginx/APISIX）** 在请求进入业务 Controller **之前** 拒绝，**不会**进入业务代码，因此 **无法**包装业务信封，前端通过 HTTP 状态识别即可：

| HTTP Status | 来源 | 场景 | 与业务错误码的区别 |
|---|---|---|---|
| `400 Bad Request` | 网关 | 网关层请求体解析失败 | 业务层的 JSON 解析失败用 10103 |
| `404 Not Found` | 框架路由 | 路由不存在 | 业务层「资源不存在」用 10400 |
| `405 Method Not Allowed` | 框架路由 | HTTP Method 不匹配 | **无对应业务码**（路由层拦截，到不了业务） |
| `413 Payload Too Large` | 网关 | 网关层请求体超限 | 业务层「文件体积过大」用 10505 |
| `415 Unsupported Media Type` | 框架 | Content-Type 不支持 | **无对应业务码**（框架层拦截） |
| `502 / 503 / 504` | 网关 / 负载均衡 | 服务不可达、网关超时 | 业务层「应用主动维护/超时」用 10002/10003 |

**关键区分**：

- **应用层主动返回的异常**（服务自己判断的维护、超时、限流）→ 业务信封 + HTTP 200
  - 例：10002 服务维护、10003 RPC 超时、10500 限流、10505 文件过大
- **路由/网关层被动返回的错误**（请求根本没到业务代码）→ 原始 HTTP，无业务信封
  - 例：404 路由不存在、405 Method 错、413 网关体超限、502 网关宕机

> 💡 **判定法**：「这个错误业务代码能 catch 到吗？」能 → 业务信封；不能 → 原始 HTTP。

## 4. 基础响应结构（Standard Envelope）

所有业务 API 响应（HTTP 200）**必须**遵循以下 JSON 结构：

```json
{
  "code": 0,
  "message": "success",
  "data": { "...": "..." },
  "error": null,
  "trace_id": "a1b2c3d4e5f6g7h8",
  "timestamp": 1718660400000
}
```

同时，响应头**必须**返回 `X-Trace-Id: a1b2c3d4e5f6g7h8`（与 body 的 `trace_id` 完全一致）。

### 4.1 字段定义

| 字段名 | 类型 | 必返 | 描述 | 规范说明 |
|---|---|---|---|---|
| `code` | Integer | 是 | 业务状态码 | **核心判断依据**。0 表示成功，非 0 表示失败 |
| `message` | String | 是 | **用户可见**友好提示 | 成功推荐 `"success"`（也允许 `"成功"`，不强制）；失败时为面向用户的简短提示（如「库存不足」），**不**包含技术堆栈 |
| `data` | Any | 是 | 业务数据载荷 | 成功时返回对象/数组/null；**失败时必须为 null**（10700 部分成功例外） |
| `error` | Array\<Object\> \| null | **是** | 错误详情数组 | **必返**。成功时为 `null`；失败时为 `[]` 或 `[{...}]`。元素结构见 §4.2 |
| `trace_id` | String | 是 | 全链路追踪 ID | 必须与透传到数据库 SQL 注释中的 `traceid` 字符串完全一致；推荐 ULID 或 16~32 位 hex；**同时通过响应头 `X-Trace-Id` 返回**（见 §5.4） |
| `timestamp` | Long | 是 | 服务端时间戳（毫秒） | 用于排查客户端/服务端时钟不一致问题 |

### 4.2 error 数组元素结构

> v1.6 变更：新增可选 `code` 字段（错误子类型，字符串），便于前端按错误类型分支处理（参考 GitHub `errors[].code` 与 Stripe `error.code`）。

```json
{
  "field": "email",
  "code": "FORMAT_INVALID",
  "message": "邮箱格式不正确",
  "value": "invalid-email"
}
```

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| `field` | String | 否 | 出错字段名（snake_case）；非表单类错误（如限流）可省略 |
| `code` | String | 否 | **错误子类型**，大写下划线（如 `REQUIRED_MISSING`、`FORMAT_INVALID`、`UNIQUE_CONFLICT`、`OUT_OF_STOCK`）；前端可据此分支处理，无需解析 message |
| `message` | String | 是 | 该错误的描述（可面向开发者，比顶层 `message` 更具体） |
| `value` | Any | 否 | 客户端提交的原始值；敏感字段（密码）禁止返回 |

**常用 `code` 子类型参考清单**（不限于此，业务可自定义）：

| 子类型 code | 顶层 code | 含义 |
|---|---|---|
| `REQUIRED_MISSING` | 10101 | 必填字段缺失 |
| `FORMAT_INVALID` | 10102 | 格式不正确（邮箱/手机/日期） |
| `LENGTH_INVALID` | 10102 | 长度越界 |
| `RANGE_INVALID` | 10106 | 数值越界（金额负数、超出区间） |
| `UNIQUE_CONFLICT` | 10401 | 唯一性冲突 |
| `STATE_CONFLICT` | 10402 | 状态机流转失败 |
| `OUT_OF_STOCK` | 10402 | 库存不足 |
| `NOT_FOUND` | 10400 | 资源不存在 |

## 5. 字段命名与序列化约定

### 5.1 命名风格

- 全局统一 **snake_case**（小写下划线），适用于：
  - 响应信封字段（`trace_id`、`page_size`）
  - `data` 内部所有业务字段（`user_id`、`created_at`、`is_active`）
  - `error` 元素的 `field` 值
- 禁止出现 `traceId`、`userId`、`pageSize` 等驼峰命名
- 后端 Java 对象通过 Jackson `@JsonProperty("snake_name")` 或全局 `PropertyNamingStrategy.SNAKE_CASE` 输出

### 5.2 大整数与精度（v1.6 修订：金额统一 String）

为避免 JavaScript Number 精度上限（2^53 - 1）导致 ID 精度丢失，以及金额计算精度问题：

| 字段类型 | 序列化方式 | 示例 | 说明 |
|---|---|---|---|
| 主键 ID、雪花 ID、流水号 | **String** | `"id": "892310293123123"` | 防精度丢失 |
| **金额** | **String（元，2 位小数）** | `"amount": "128.50"` | **v1.6 统一**：禁止 Long（分）/double/float；与支付宝「元字符串」一致 |
| 数量、计数 | Integer/Long | `"total": 100` | - |
| 时间 | Long 毫秒时间戳 或 ISO-8601 字符串 | `"created_at": 1718660400000` | - |

**金额字段实现要求**：

- 永远是 String，永远 2 位小数（如 `"0.50"`、`"99.00"`、`"12345.67"`）
- 服务端用 `BigDecimal`，序列化时调用 `setScale(2, RoundingMode.HALF_UP).toPlainString()`
- 前端展示前可调用 `parseFloat`（金额范围 < 2^53 不会丢精度），但**参与运算必须转回字符串或 Decimal 库**（如 `decimal.js`），禁止直接用 float 累加
- 币种默认 CNY；多币种接口需带 `currency` 字段（ISO 4217，如 `"CNY"`、`"USD"`）

> ⚠️ **禁止**：`"amount": 128.5`（数字）、`"amount": 12850`（分整数）、`"amount": "128.5"`（不足 2 位）。

### 5.3 空值约定

- 不存在的字段：**省略**（不要返回 `"field": null` 占位）
- 明确为空：`null`
- 空集合：`[]`（不要返回 `null`），降低前端 NPE 风险

> 例外：信封字段 `data` 和 `error` 永远出现（必返），值可能为 `null`。

### 5.4 HTTP Header 规范（v1.6 新增）

向 Stripe / GitHub / AWS 对齐，统一以下响应头：

| 响应头 | 何时返回 | 示例 | 说明 |
|---|---|---|---|
| `X-Trace-Id` | **所有响应必返** | `a1b2c3d4e5f6g7h8` | 与 body `trace_id` 完全一致；body 损坏时仍可关联（参考 AWS `x-amzn-RequestId`、阿里云 `x-acs-request-id`、GitHub `X-GitHub-Request-Id`） |
| `X-RateLimit-Limit` | 限流接口的所有响应 | `100` | 当前窗口配额上限 |
| `X-RateLimit-Remaining` | 限流接口的所有响应 | `42` | 当前窗口剩余配额 |
| `X-RateLimit-Reset` | 限流接口的所有响应 | `1718660460` | 窗口重置时间（Unix 秒，UTC） |
| `Retry-After` | 10500 / 10502 错误响应 | `30` | 建议客户端等待秒数（RFC 7231） |
| `Sunset` | 即将下线的接口 | `Sat, 31 Dec 2026 23:59:59 GMT` | 接口废弃日期（RFC 8594） |
| `Deprecation` | 即将下线的接口 | `true` | 接口已废弃标记（RFC 8594） |

**请求头**：

| 请求头 | 适用接口 | 说明 |
|---|---|---|
| `Idempotency-Key` | 所有写操作（POST/PUT/DELETE） | 客户端生成的唯一键（UUID 推荐），同 key 重复提交返回首次结果（详见下文） |
| `X-Trace-Id` | 可选 | 客户端可主动透传；未传时服务端生成 |

**幂等键（Idempotency-Key）规范**：

参考 Stripe `Idempotency-Key`：

- **适用范围**：所有写操作（POST / PUT / PATCH / DELETE）；GET 天然幂等无需传
- **键值**：客户端生成的 UUID 或唯一字符串（长度 ≤ 255）
- **有效期**：服务端保留首次结果至少 **48 小时**，过期后允许复用
- **行为**：同 key + 同请求体 → 返回首次结果（含 code/message/data）；同 key + 不同请求体 → 返回 10501「请勿重复提交」
- **存储**：Redis 推荐，key 格式 `idem:{api_path}:{idempotency_key}`，TTL 48h
- **状态码**：首次成功 → `code=0`；首次失败 → `code=10xxx`；重复请求 → 完全返回首次的响应

> 💡 **前端最佳实践**：表单提交按钮点击时生成 `Idempotency-Key = uuid()`，存入 session；网络重试或用户多次点击同一 key 即可避免重复下单。

## 6. 分页响应规范

凡是返回列表数据的接口，**必须**使用统一的分页信封。本规范定义三种分页模式，接口设计时按 §6.6 的决策表选择一种，**严禁**同一接口混用多种模式。

### 6.0 三种分页模式总览

| 模式 | 典型代表 | 适用场景 | 是否支持跳页 | 深翻页性能 | 数据一致性 |
|---|---|---|---|---|---|
| **页码分页（Offset）** | GitHub REST API、Google、AWS、传统后台管理 | 后台表格、需要跳页、数据量可控 | ✅ 支持 | ❌ 深 `OFFSET` 慢 | 弱（数据变动会移位） |
| **游标分页（Cursor）** | Twitter/X、Facebook GraphQL Connection、Slack、Stripe | 移动端无限滚动、Feed 流、海量数据 | ❌ 仅「下一页/上一页」 | ✅ 稳定 | 强（基于游标锁定位置） |
| **键集分页（Keyset）** | Shopify、GitHub（部分）、ES `search_after` | 高性能列表、按主键/时间倒序 | ❌ 仅「下一页」 | ✅ 最佳 | 强（基于排序键唯一性） |

### 6.1 通用请求参数

各模式共享「排序」参数；每页条数参数按模式选用，**禁止混用**。

| 参数 | 类型 | 默认 | 适用模式 | 说明 |
|---|---|---|---|---|
| `page_size` | Integer | 20 | **仅 Offset**（§6.2） | 每页条数；服务端配置最大上限（如 100），超出会被裁剪并回显实际值 |
| `limit` | Integer | 20 | **仅 Cursor / Keyset**（§6.3 / §6.4） | 每页条数；同上 |
| `sort` | String | - | 全部 | 排序字段，格式 `field:asc` 或 `field:desc`，多字段逗号分隔；Cursor / Keyset 要求排序字段稳定且唯一 |

> ⚠️ **禁止混用**：Offset 模式只接受 `page_size`，Cursor / Keyset 模式只接受 `limit`。同时传两者按模式默认值忽略另一个，并在响应头/日志告警。

### 6.1.1 通用可选响应字段：`summary`

三种分页模式均支持在 `data` 内返回 **可选** 的 `summary` 字段，承载**当前查询条件（去掉分页约束）下**的聚合统计信息（如总额、均值、计数）。常见于后台报表、订单列表底部合计行等场景。

**示例：**

```json
{
  "data": {
    "list": [
      { "id": "1", "name": "...", "money": "100.50" },
      { "id": "2", "name": "...", "money": "87.30" }
    ],
    "total": 128,
    "summary": {
      "sum_money": "128723.23",
      "avg_money": "187.30",
      "max_money": "5800.00",
      "min_money": "0.01"
    },
    "page": 1,
    "page_size": 20,
    "has_more": true,
    "summary": null
  }
}
```

**约定：**

| 维度 | 规则 |
|---|---|
| 必返性 | **可选**；无聚合需求时**省略该字段**（不要返回 `null` 占位） |
| 计算范围 | 基于当前过滤条件（去掉分页 `page`/`cursor`/`last_id` 等约束）的全量结果集，**不是当前页** |
| 字段命名 | 内部字段统一 snake_case（如 `sum_money`、`avg_amount`）；遵循 §5.1 |
| 类型 | 金额类按 §5.2 序列化为 **String（元，2 位小数）**；禁止 double |
| 触发方式 | **本规范不约束**；是否返回 `summary`、如何触发（始终返回 / 按接口约定 / 按查询参数）由各业务线自行决定 |
| 一致性 | `summary` 与 `list`/`total` 不要求强一致（可能来自不同查询）；前端不得用 `summary.sum_money / total` 反推均价 |

> 💡 **典型场景**：订单列表页底部「合计 ¥XXX / 平均 ¥YYY / 共 N 笔」、运营报表的 KPI 概览。

### 6.2 模式一：页码分页（Offset Pagination）

最直观、可跳页，适合后台管理类系统。

**请求参数：**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` | Integer | 1 | 页码，从 1 开始；非法值（≤0）按默认值处理 |
| `page_size` | Integer | 20 | 每页条数 |

**响应结构：**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      { "id": "1", "name": "..." },
      { "id": "2", "name": "..." }
    ],
    "total": 128,
    "page": 1,
    "page_size": 20,
    "has_more": true,
    "summary": null
  },
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| `list` | Array | 是 | 当前页数据；空结果必须返回 `[]` |
| `total` | Long | 是 | 符合查询条件的总记录数（非总页数） |
| `page` | Integer | 是 | 当前页码（回显） |
| `page_size` | Integer | 是 | 当前每页条数（回显，可能被服务端裁剪到上限） |
| `has_more` | Boolean | 是 | 是否还有下一页，避免前端 `page * page_size < total` 的边界计算 |
| `summary` | Object \| null | 是 | 聚合统计；无聚合需求时为 `null`，详见 §6.1.1 |

> ⚠️ **深翻页警告**：当 `OFFSET` 较大（如超过十万级）时，MySQL/PG 性能会显著下降。深翻页场景应改用 §6.4 键集分页。

### 6.3 模式二：游标分页（Cursor Pagination）

服务端下发不透明（opaque）游标字符串，客户端原样回传以获取下一页。客户端无需关心游标内部结构，适合无限滚动与高并发 Feed 流。

**请求参数：**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `cursor` | String | - | 游标，首次请求不传；后续请求原样回传 `next_cursor` 的值 |
| `limit` | Integer | 20 | 每页条数 |

**响应结构：**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      { "id": "1", "name": "..." },
      { "id": "2", "name": "..." }
    ],
    "next_cursor": "eyJpZCI6IjIwIiwidHMiOjE3MTg2NjA0MDAwMDB9",
    "has_more": true,
    "summary": null
  },
  "error": null,
  "trace_id": "c0a8010116983728002",
  "timestamp": 1718660400000
}
```

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| `list` | Array | 是 | 当前页数据；空结果返回 `[]` |
| `next_cursor` | String \| null | 是 | 下一页游标；**字段必返**，已到末页时值为 `null` |
| `has_more` | Boolean | 是 | 是否还有下一页（与 `next_cursor !== null` 等价，前端更易判断） |
| `summary` | Object \| null | 是 | 聚合统计；无聚合需求时为 `null`，详见 §6.1.1 |

**游标实现要求：**

- 必须 **opaque**：客户端不可解析（推荐 Base64(JSON({last_id, sort_key, ...}))，可加密）
- 必须 **无状态可重建**：服务端能从游标解析出查询起点，不依赖 session
- 必须 **校验完整性**：解码失败/签名错误时返回 10100，不静默回退到第一页
- 排序字段必须 **稳定且唯一**（如 `(created_at DESC, id DESC)`，避免分页错位）

> ❌ **不返回 `total`**：游标分页场景下精确 `total` 代价过高；如需「约 X 条」，单独提供估算接口。

### 6.4 模式三：键集分页（Keyset / Search-After）

客户端基于上一页最后一条记录的排序键直接构造下一次请求，性能最佳（走索引范围扫描），但要求排序键稳定唯一。

**请求参数：**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `last_id` | String | - | 上一页最后一条记录的主键 ID |
| `last_value` | String | - | 上一页最后一条记录的排序键值（**统一序列化为 String**，复合排序时按 `sort` 字段顺序拼接） |
| `limit` | Integer | 20 | 每页条数 |

> 也可将 `last_id` + `last_value` 合并为单一 `after` 字段（ES `search_after` 风格），由业务决定。`last_value` 统一用 String 是为了 Schema 化与客户端原样回传，避免数值类型推断歧义。

**响应结构：**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      { "id": "21", "name": "...", "created_at": 1718660400000 },
      { "id": "22", "name": "...", "created_at": 1718660300000 }
    ],
    "last_id": "22",
    "last_value": "1718660300000",
    "has_more": true,
    "summary": null
  },
  "error": null,
  "trace_id": "c0a8010116983728003",
  "timestamp": 1718660400000
}
```

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| `list` | Array | 是 | 当前页数据；空结果返回 `[]` |
| `last_id` | String \| null | 是 | 本页最后一条记录的主键；**字段必返**，空页时值为 `null` |
| `last_value` | String \| null | 否 | 本页最后一条记录的排序键值（统一 String）；空页或无排序时省略 |
| `has_more` | Boolean | 是 | 是否还有下一页（判断依据：本页条数 = `limit` 时通常为 true） |
| `summary` | Object \| null | 是 | 聚合统计；无聚合需求时为 `null`，详见 §6.1.1 |

**实现要点：**

- 排序键必须 **唯一**（如单字段不唯一，必须叠加 `id` 作为 tie-breaker）
- 客户端逻辑：把响应里的 `last_id`/`last_value` 原样作为下次请求的入参
- 不返回 `total`（同游标分页理由）

### 6.5 不分页的列表接口

全量字典、配置项等数据量小且需要一次性返回的接口，**不**使用上述任何分页信封，直接：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "id": "1", "name": "..." },
    { "id": "2", "name": "..." }
  ],
  "error": null,
  "trace_id": "c0a8010116983728007",
  "timestamp": 1718660400000
}
```

### 6.6 选型决策表

| 场景特征 | 推荐模式 |
|---|---|
| 后台管理系统、用户需跳转到第 N 页、数据量 < 10 万 | **页码分页（Offset）** |
| 移动端/前端无限滚动、Feed 流、社交时间线、列表实时变动 | **游标分页（Cursor）** |
| 高性能列表（>100 万行）、固定排序的导出/批处理、深翻页 | **键集分页（Keyset）** |
| 数据量小（< 500 条）、需要一次返回 | **不分页**（§6.5） |

**决策细则：**

1. 是否需要跳页？是 → Offset；否 → 继续 2
2. 是否接受 opaque cursor（客户端不可解析）？是 → Cursor；否 → 继续 3
3. 排序键是否稳定唯一？是 → Keyset；否 → 退回 Offset 并接受性能代价

## 7. 错误码规范

错误码采用 **5 位数字**格式：`ABCCC`。

- **A**（万位）：错误来源级（1=系统/通用，2~9=业务域预留，见 §7.1）
- **B**（千位）：模块/类别级（见 §7.2）
- **CCC**（后三位）：具体错误（001~999）

### 7.1 业务模块（A 位）认领清单

| A 位 | 模块 | 状态 |
|---|---|---|
| 1 | 系统/通用（10xxx，本文档定义） | ✅ 已用 |
| 2~9 | 预留业务域 | 🟡 待认领（业务线 PR 时在此登记，避免冲突） |

### 7.2 类别（B 位）说明

适用于所有 A 位（业务域）：

| B 位 | 类别 | 语义 |
|---|---|---|
| 0 | 系统/基础设施 | 服务端兜底异常（维护、超时、第三方故障） |
| 1 | 请求/参数校验 | 客户端入参错误（必填缺失、格式、业务规则） |
| 2 | 认证（AuthN） | 身份识别失败（未登录、Token 失效） |
| 3 | 权限（AuthZ） | 权限校验失败（无角色、数据权限、签名） |
| 4 | 资源/数据状态 | 资源不存在或状态冲突（重复、状态机、锁） |
| 5 | 流量控制与文件上传 | 限流 / 熔断 / 幂等 / 文件类型/体积/内容（v1.7：原 106xxx 并入） |
| 6 | 业务自定义 | **业务线自定义错误**（适配当前业务，由各业务线登记认领，详见 §7.10） |
| 7 | 业务混合结果 | 批量操作部分成功 |

### 7.3 参数校验类与资源类的判定原则

`10106 业务规则校验失败` 与 `104xx 资源/数据状态` 的边界：

| 判定条件 | 归类 | 示例 |
|---|---|---|
| **不查数据库**即可判定 | 101xx（参数校验） | 结束时间早于开始时间；金额为负数；手机号格式错误 |
| **必须查数据库**才能判定 | 104xx（资源/状态） | 订单已取消不能发货；库存不足；用户名已存在 |

> 实践提示：能在 Controller 的 `@Valid` 阶段拦截的，一律 101xx；需要进入 Service 层查询后判断的，走 104xx。

### 7.4 系统与基础设施类（10xxx）

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10001 | 系统繁忙，请稍后再试 | **兜底**。未捕获异常（NPE、DB 连接失败、索引越界） | 提示用户重试；上报监控 |
| 10002 | 服务暂停维护 | **应用层主动**进入维护模式（区别于网关 503） | 引导用户查看公告 |
| 10003 | 服务调用超时 | **应用层主动**判定超时（RPC 下游超时；区别于网关 504） | 提示重试；不自动重试写操作 |
| 10004 | 第三方服务异常 | 调用外部 API（微信支付、短信网关）失败 | 提示稍后重试；联系客服 |
| 10005 | 中间件服务异常 | Redis 连接失败、MQ 投递失败 | 同 10001 |

### 7.5 请求与参数校验类（101xxx）

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10100 | 请求参数错误 | **通用**参数错误，配合 `error` 数组展示字段校验 | 渲染 `error` 数组到对应表单字段 |
| 10101 | 必填参数缺失 | `@NotNull` / `@NotBlank` 失败 | 高亮缺失字段 |
| 10102 | 参数格式错误 | 邮箱/手机号正则失败；日期解析失败 | 高亮字段并提示格式 |
| 10103 | 请求体格式错误 | JSON 解析失败（Malformed JSON） | 提示「请求格式异常」 |

> ⚠️ **v1.5 变更**：删除原 `10104 请求方法不支持` 与 `10105 媒体类型不支持`（路由层拦截，永久弃用）。

### 7.6 认证类（102xxx，AuthN）

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10200 | 用户未登录 | Token 缺失/格式错误 | 跳转登录页 |
| 10201 | 登录凭证已过期 | Token 过期 | 尝试 refresh；失败则跳登录页 |
| 10202 | 登录凭证无效 | Token 签名失败/被伪造 | 清除本地凭证，跳登录页 |
| 10203 | 账号密码错误 | 登录接口专用 | 表单内提示 |
| 10204 | 账号已被冻结 | 账号 Disabled/Locked | 引导联系客服/申诉 |
| 10205 | 账号在异地登录 | 被踢下线（Kickout） | 弹窗提示并跳登录页 |
| 10206 | 验证码错误 | 图形/短信验证码失败 | 刷新验证码并提示 |

> 💡 **OAuth 2.0 / RFC 6750 兼容映射**（对接第三方 OAuth 时参考）：
> - `invalid_token` → 10200 或 10202（视情况）
> - `expired_token` → 10201
> - `insufficient_scope` → 10300

### 7.7 权限类（103xxx，AuthZ）

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10300 | 无权限访问 | **通用**鉴权失败（缺少角色/权限点） | 提示无权限；隐藏对应入口 |
| 10301 | 数据权限不足 | 有功能权限但无权操作该行数据 | 提示无权访问该数据 |
| 10302 | 签名验证失败 | 接口签名（防篡改）校验不通过 | 检查签名算法 |
| 10303 | IP 限制访问 | IP 白名单拦截 | 联系管理员 |

### 7.8 资源与数据类（104xxx）

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10400 | 请求资源不存在 | **通用**。ID 查询为空、URL 路径错误 | 提示资源不存在；列表场景渲染空状态 |
| 10401 | 数据已存在 | 唯一性冲突（Duplicate Key） | 高亮冲突字段 |
| 10402 | 数据状态冲突 | 状态机流转失败（订单已取消不能发货） | 提示当前状态及允许的操作 |
| 10403 | 数据被锁定 | 悲观锁/分布式锁失败 | 提示资源繁忙，稍后重试 |
| 10404 | 数据完整性约束失败 | 级联删除失败、外键约束 | 提示操作失败，联系管理员 |

### 7.9 流量控制与文件上传类（105xxx）

> v1.7 变更：原「文件与上传类 106xxx」并入本节（10503-10506），106xxx 段释放给业务自定义错误（见 §7.10）。
> v1.6 新增：10500 / 10502 响应必须通过 HTTP Header 暴露限流元数据（`Retry-After`、`X-RateLimit-*`），详见 §5.4。`data` 保持 `null`，不破坏信封规范。

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10500 | 请求过于频繁 | **通用**限流（QPS） | 读 `Retry-After` 头指数退避重试；前端节流 |
| 10501 | 请勿重复提交 | 幂等性校验失败（`Idempotency-Key` 重复但请求体不同） | 禁用提交按钮；提示已提交 |
| 10502 | 服务降级 | 熔断器开启 | 读 `Retry-After` 头等待降级结束 |
| 10503 | 文件上传失败 | IO 异常/存储服务异常（原 10600） | 提示重试 |
| 10504 | 文件类型不支持 | 后缀名/Magic Number 校验失败（原 10601） | 提示支持的类型列表 |
| 10505 | 文件体积过大 | **应用层主动**判定超限（区别于网关 413，原 10602） | 提示最大体积限制 |
| 10506 | 文件内容为空 | 上传了空文件（原 10603） | 提示选择有效文件 |

**10500 / 10502 响应示例：**

```http
HTTP/1.1 200 OK
X-Trace-Id: c0a8010116983728006
Retry-After: 30
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718660460
Content-Type: application/json

{
  "code": 10500,
  "message": "请求过于频繁，请稍后再试",
  "data": null,
  "error": null,
  "trace_id": "c0a8010116983728006",
  "timestamp": 1718660400000
}
```

### 7.10 业务类错误（106xxx，业务自定义）

> v1.7 变更：本段原为「文件与上传类」，已并入 §7.9（10503-10506）。**106xxx 现释放为业务自定义错误段**，由各业务线按当前业务需要自行登记使用。

**适用场景**：通用错误码（10xxx ~ 105xxx）无法精确表达的业务规则错误。例如：

- 交易域：余额不足、超卖、风控拦截、优惠码失效
- 履约域：超出发货范围、配送时段关闭、收货人黑名单
- 营销域：活动未开始 / 已结束、参与次数达上限、会员等级不符
- 农业域（漫云科技）：农事操作违反农时、地块轮作冲突、设备离线告警阈值

**登记规范**（避免业务线之间冲突）：

| 错误码 | 业务域 | 描述 | 适用场景 | 前端处理建议 | 登记人 / 日期 |
|---|---|---|---|---|---|
| 10600 | [业务域 A] | [业务错误描述] | [触发条件] | [前端处理] | [登记人 / YYYY-MM-DD] |
| 10601 | ... | ... | ... | ... | ... |

> **认领流程**：业务线 PR 时在本表登记「错误码 + 业务域 + 描述」，避免与他人冲突；同一错误码不可复用，废弃后保留空位不重新分配。

**与 104xx 资源/状态类的边界**：

| 判定条件 | 归类 | 示例 |
|---|---|---|
| 通用资源状态冲突（与具体业务无关） | 104xx | 订单已取消不能发货；库存不足 |
| 业务规则拒绝（含业务语义、跨实体判定） | 106xx | 风控拦截；活动参与次数上限；农时冲突 |

> **实践提示**：通用错误码能覆盖的优先用 10xxx ~ 105xxx；只有强业务语义的错误才落到 106xx，并在本表登记。

### 7.11 业务混合结果类（107xxx）

适用于批量操作中「部分成功 + 部分失败」的场景。

| 错误码 | 描述 | 适用场景 | 前端处理建议 |
|---|---|---|---|
| 10700 | 部分成功 | **同步批量操作** N 条中 M 条失败（M < N，且 N ≤ 100） | 渲染 `data.failures` 列表；提示「N 条中 M 条失败」 |

**同步模式响应结构**（N ≤ 100）：

```json
{
  "code": 10700,
  "message": "部分操作失败",
  "data": {
    "success_count": 7,
    "failure_count": 3,
    "failures": [
      { "id": "101", "reason": "状态冲突", "code": 10402 },
      { "id": "103", "reason": "库存不足", "code": 10402 }
    ]
  },
  "error": null,
  "trace_id": "c0a8010116983728003",
  "timestamp": 1718660400000
}
```

**`failures` 数组元素结构：**

| 字段 | 类型 | 必返 | 说明 |
|---|---|---|---|
| `id` | String | 是 | 失败项的主键 ID（按 §5.2 序列化为 String） |
| `reason` | String | 是 | 该项失败原因（简短，可面向用户） |
| `code` | Integer | 否 | 该项的具体业务错误码（如 10402），便于前端分类展示 |

#### 7.11.1 异步 Job 模式（v1.6 新增，N > 100 必须用）

参考 Stripe `/v1/file_links` 异步、Slack `async.jobs`、GitHub Actions 的 job 模式：

**触发条件**：批量操作 N > **100** 时**禁止**用同步 10700，**必须**用异步 Job 模式（避免 HTTP 超时、连接占用）。

**异步提交流程**：

```
1. 客户端 POST /v1/orders/batch { items: [...] }  带 Idempotency-Key
2. 服务端立即返回 job_id：
   { "code": 0, "data": { "job_id": "job_abc123", "status": "queued" } }
3. 客户端轮询 GET /v1/jobs/job_abc123 直到 status=completed/failed
4. 或服务端通过 Webhook 回调通知
```

**Job 创建响应**（立即返回）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "job_id": "job_abc123",
    "status": "queued",
    "poll_url": "/v1/jobs/job_abc123",
    "estimated_seconds": 60
  },
  "error": null,
  "trace_id": "c0a8010116983728008",
  "timestamp": 1718660400000
}
```

**Job 状态枚举**：

| `status` | 含义 |
|---|---|
| `queued` | 已入队，等待处理 |
| `processing` | 正在处理 |
| `completed` | 处理完成（含部分失败） |
| `failed` | 任务整体失败（系统异常） |
| `cancelled` | 用户取消 |

**Job 查询响应**（status=completed）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "job_id": "job_abc123",
    "status": "completed",
    "success_count": 187,
    "failure_count": 13,
    "failures": [
      { "id": "1001", "reason": "状态冲突", "code": 10402 }
    ],
    "result_url": "/v1/jobs/job_abc123/result",
    "created_at": 1718660400000,
    "completed_at": 1718660460000
  },
  "error": null,
  "trace_id": "c0a8010116983728009",
  "timestamp": 1718660460000
}
```

> 💡 **轮询建议**：客户端采用指数退避（首次 2s → 5s → 10s → 最大 30s），避免空轮询打爆服务。
> 💡 **Webhook 优先**：能配 Webhook 的场景应优先回调，轮询作为兜底。

## 8. 示例

### 8.1 成功响应

```http
HTTP/1.1 200 OK
X-Trace-Id: c0a8010116983728001
Content-Type: application/json

{
  "code": 0,
  "message": "success",
  "data": {
    "id": "892310293123123",
    "name": "Admin User",
    "amount": "128.50",
    "currency": "CNY",
    "created_at": 1718660400000
  },
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

### 8.2 失败响应（带字段校验错误 + 子类型 code）

```http
HTTP/1.1 200 OK
X-Trace-Id: c0a8010116983728002
Content-Type: application/json

{
  "code": 10100,
  "message": "请求参数错误",
  "data": null,
  "error": [
    {
      "field": "email",
      "code": "FORMAT_INVALID",
      "message": "邮箱格式不正确",
      "value": "invalid-email"
    },
    {
      "field": "password",
      "code": "LENGTH_INVALID",
      "message": "密码长度不能少于 6 位"
    }
  ],
  "trace_id": "c0a8010116983728002",
  "timestamp": 1718660400000
}
```

### 8.3 分页响应（Offset 模式）

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      { "id": "1", "name": "Alice" },
      { "id": "2", "name": "Bob" }
    ],
    "total": 128,
    "page": 1,
    "page_size": 20,
    "has_more": true,
    "summary": null
  },
  "error": null,
  "trace_id": "c0a8010116983728004",
  "timestamp": 1718660400000
}
```

### 8.4 部分成功响应（同步批量，N ≤ 100）

```json
{
  "code": 10700,
  "message": "部分操作失败",
  "data": {
    "success_count": 7,
    "failure_count": 3,
    "failures": [
      { "id": "101", "reason": "状态冲突", "code": 10402 },
      { "id": "103", "reason": "库存不足", "code": 10402 },
      { "id": "107", "reason": "权限不足", "code": 10300 }
    ]
  },
  "error": null,
  "trace_id": "c0a8010116983728005",
  "timestamp": 1718660400000
}
```

### 8.5 限流响应（带 Header 元数据）

```http
HTTP/1.1 200 OK
X-Trace-Id: c0a8010116983728006
Retry-After: 30
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718660460
Content-Type: application/json

{
  "code": 10500,
  "message": "请求过于频繁，请稍后再试",
  "data": null,
  "error": null,
  "trace_id": "c0a8010116983728006",
  "timestamp": 1718660400000
}
```

### 8.6 异步 Job 提交（N > 100）

```http
POST /v1/orders/batch
Idempotency-Key: 7c8d2e1a-9f3b-4d6e-8a2c-1b5e9f0d3a7b

{
  "items": [/* 500 条 */]
}
```

```http
HTTP/1.1 200 OK
X-Trace-Id: c0a8010116983728008
Content-Type: application/json

{
  "code": 0,
  "message": "success",
  "data": {
    "job_id": "job_abc123",
    "status": "queued",
    "poll_url": "/v1/jobs/job_abc123",
    "estimated_seconds": 60
  },
  "error": null,
  "trace_id": "c0a8010116983728008",
  "timestamp": 1718660400000
}
```

## 9. JSON Schema

本节提供用于自动化验证的 Schema。

> v1.6 变更：§9.2 `error.items` 新增可选 `code` 字段（错误子类型字符串）。

### 9.1 成功响应 Schema（code = 0）

成功时，`code` 必须为 0，`data` 可以是任意类型（含 null），`error` 必须为 null。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Success Response Schema",
  "type": "object",
  "required": ["code", "message", "data", "error", "trace_id", "timestamp"],
  "properties": {
    "code": {
      "const": 0,
      "description": "成功时状态码固定为 0"
    },
    "message": {
      "type": "string",
      "enum": ["success", "成功"],
      "description": "成功推荐 success，也允许「成功」，不强制"
    },
    "data": {
      "description": "业务数据载荷，任意类型"
    },
    "error": {
      "type": "null",
      "description": "成功时 error 必须为 null"
    },
    "trace_id": {
      "type": "string",
      "minLength": 1,
      "description": "全链路追踪 ID；同时通过 X-Trace-Id 响应头返回"
    },
    "timestamp": {
      "type": "integer",
      "minimum": 0,
      "description": "服务端时间戳（毫秒）"
    }
  }
}
```

### 9.2 失败响应 Schema（code ≠ 0 且 ≠ 10700）

失败时，`code` 为非 0、非 10700 的整数，`data` 必须为 null，`error` 为数组或 null。

> v1.6：`error.items` 新增可选 `code` 字段（错误子类型，大写下划线字符串）。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Failure Response Schema",
  "type": "object",
  "required": ["code", "message", "data", "error", "trace_id", "timestamp"],
  "properties": {
    "code": {
      "type": "integer",
      "not": { "enum": [0, 10700] },
      "description": "失败时状态码为非 0、非 10700 的整数"
    },
    "message": {
      "type": "string",
      "minLength": 1,
      "description": "面向用户的错误提示"
    },
    "data": {
      "type": "null",
      "description": "失败时 data 必须为 null"
    },
    "error": {
      "type": ["array", "null"],
      "items": {
        "type": "object",
        "required": ["message"],
        "properties": {
          "field": { "type": "string" },
          "code": {
            "type": "string",
            "pattern": "^[A-Z][A-Z0-9_]*$",
            "description": "错误子类型，大写下划线，如 FORMAT_INVALID / UNIQUE_CONFLICT"
          },
          "message": { "type": "string" },
          "value": { "description": "客户端提交的原始值，任意类型；可选" }
        }
      },
      "description": "错误详情数组；单条错误也用数组"
    },
    "trace_id": {
      "type": "string",
      "minLength": 1
    },
    "timestamp": {
      "type": "integer",
      "minimum": 0
    }
  }
}
```

### 9.3 部分成功响应 Schema（code = 10700）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Partial Success Response Schema",
  "type": "object",
  "required": ["code", "message", "data", "error", "trace_id", "timestamp"],
  "properties": {
    "code": { "const": 10700 },
    "message": { "type": "string" },
    "data": {
      "type": "object",
      "required": ["success_count", "failure_count", "failures"],
      "properties": {
        "success_count": { "type": "integer", "minimum": 0 },
        "failure_count": { "type": "integer", "minimum": 1 },
        "failures": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["id", "reason"],
            "properties": {
              "id": { "type": "string" },
              "reason": { "type": "string" },
              "code": { "type": "integer" }
            }
          }
        }
      }
    },
    "error": { "type": "null" },
    "trace_id": { "type": "string", "minLength": 1 },
    "timestamp": { "type": "integer", "minimum": 0 }
  }
}
```

### 9.4 Offset 分页响应 Schema（data 部分）

适用于 §6.2 页码分页。Envelope 部分复用 §9.1（成功信封），此处仅约束 `data`。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Offset Pagination Data Schema",
  "type": "object",
  "required": ["list", "total", "page", "page_size", "has_more", "summary"],
  "properties": {
    "list": {
      "type": "array",
      "description": "当前页数据；空结果必须为 []"
    },
    "total": { "type": "integer", "minimum": 0 },
    "page": { "type": "integer", "minimum": 1 },
    "page_size": { "type": "integer", "minimum": 1 },
    "has_more": { "type": "boolean" },
    "summary": {
      "type": ["object", "null"],
      "description": "可选聚合统计；无聚合需求时为 null，详见 §6.1.1"
    }
  }
}
```

### 9.5 Cursor 分页响应 Schema（data 部分）

适用于 §6.3 游标分页。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Cursor Pagination Data Schema",
  "type": "object",
  "required": ["list", "next_cursor", "has_more", "summary"],
  "properties": {
    "list": { "type": "array" },
    "next_cursor": {
      "type": ["string", "null"],
      "description": "下一页 opaque 游标；末页时为 null"
    },
    "has_more": { "type": "boolean" },
    "summary": {
      "type": ["object", "null"],
      "description": "可选聚合统计；无聚合需求时为 null，详见 §6.1.1"
    }
  }
}
```

### 9.6 Keyset 分页响应 Schema（data 部分）

适用于 §6.4 键集分页。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Keyset Pagination Data Schema",
  "type": "object",
  "required": ["list", "last_id", "has_more", "summary"],
  "properties": {
    "list": { "type": "array" },
    "last_id": {
      "type": ["string", "null"],
      "description": "本页最后一条记录主键；空页时为 null"
    },
    "last_value": {
      "type": ["string", "null"],
      "description": "本页最后一条记录的排序键值（统一 String）；空页或无排序时省略"
    },
    "has_more": { "type": "boolean" },
    "summary": {
      "type": ["object", "null"],
      "description": "可选聚合统计；无聚合需求时为 null，详见 §6.1.1"
    }
  }
}
```

### 9.7 异步 Job 提交响应 Schema（data 部分）

适用于 §7.11.1 异步 Job 提交场景。

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Async Job Submit Data Schema",
  "type": "object",
  "required": ["job_id", "status"],
  "properties": {
    "job_id": { "type": "string", "minLength": 1 },
    "status": { "enum": ["queued", "processing", "completed", "failed", "cancelled"] },
    "poll_url": { "type": "string", "description": "轮询地址" },
    "estimated_seconds": { "type": "integer", "minimum": 0 }
  }
}
```

---

## 附录 A：版本变更记录

| 版本 | 日期 | 主要变更 |
|---|---|---|
| v1.3 | - | 初版完整规范 |
| v1.4 | 2026-06-17 | 业务异常统一 HTTP 200；全局 snake_case；新增 `timestamp` / 三种分页模式 / `summary` / 10700 部分成功；`error` 收敛为数组；错误码表新增「前端处理建议」列；修复章节跳号 |
| v1.5 | 2026-06-17 | **自洽性修复**：删除 10104/10105 路由层错误码；§3.3 明确应用层 vs 路由/网关层边界；`error` 字段改为必返；修复 §9.2 JSON Schema 重复 `not` 键语法错误。**Schema 补全**：删除 §7.2 失效的「典型 HTTP 范围」列；新增 §9.4/§9.5/§9.6 三种分页 Schema；§9.2 `error.items` 补 `value` 字段；§9.3 `failures.items` 补严格类型；§6.1 明确 `limit`/`page_size` 按模式选用规则 |
| v1.6 | 2026-06-17 | **大厂对齐**：`trace_id` 同步通过 `X-Trace-Id` 响应头返回；新增 §5.4 HTTP Header 规范（`X-Trace-Id`/`X-RateLimit-*`/`Retry-After`/`Idempotency-Key`）；金额统一 String（元，2 位小数）；新增 §2.1 API 版本控制约定（`/v{N}/`）；新增 §7.6 OAuth 2.0 错误码映射；新增 §7.11.1 异步 Job 模式（N > 100 必须异步）；新增 §9.7 Job Schema；`error[]` 元素新增可选 `code` 子类型；§6.1.1 金额字段示例改 String；限流响应示例改完整 HTTP 包含 Header |

---

## 附录 B：与主流大厂做法对照（v1.6 新增）

供架构评审 / 国际化场景参考。

| 维度 | 本规范 v1.6 | 阿里/支付宝 | 腾讯云 | 微信支付 | Stripe | GitHub | AWS |
|---|---|---|---|---|---|---|---|
| 业务 code 类型 | Integer | String `"10000"` | Integer | String `"SUCCESS"` | 无（HTTP） | 无（HTTP） | String |
| 业务异常 HTTP | 200 | 200 | 200 | 200 | 4xx/5xx | 4xx/5xx | 4xx/5xx |
| 错误详情字段 | `error` (Array) | msg/biz_msg | Error (Object) | 无 | error (Object) | errors (Array) | Error (Object) |
| 子类型 code | ✅ `code` 字符串 | 部分 | 部分 | - | ✅ `error.code` | ✅ `errors[].code` | ✅ Code |
| trace_id 双通道 | ✅ body + `X-Trace-Id` | ✅ `x-acs-request-id` | ✅ `X-TC-RequestId` | ✅ Header + body | ✅ Header + body | ✅ `X-GitHub-Request-Id` | ✅ `x-amzn-RequestId` |
| 限流 Header | ✅ `X-RateLimit-*` + `Retry-After` | 部分 | 部分 | - | ✅ | ✅ | ✅ |
| 幂等键 | ✅ `Idempotency-Key` | 部分 | - | - | ✅ | - | ✅ `ClientRequestToken` |
| 分页 cursor 双向 | ❌（仅向后） | - | - | - | ✅ | 部分 | 部分 |
| 异步 Job 模式 | ✅ §7.11.1 | ✅ | ✅ | - | ✅ | ✅ | ✅ |

**结论**：v1.6 已基本对齐国内大厂标准，并在 trace_id 双通道、幂等键、限流 Header、异步 Job、错误子类型等维度达到或超过部分国外大厂水平。剩余差距（如 cursor 双向、RFC 7807 兼容）不影响中后台系统使用，留待国际化需求时扩展。
