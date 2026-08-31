---
name: mc-doc-api
description: 接口设计文档（API Design Doc）编写 / 修改 / 校验激活。覆盖 RESTful HTTP API 的结构化接口设计文档规范，含资源建模、URL/方法、请求/响应 schema、错误码、鉴权限流、版本与兼容性、Mock 契约。触发词：接口设计、接口设计文档、API 设计文档、API 说明书、接口契约、资源建模、URL 设计、HTTP 方法、请求 schema、响应 schema、错误码、鉴权、限流、幂等、分页、批量、版本管理、向后兼容、OpenAPI、Swagger、Apifox、YApi、Mock、接口评审、契约评审。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: documentation
  tags: [api-design-doc, rest, http, schema, error-code, pagination, idempotency, openapi, apifox, contract, versioning]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - 接口设计文档编写规范 v1.0.md
  related-skills: [mc-doc-prd, mc-doc-arch, mc-doc-dbd, mc-api-spec, mc-java-spec, mc-webui-spec, mc-java-security, mc-perf]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "写一份订单模块的接口设计文档"
    - "接口清单怎么列"
    - "资源建模怎么做"
    - "请求 / 响应 schema 怎么写"
    - "错误码怎么归类"
    - "鉴权方案怎么写进接口文档"
    - "限流方案怎么描述"
    - "分页 / 批量怎么规范"
    - "版本管理 / 向后兼容怎么做"
    - "OpenAPI / Apifox 契约怎么维护"
    - "接口评审流程"
    - "接口设计文档校验什么"
---

# 接口设计文档规范

面向 **RESTful HTTP API** 项目的接口设计文档规范。整合 OpenAPI / 大厂契约管理（Apifox / YApi / Swagger）+ 字节 / 阿里 / 腾讯 / 美团大厂做法。

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 从零写接口设计文档 | 场景一：新建 |
| 列接口清单 | 场景二：接口清单与资源建模 |
| 写单接口详述（URL / 请求 / 响应） | 场景三：单接口详述模板 |
| 归类错误码 | 场景四：错误码与状态码 |
| 写鉴权 / 限流 / 幂等 | 场景五：横切关注点 |
| 选分页 / 批量模式 | 场景六：分页与批量 |
| 维护契约（OpenAPI / Apifox） | 场景七：契约管理 |
| 版本与向后兼容 | 场景八：版本管理 |
| 校验接口设计文档合规 | 场景九：P0 必查 8 项 |
| 评审接口设计文档 | 场景十：评审流程 |
| 退出本规范 | 「退出 mc-doc-api」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| **适用** | RESTful HTTP API 的接口设计文档（资源建模 / 契约说明 / 评审依据） |
| **不适用** | 产品需求（→ mc-doc-prd）、系统总体架构（→ mc-doc-arch）、DB 设计（→ mc-doc-dbd）、Java 代码实现细节（→ mc-java-spec）、API 工程规则（→ mc-api-spec） |
| 推荐工具 | Apifox / YApi / Swagger（在线契约 + Mock）；OpenAPI 3.x（规范文件）；飞书 / Confluence（文档协作） |
| 核心原则 | **资源导向 + 契约先行 + 错误码统一 + 版本兼容 + 可评审可变更** |
| 退出 | 「退出 mc-doc-api」 |

## 2. 全局铁律

1. **首页必有基础信息表 + 修订历史**（倒序，最新在上）
2. **资源建模先于接口列表**：先定义资源（订单 / 用户 / 商品）+ 关系，再列接口
3. **接口清单全局唯一**：每接口必含「编号 / URL / Method / 用途 / 鉴权 / 关联模块」
4. **单接口详述必含 8 要素**：URL + Method + 路径参数 + 查询参数 + 请求体 + 响应体 + 错误码 + 示例
5. **字段命名全局 snake_case**（对齐 mc-api-spec v1.7 §3）：URL 路径参数 / 查询参数 / body 字段；URL 资源段用 kebab-case
6. **业务异常统一 HTTP 200 + 6 字段信封**（对齐 mc-api-spec v1.7 §4）：`code` / `message` / `data` / `error` / `trace_id` / `timestamp`
7. **ID 与金额必须 String**（对齐 mc-api-spec v1.7 §4）：主键 / 雪花 ID / 金额（元）一律 String
8. **写操作必支持 `Idempotency-Key`**：客户端 UUID v4，服务端保留 48h
9. **错误码 5 位数字**：分段 10xxx（参数）/ 102xx（认证）/ 103xx（权限）/ 104xx（资源）/ 105xx（限流 + 文件上传）/ 106xx（业务自定义，本项目登记）/ 107xx（部分成功）。文件上传 10503-10506；业务自定义错误（余额不足 / 风控 / 农时冲突等）走 106xx 并在本设计文档 §6.5 登记
10. **契约与文档同步**：接口设计文档与 Apifox / OpenAPI 一一对应；禁文档更新但契约未更新

