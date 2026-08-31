---
name: mc-api-spec
description: 后端 API 设计与实现激活。覆盖 URL 设计、HTTP 方法、响应信封、错误码、分页、幂等键。触发词：API、接口、REST、Controller、Service、DTO、VO、错误码、业务码、code、响应、信封、分页、游标、cursor、OpenAPI、Swagger、URL规范、幂等、Idempotency、限流、trace_id、金额、枚举、批量、异步Job。
version: 1.7.0
enabled: true
metadata:
  type: domain-spec
  category: backend
  tags: [api, rest, http, response-envelope, error-code, pagination, idempotency, openapi]
  language: zh-CN
  spec-version: v1.6
  related-specs:
    - API 响应结构与错误码规范 v1.6.md
    - API 接口定义规范 v1.0.md
  related-skills: [mc-java-spec, mc-webui-spec]
  author: architecture-team
  last-reviewed: 2026-06-23
  examples:
    - "设计一个订单查询 API"                # 自动激活：API 设计
    - "这个接口应该返回什么 HTTP 状态码"   # 自动激活：HTTP 状态讨论
    - "限流后前端怎么知道何时重试"         # 自动激活：Retry-After
    - "游标分页和键集分页有什么区别"       # 自动激活：分页模式选型
    - "幂等键怎么生成"                     # 自动激活：Idempotency-Key
    - "批量操作 1000 条数据怎么处理"       # 自动激活：异步 Job
---

# API 接口与响应规范

由两份文档组成：**接口定义规范**（URL/方法/命名/OpenAPI）+ **响应规范**（信封/错误码/分页/Header）。

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 设计新 API | 场景一：URL + 方法 + 参数命名 |
| 写 Controller / DTO / VO | 场景二：Java 实现要点（详细代码在 mc-java-spec） |
| 返回成功 / 失败响应 | 场景三：信封 + 错误码 |
| 实现列表 / 分页 / 批量 | 场景四：三种分页 + 异步 Job |
| 检查 API 合规 | 场景五：P0 必查 5 项 |
| 退出本规范 | 「退出 mc-api-spec」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| **适用** | RESTful HTTP API 设计与契约定义、错误码、分页、Header 规范 |
| **不适用** | Java 代码实现细节（→ mc-java-spec）、数据库设计（→ mc-database-spec）、前端调用（→ mc-webui-spec） |
| 优先级 | plan mode > mc-cli > mc-api-spec > 默认 |
| 退出 | 「退出 mc-api-spec」 |

## 2. 全局铁律

1. **业务异常统一 HTTP 200** — `code` 决定结果，禁 4xx/5xx 携带业务信封
2. **响应信封 6 字段必返** — `code` / `message` / `data` / `error` / `trace_id` / `timestamp`
3. **全局 snake_case** — URL 路径参数 / 查询参数 / body 字段；URL 资源段用 kebab-case
4. **ID 与金额必须 String** — 主键/雪花 ID/金额（元，2 位小数）一律 String
5. **失败时 data 必须 null** — 唯一例外 10700 部分成功
6. **trace_id 双通道** — body 必返 + 响应头 `X-Trace-Id` 必返
7. **URL 强制 `/v{N}/` 前缀**
8. **写操作支持 `Idempotency-Key`** — 客户端 UUID，服务端保留 48h

## 3. 场景判定

```
当前任务是什么？
├── 设计新 API（URL / 方法 / 参数）→ 场景一：接口设计
├── 写 Controller / DTO / VO      → 场景二：Java 实现要点（详细走 mc-java-spec）
├── 返回响应 / 处理错误           → 场景三：响应与错误处理
├── 列表 / 分页 / 批量            → 场景四：分页与批量
└── 检查 API 合规                 → 场景五：规范检查
```

### 场景一：接口设计

**URL 命名**（lowercase + kebab-case + 复数名词）：

| 规则 | 正例 | 反例 |
|---|---|---|
| 资源段 lowercase + kebab + 复数 | `/v1/user-profiles` | ❌ `/Users` / `/user_profiles` / `/getUsers` |
| 路径参数 snake_case | `{order_id}` | ❌ `{orderId}` |
| 查询参数 snake_case | `created_after` | ❌ `createdAt` |
| 业务动作 | `POST /v1/orders/{id}/cancel` | ❌ `POST /v1/cancelOrder` |
| 复杂查询 | `POST /v1/orders/search` | - |
| 路径深度 | ≤ 3 层（不含 version） | - |

**HTTP 方法**：GET 查询 / POST 创建+动作+复杂查询 / PATCH 部分更新（推荐默认）/ PUT 全量替换 / DELETE 删除。

**参数命名后缀**：Boolean→`is_`/`has_`、时间→`_at`/`_date`/`_before`/`_after`、金额→`_amount`、货币→`_currency`、数量→`_count`、列表→复数名词（禁 `_list`）。