## 3. 场景判定

```
当前任务？
├── 从零写接口设计文档            → 场景一：新建
├── 列接口清单 / 资源建模          → 场景二：清单与资源
├── 写单接口详述                  → 场景三：单接口模板
├── 归类错误码                    → 场景四：错误码
├── 写鉴权 / 限流 / 幂等          → 场景五：横切
├── 选分页 / 批量模式             → 场景六：分页与批量
├── 维护 OpenAPI / Apifox 契约    → 场景七：契约
├── 版本与向后兼容                → 场景八：版本管理
├── 校验接口设计文档合规          → 场景九：P0 必查
└── 评审接口设计文档              → 场景十：评审
```

### 场景一：新建接口设计文档

**7 大章节**：① 基础信息 + 修订历史 → ② 总体说明（协议 / 域名 / 鉴权 / 通用 Header）→ ③ 资源建模 → ④ 接口清单 → ⑤ 单接口详述 → ⑥ 错误码体系 → ⑦ 版本与兼容性。

**基础信息表**：项目名 / 代号 / 状态 / 创建日期 / 架构师 / 后端 Lead / 前端 Lead / API Owner / 契约维护人。

**文档状态机**：`草稿 → 评审中 → 已定稿 → 变更中 → 已定稿`。

**详细目录与模板**：见 `./接口设计文档编写规范 v1.0.md` §1、§9.1。

### 场景二：接口清单与资源建模

**资源建模**：先识别业务资源（订单 / 用户 / 商品 / 退款），定义资源关系（订单包含订单明细；退款属于订单），再列 CRUD + 业务动作。

**接口清单**：

| # | 编号 | URL | Method | 用途 | 鉴权 | 模块 | 关联 PRD |
|---|---|---|---|---|---|---|---|
| 1 | API-ORDER-001 | /v1/orders | GET | 订单列表 | user | 订单 | F-ORDER-001 |
| 2 | API-ORDER-002 | /v1/orders | POST | 创建订单 | user | 订单 | F-ORDER-002 |
| 3 | API-ORDER-003 | /v1/orders/{order_id} | GET | 订单详情 | user | 订单 | F-ORDER-001 |
| 4 | API-ORDER-004 | /v1/orders/{order_id}/cancel | POST | 取消订单 | user | 订单 | F-ORDER-003 |
| 5 | API-ORDER-005 | /v1/orders/{order_id}/refund | POST | 申请退款 | user | 订单 | F-ORDER-004 |
| 6 | API-ADMIN-ORDER-001 | /v1/admin/orders | GET | 后台订单查询 | admin | 后台 | F-ADMIN-001 |

**编号规范**：`API-[MODULE]-[NNN]`，模块大写英文 + 3 位序号；废弃接口标 deprecated 但不删除编号。

**详细规则 + 完整模板**：见主规范 §4。

### 场景三：单接口详述模板（核心）

```markdown
### API-ORDER-002: 创建订单

- **URL**：`POST /v1/orders`
- **用途**：用户提交订单
- **鉴权**：用户 Token（`Authorization: Bearer <access_token>`）
- **幂等**：支持（Header `Idempotency-Key: <uuid v4>`，48h 内同 key 返回首次结果）

#### 请求 Header
| Header | 必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer Token |
| Idempotency-Key | 是 | 写操作必传，防重复提交 |
| X-Trace-Id | 否 | 链路追踪 ID（不传则服务端生成） |

#### 请求 Body
| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| address_id | String | 是 | 数字字符串 | 收货地址 ID |
| items | array | 是 | minItems=1 | 商品列表 |
| items[].sku_id | string | 是 | - | SKU 编号 |
| items[].quantity | integer | 是 | 1-99 | 数量 |
| coupon_code | string | 否 | ≤ 32 字符 | 优惠券码 |
| remark | string | 否 | ≤ 200 字符 | 备注 |

#### 请求示例
```json
{
  "address_id": "892310293123123",
  "items": [
    { "sku_id": "SKU-001", "quantity": 2 }
  ],
  "remark": "请尽快发货"
}
```

#### 响应 Body（成功，HTTP 200）
| 字段 | 类型 | 说明 |
|---|---|---|
| code | integer | 0 表示成功 |
| message | string | "success" |
| data.order_id | string | 订单 ID |
| data.order_no | string | 订单业务编号 |
| data.amount | string | 订单金额（元，2 位小数） |
| data.status | string | PENDING |
| data.expire_at | integer | 支付截止时间戳（毫秒） |
| trace_id | string | 链路追踪 ID |
| timestamp | integer | 响应时间戳（毫秒） |

#### 响应示例（成功）
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "order_id": "892310293123123",
    "order_no": "OD202606241234567890",
    "amount": "256.80",
    "status": "PENDING",
    "expire_at": 1718664000000
  },
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

#### 错误码（节选）
| code | 子类型 | 场景 | message |
|---|---|---|---|
| 10101 | REQUIRED_MISSING | 缺少 items | "items 不能为空" |
| 10102 | FORMAT_INVALID | quantity 非数字 | "quantity 格式不正确" |
| 10400 | NOT_FOUND | address_id 不存在 | "收货地址不存在" |
| 10402 | OUT_OF_STOCK | 库存不足 | "商品库存不足" |
| 10501 | - | 重复提交（同 Idempotency-Key 不同 Body） | "请勿重复提交" |

#### 性能预算
- P99 ≤ 500ms（创建订单涉及库存 + 优惠券 + 计价，较耗时）
- 超时：客户端 30s
```

**单接口模板铁律**：① 必有示例（请求 + 响应）② 字段类型 / 必填 / 校验明确 ③ 错误码列高频项 ④ 性能预算量化。

**详细规则 + 完整模板**：见主规范 §5。

### 场景四：错误码与状态码

**应用层 vs 路由层边界**：

| 层 | HTTP 状态 | 含义 | 示例 |
|---|---|---|---|
| 应用层（业务） | 200 | 业务异常统一 200 + 信封 code | 10100 参数错误 |
| 路由 / 网关层 | 401 / 403 / 404 / 405 / 413 / 415 / 502 / 503 / 504 | 路由 / 网关拦截，无信封 | 503 服务不可用 |

**错误码分段**（5 位数字，对齐 mc-api-spec v1.7 §7）：

| 段 | 含义 | 示例 |
|---|---|---|
| 0 | 成功 | 0 |
| 101xx | 参数错误 | 10100 通用 / 10101 必填缺失 / 10102 格式不正确 |
| 102xx | 认证失败 | 10200 未登录 / 10201 Token 失效 / 10202 Token 过期 |
| 103xx | 权限不足 | 10300 通用 / 10301 无数据权限 |
| 104xx | 资源错误 | 10400 不存在 / 10401 唯一冲突 / 10402 状态冲突 / 10403 库存不足 |
| 105xx | 限流 / 重试 / 文件上传 | 10500 限流（Retry-After）/ 10501 重复提交 / 10502 降级 / 10503-10506 文件类 |
| 106xx | **业务自定义**（v1.7） | 业务线登记：余额不足 / 风控 / 农时冲突 / 活动上限等强业务语义，本项目在 §6.5 登记 |
| 107xx | 部分成功 | 10700 批量部分成功（data 非 null） |
| 109xx | 系统错误 | 10900 内部错误 / 10901 三方超时 |

> v1.7 变更：文件上传错误从 106xx 并入 105xx（10503-10506），106xx 释放给业务自定义。

**详细规则 + 完整错误码表 + 106xx 登记模板**：见主规范 §6.2、§6.5。

### 场景五：横切关注点（鉴权 / 限流 / 幂等 / 通用 Header）

**鉴权**：

| 场景 | 方式 | Header |
|---|---|---|
| 用户侧 API | JWT Bearer Token | `Authorization: Bearer <access_token>` |
| 开放 API（对接三方） | HMAC-SHA256 签名 | `X-App-Id` + `X-Signature` + `X-Timestamp` + `X-Nonce` |
| 内部服务调用 | mTLS 或内部 Token | 网关层处理 |