详见 `./API 接口定义规范 v1.0.md` §3、§4、§5。

### 场景二：Java 实现要点

**信封类名**：`ApiResponse<T>`（禁 `R<T>`）。**ID 类型**：`String`（禁 `Long` 出现在 Controller）。**DTO 命名**：`<Resource><Action>Request` / `<Resource><Action>VO`。

详细代码模板（Controller / 全局异常处理 / Jackson 配置 / TraceContext）走 **mc-java-spec**。

### 场景三：响应与错误处理

**成功响应**（HTTP 200）：

```json
{
  "code": 0, "message": "success",
  "data": { "id": "892310293123123", "amount": "128.50" },
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

**失败响应**（HTTP 200，data=null）：

```json
{
  "code": 10100, "message": "请求参数错误",
  "data": null,
  "error": [
    { "field": "email", "code": "FORMAT_INVALID", "message": "邮箱格式不正确", "value": "invalid-email" }
  ],
  "trace_id": "...", "timestamp": 1718660400000
}
```

**高频错误码速查**（完整见 v1.6 §7；v1.7：文件上传 106xx → 10503-10506，106xxx 释放为业务自定义）：

| 场景 | code | 子类型 |
|---|---|---|
| 必填缺失 | 10101 | `REQUIRED_MISSING` |
| 格式不正确 | 10102 | `FORMAT_INVALID` |
| 通用参数错误 | 10100 | - |
| 资源不存在 | 10400 | `NOT_FOUND` |
| 唯一性冲突 | 10401 | `UNIQUE_CONFLICT` |
| 状态冲突 | 10402 | `STATE_CONFLICT` / `OUT_OF_STOCK` |
| 未登录 / Token 失效 | 10200 / 10201 / 10202 | - |
| 权限不足 | 10300 | - |
| 限流（带 `Retry-After`） | 10500 | - |
| 重复提交 | 10501 | - |
| 文件过大 / 类型不支持 / 上传失败 | 10505 / 10504 / 10503 | - |
| 业务自定义（余额不足、风控、农时冲突等） | 106xx | 业务线登记认领（§7.10） |
| 部分成功 | 10700 | data 非 null |

**应用层 vs 路由层边界**：能被业务代码 catch → 业务信封（HTTP 200）；路由/网关层拦截（404/405/413/502/503/504）→ 原始 HTTP 无信封。

### 场景四：分页与批量

**三种分页选型**：

| 场景 | 模式 | 请求参数 | 响应字段 |
|---|---|---|---|
| 后台管理 / 跳页 / <10 万 | Offset | `page` + `page_size` | list/total/page/page_size/has_more/summary |
| Feed 流 / 无限滚动 / 海量 | Cursor | `cursor` + `limit` | list/next_cursor/has_more/summary（无 total） |
| 高性能 / >100 万 / 深翻页 | Keyset | `last_id` + `last_value` + `limit` | list/last_id/last_value/has_more/summary（无 total） |

> `limit` / `page_size` 禁止混用：Offset 用 `page_size`，Cursor/Keyset 用 `limit`。

**summary 可选字段**：聚合统计（基于全量结果集，非当前页）；触发方式由业务自定。

**批量操作**：N ≤ 100 同步返 10700；N > 100 必须异步 Job（返 `job_id` + `poll_url`，轮询 `GET /v1/jobs/{job_id}`）。

详见 v1.6 §6、§7.11。

### 场景五：规范检查

**P0 必查 5 项**：

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | 业务异常返回 HTTP 200 | 抓真实响应 |
| 2 | 信封 6 字段全返 | 抓真实响应 |
| 3 | 失败时 data=null | 抓真实响应 |
| 4 | URL 含 `/v{N}/` 前缀 | grep `@RequestMapping` |
| 5 | `X-Trace-Id` Header 必返 | 抓真实响应头 |

**P1/P2/P3**：错误码 5 位数字 / ID String / 金额 String / snake_case / 错误码不与 v1.6 §7 冲突 / 分页参数不混用 / 写操作支持 Idempotency-Key / 异步 Job（N>100）。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./API 接口定义规范 v1.0.md` | URL/方法/命名/OpenAPI | v1.0 |
| `./API 响应结构与错误码规范 v1.6.md` | 信封/错误码/分页/Header | v1.6 |
| `./API 响应结构与错误码规范 v1.3.md` / `v1.4.md` / `v1.5.md` | 历史版本（已废弃，仅对照） | - |

## 5. 与其他规范协作

| 涉及 | 同时参考 |
|---|---|
| Java 后端代码实现（Controller/DTO/Jackson 配置） | `../mc-java-spec/SKILL.md` |
| 数据库表设计 / SQL | `../mc-database-spec/SKILL.md` |
| 前端 axios 封装 / 类型定义 | `../mc-webui-spec/SKILL.md`（场景三） |