**Token 生命周期**：access_token ≤ 2h；refresh_token ≤ 30d 且一次性；无感刷新走 `POST /v1/auth/refresh`。

**限流**：

| 维度 | 策略 | 响应 |
|---|---|---|
| 用户级 | 100 QPS / 用户 | 10500 + `Retry-After: <seconds>` |
| IP 级 | 1000 QPS / IP | 同上 |
| App 级 | 总 QPS 上限 | 同上 |

**幂等**：所有写操作支持 `Idempotency-Key: <uuid v4>`；服务端 48h 内同 key + 同 Body 返回首次结果；同 key + 不同 Body 返 10501。

**通用 Header**：

| Header | 必返 | 说明 |
|---|---|---|
| X-Trace-Id | 是 | 链路追踪 ID（请求头未传则生成） |
| X-Request-Id | 否 | 请求 ID（客户端生成） |
| X-RateLimit-Remaining | 否 | 剩余配额 |
| Retry-After | 限流时必返 | 重试等待秒数 |

**详细规则**：见主规范 §7。

### 场景六：分页与批量

**三种分页**：

| 场景 | 模式 | 请求参数 | 响应字段 |
|---|---|---|---|
| 后台 / 跳页 / <10 万 | Offset | page + page_size | list / total / page / page_size / has_more / summary |
| Feed / 无限滚动 / 海量 | Cursor | cursor + limit | list / next_cursor / has_more / summary |
| 高性能 / 深翻页 / >100 万 | Keyset | last_id + last_value + limit | list / last_id / last_value / has_more / summary |

> `limit` 与 `page_size` 禁止混用：Offset 用 `page_size`，Cursor/Keyset 用 `limit`。

**批量操作**：N ≤ 100 同步返 10700（部分成功）；N > 100 必须异步 Job（返 `job_id` + `poll_url`，轮询 `GET /v1/jobs/{job_id}`）。

**详细规则**：见主规范 §8。

### 场景七：契约管理（OpenAPI / Apifox）

**契约维护铁律**：

1. 接口设计文档定稿后，**必须**录入 Apifox / YApi / Swagger
2. Apifox 项目结构按模块划分（订单 / 用户 / 商品 / 后台）
3. 每接口必含：完整 schema + 示例 + Mock + 关联错误码
4. 文档变更必同步更新契约（双向校验：文档 ↔ Apifox）
5. 前后端基于 Apifox 在线契约同步开发（前端用 Mock 联调，后端按契约实现）

**OpenAPI 3.x 片段示例**：

```yaml
openapi: 3.0.3
info:
  title: 订单 API
  version: 1.0.0
paths:
  /v1/orders:
    post:
      summary: 创建订单
      operationId: createOrder
      tags: [订单]
      security:
        - bearerAuth: []
      parameters:
        - name: Idempotency-Key
          in: header
          required: true
          schema: { type: string, format: uuid }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CreateOrderRequest' }
      responses:
        '200':
          description: 成功
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ApiResponse_Order' }
```

**详细规则**：见主规范 §10。

### 场景八：版本与向后兼容

**URL 版本**：`/v{N}/`（必含），主版本号递增（v1 → v2），不兼容变更必须升版本。

**兼容性规则**：

| 变更类型 | 是否兼容 | 做法 |
|---|---|---|
| 加可选请求字段 | ✅ 兼容 | 直接加 |
| 加响应字段 | ✅ 兼容 | 直接加 |
| 删字段 | ❌ 不兼容 | 先 deprecated → 下版本删 |
| 改字段类型 | ❌ 不兼容 | 新字段过渡 → 升版本 |
| 改语义 | ❌ 不兼容 | 新接口或升版本 |

**废弃流程**：标 `deprecated: true` + 文档公告 → 邮件 / 群通知 → 观察流量 → 下版本删除。

**详细规则**：见主规范 §11。

### 场景九：校验（P0 必查 8 项）

| # | 检查项 |
|---|---|
| 1 | 首页有完整基础信息表 + 修订历史 |
| 2 | 资源建模章节存在 |
| 3 | 接口清单完整（编号 / URL / Method / 鉴权 / 模块） |
| 4 | 每接口有完整 8 要素（URL/Method/路径/查询/请求体/响应体/错误码/示例） |
| 5 | 字段命名 snake_case（URL 路径参数 / 查询 / body） |
| 6 | 业务异常 HTTP 200 + 6 字段信封 |
| 7 | 写操作支持 Idempotency-Key |
| 8 | 接口清单与 Apifox / OpenAPI 一一对应 |

**P1**：ID 与金额 String / 错误码 5 位分段 / 分页参数不混用 / 限流带 Retry-After / 批量 N>100 走异步 Job / 鉴权方案明确 / 路径深度 ≤ 3 层 / URL 含 /v{N}/。

**P2**：性能预算量化 / 幂等保留期明确 / Token 生命周期明确 / 通用 Header 齐全（X-Trace-Id 等）/ 兼容性策略明确 / deprecated 接口标注。

**P3**：图例完整 / 命名规范 / 引用文档有效 / 错别字。

**完整 P0~P3 checklist**：见主规范 §9。

### 场景十：评审流程

**3 轮评审**：

1. **前后端内审**（前端 Lead + 后端 Lead + API Owner）：资源建模合理 / 接口清单完整 / 字段对齐前端需求
2. **契约评审**（前后端开发 + API Owner + QA）：Apifox 契约 + Mock + 错误码 + 兼容性
3. **架构评审**（架构师 + 后端 Lead + 安全）：跨模块一致 / 鉴权合理 / 版本策略 / 性能预算

**评审前必交**：完整接口设计文档 + Apifox 契约 + Mock 数据 + 关联 PRD。

**评审后必出**：评审纪要 + 待办项（负责人 + 截止日期）+ 修订历史更新（V1.0.X）+ 文档状态 → `已定稿` + Apifox 同步。

**禁**：边评审边改文档 / 评审完不更新 Apifox / 跳过契约评审直接开发。

**详细规则**：见主规范 §12。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./接口设计文档编写规范 v1.0.md` | 详细规则 + 完整模板 + 校验 checklist | v1.0 |
| `../mc-doc-prd/SKILL.md` | 产品需求文档规范（接口需求来源） | v1.0 |
| `../mc-doc-arch/SKILL.md` | 系统架构设计说明书规范 | v1.0 |
| `../mc-doc-dbd/SKILL.md` | 数据库设计说明书规范 | v1.0 |
| `../mc-api-spec/SKILL.md` | API 工程规则（URL/方法/响应信封/错误码/分页/Header） | v1.7 |
| `../mc-java-spec/SKILL.md` | Java 实现规范（Controller / DTO / Jackson） | v1.3 |
| `../mc-webui-spec/SKILL.md` | Vue 前端规范（axios 封装 / 类型定义） | v1.1 |
| `../mc-java-security/SKILL.md` | 安全规范（鉴权 / 脱敏 / 签名） | - |
| `../mc-perf/SKILL.md` | 性能规范（SLA / 超时） | - |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 业务需求来源 | mc-doc-prd §3.5 接口需求 |
| 总体架构 / 资源划分 | mc-doc-arch §5 关键模块 |
| 数据库字段 / 字典 | mc-doc-dbd + mc-database-spec |
| API 工程规则（URL / 响应信封 / 错误码） | mc-api-spec v1.7 |
| Java 代码实现 | mc-java-spec |
| 前端调用 / 类型定义 | mc-webui-spec 场景三 |
| 鉴权 / 签名 / 脱敏 | mc-java-security |
| 性能 SLA / 超时 | mc-perf |

**接口设计文档与其他文档的边界**：

| 接口设计文档章节 | 关联工程规范 |
|---|---|
| §3 资源建模 | mc-api-spec v1.7 §3（URL / 方法 / 命名） |
| §5 单接口详述 | mc-api-spec §4（信封）+ §6（分页） |
| §6 错误码 | mc-api-spec §7 |
| §7 横切（鉴权 / 限流） | mc-java-security + mc-api-spec §5 |
| §10 契约（OpenAPI） | Apifox / YApi / Swagger |

> **关键**：接口设计文档描述「**接口长什么样、契约如何、为什么这么设计**」，mc-api-spec 描述「**API 工程规则（URL 命名 / 响应信封 / 错误码）**」，mc-java-spec 描述「**Controller / DTO / Jackson 代码怎么写**」，mc-doc-prd 描述「**业务需要什么接口**」。四者互补不重叠。
