# API 接口定义规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-17
> 风格基准：OpenAPI 3.x（OAS 3.0/3.1）
> 命名参考：阿里巴巴《Java 开发手册》
> 配套规范：《API 响应结构与错误码规范 v1.6》（响应信封、错误码、分页、Header 等）

---

## 1. 概述

本规范定义系统所有 RESTful HTTP API 接口的**设计、命名、描述、版本、安全**等工程标准。目标：

- **统一风格**：所有接口遵循 OpenAPI 3.x 语义，前端联调成本最小化。
- **命名严谨**：URL、字段、Java 类与方法严格遵循阿里 Java 约规，消除团队内的风格漂移。
- **可文档化**：所有接口必须能自动生成 OpenAPI 描述（推荐 springdoc-openapi），人工手写文档仅作补充。
- **可演进**：内置版本与兼容性约束，避免破坏性变更影响现网。

## 2. 适用范围与配套关系

| 适用 | 不适用 |
|---|---|
| 所有 RESTful HTTP API | GraphQL / WebSocket / MQ（另行规范） |
| 网关层之后的所有业务服务 | 网关本身的健康检查 / 内部探活接口 |
| 对前端 / 三方开放的接口 | 内部 RPC / Dubbo / gRPC（用 IDL 规范） |

**配套文档**：

- 响应信封、错误码、分页、HTTP Header → **v1.6 响应规范**
- 本规范**不重复定义**上述内容，仅引用

## 3. URL 规范

### 3.1 命名风格（核心）

参考阿里 Java 约规「URL 全部小写、多个单词用连字符分隔」与 RESTful 行业惯例：

| 规则 | 正例 | 反例 |
|---|---|---|
| 全部**小写** | `/users` | ❌ `/Users`、`/USERS` |
| 多词用**连字符** `-` 分隔 | `/user-profiles` | ❌ `/userProfiles`、`/user_profiles` |
| 资源**复数名词** | `/orders` | ❌ `/order`、`/getOrders` |
| **禁止** URL 后缀 | `/v1/users` | ❌ `/v1/users.json`、`/v1/users.do` |
| **禁止** 动词出现在基础资源路径 | `/v1/orders/{id}` | ❌ `/v1/getOrderById/{id}` |
| **禁止** 下划线 | `/v1/user-profiles` | ❌ `/v1/user_profiles` |
| **禁止** 文件扩展名 | `/v1/reports/{id}` | ❌ `/v1/reports/{id}.pdf` |

> ⚠️ **阿里约规 vs 行业惯例的取舍**：阿里约规禁止 URL 下划线，与 v1.6 的 body snake_case 字段命名**不冲突**——URL 路径段用 kebab-case，body 字段用 snake_case，这是大厂主流组合（GitHub REST 即如此）。

### 3.2 路径模板

所有业务 API 路径必须遵循以下模板：

```
/{version}/{resource}[/{id}[/{sub-resource}[/{sub-id}...]]]
```

| 段 | 说明 | 示例 |
|---|---|---|
| `version` | API 版本（详见 §10） | `v1`、`v2` |
| `resource` | 顶层资源名（复数） | `orders`、`user-profiles` |
| `id` | 资源主键（按 v1.6 §5.2 序列化为 String） | `123456` |
| `sub-resource` | 子资源（复数） | `items`、`shipments` |
| `sub-id` | 子资源主键 | `789` |

**示例**：

```
GET    /v1/orders                           # 订单列表
POST   /v1/orders                           # 创建订单
GET    /v1/orders/{order_id}                 # 订单详情
PATCH  /v1/orders/{order_id}                 # 修改订单
DELETE /v1/orders/{order_id}                 # 删除订单
GET    /v1/orders/{order_id}/items           # 订单子项列表
POST   /v1/orders/{order_id}/cancel          # 业务动作（详见 §4.2）
GET    /v1/users/{user_id}/orders            # 用户订单（嵌套子资源）
```

### 3.3 路径深度约束

| 层级 | 上限 | 说明 |
|---|---|---|
| 资源层级（不含 version） | **≤ 3 层** | 如 `/v1/users/{id}/orders/{order_id}/items` 已达上限 |
| 单层段长度 | **≤ 32 字符** | 避免过长难读 |
| 完整 URL 总长 | **≤ 2048 字符** | 浏览器/Nginx 兼容 |

> 💡 **超过 3 层时**：考虑将子资源提升为顶层资源（如 `/v1/order-items?order_id=xxx`），通过查询参数过滤。

### 3.4 路径参数命名

路径参数命名遵循 snake_case（与 body 字段对齐，**不是** kebab-case）：

| 规则 | 正例 | 反例 |
|---|---|---|
| 单词路径参数 | `{id}`、`{code}` | - |
| 多词路径参数 snake_case | `{order_id}`、`{user_id}` | ❌ `{orderId}`、`{order-id}` |

**原因**：路径参数本质是变量标识符，与 body 字段保持 snake_case 一致；URL 段本身的「连字符分隔」用于**资源名词**，不用于变量。

### 3.5 查询参数命名

查询参数同样 snake_case：

```
GET /v1/orders?created_after=2026-01-01&page_size=20&sort=created_at:desc
```

| 规则 | 示例 |
|---|---|
| snake_case | `created_after`、`is_active` |
| 布尔参数用 `is_`/`has_` 前缀 | `is_deleted`、`has_paid` |
| 时间范围用 `_before`/`_after` 后缀（Unix ms 或 ISO-8601） | `created_before`、`paid_after` |
| 枚举值用 String，禁止数字 | `status=paid`（非 `status=1`） |
| 排序统一格式 `sort=field:asc` 或 `sort=field:desc` | `sort=created_at:desc` |

## 4. HTTP 方法语义

### 4.1 标准方法（参考 RFC 7231 + RESTful）

| 方法 | 语义 | 幂等 | 安全 | Body | 典型场景 |
|---|---|---|---|---|---|
| `GET` | 查询资源 | ✅ | ✅ | 无 | 列表 / 详情 / 导出元信息 |
| `POST` | 创建资源 / 触发业务动作 | ❌ | ❌ | 有 | 创建、复杂查询、业务动作 |
| `PUT` | 全量替换资源（整体更新） | ✅ | ❌ | 有 | 全字段更新（罕用） |
| `PATCH` | 部分更新资源 | ❌ | ❌ | 有 | 部分字段更新（推荐） |
| `DELETE` | 删除资源 | ✅ | ❌ | 通常无 | 删除 |

> ⚠️ **PATCH vs PUT**：阿里约规不强制，本规范推荐**默认用 PATCH**（部分更新），PUT 仅用于真正的整体替换场景。

### 4.2 业务动作路径（自定义动词）

RESTful 纯粹派反对动词，但工程实践中业务动作（cancel、approve、refund、ship 等）无法纯用 CRUD 表达。**统一约定**：

```
POST /v1/{resource}/{id}/{action}
```

| 正例 | 反例 |
|---|---|
| `POST /v1/orders/{order_id}/cancel` | ❌ `POST /v1/cancelOrder` |
| `POST /v1/orders/{order_id}/ship` | ❌ `PUT /v1/orders/{order_id}?action=ship` |
| `POST /v1/refunds` （创建退款） | ❌ `POST /v1/orders/{id}/refund/create` |
| `POST /v1/approvals/{id}/reject` | ❌ `DELETE /v1/approvals/{id}` |

**action 命名规则**：

- **小写单个动词**（不要动词短语连写）：`cancel`、`approve`、`reject`、`ship`、`refund`
- 动词短语用连字符：`mark-paid`、`force-close`
- 状态转换类首选动词原形：`reopen`、`disable`、`enable`

### 4.3 复杂查询用 POST

当查询条件复杂（动态字段组合、深层嵌套 JSON），超过 URL 长度或难以表达时：

```
POST /v1/orders/search     # 复杂查询（不创建资源）
```

**约定**：

- 路径必须以 `/search` 结尾，与「创建资源」语义区分
- 响应复用 §6.2 分页信封
- 请求体允许包含分页参数（`page`、`page_size`、`limit`、`cursor` 等）

### 4.4 批量操作

| 场景 | 方法与路径 | 示例 |
|---|---|---|
| 批量查询（按 ID 列表） | `GET /v1/orders?ids=1,2,3` 或 `POST /v1/orders/batch-get` | - |
| 批量创建（N ≤ 100） | `POST /v1/orders/batch` | 同步返回 10700 部分成功 |
| 批量创建（N > 100） | `POST /v1/orders/batch` | 返回 job_id，详见 v1.6 §7.11.1 |
| 批量更新 | `PATCH /v1/orders/batch` | 同步返回 10700 |
| 批量删除 | `DELETE /v1/orders?ids=1,2,3` 或 `POST /v1/orders/batch-delete` | 推荐 POST 版本，避免 DELETE 带 body 不被部分网关支持 |

## 5. 请求规范

### 5.1 Content-Type

| 场景 | Content-Type |
|---|---|
| 普通 JSON 请求 | `application/json; charset=utf-8` |
| 文件上传（单文件） | `multipart/form-data` |
| 文件上传（base64 内嵌） | `application/json`（字段值为 base64 字符串） |

### 5.2 请求体字段规范

参考 v1.6 §5：

- 全局 **snake_case**（与响应一致）
- ID/金额字段必须 String
- 时间字段统一 Long ms 或 ISO-8601
- 不传可选字段时**省略**（不要传 `null`），服务端识别缺失 vs null

### 5.3 公共请求头（强制）

所有业务接口必须支持以下请求头：

| Header | 必填 | 说明 |
|---|---|---|
| `Authorization` | 是（除白名单） | 鉴权 Token，格式 `Bearer <jwt>` 或自定义 |
| `Content-Type` | 是（POST/PUT/PATCH） | 见 §5.1 |
| `X-Trace-Id` | 否 | 客户端可主动透传；未传服务端生成 |
| `Idempotency-Key` | 否（写操作推荐） | 幂等键，详见 v1.6 §5.4 |
| `Accept-Language` | 否 | 多语言场景，如 `zh-CN`、`en-US` |

### 5.4 字段命名细则（与阿里 Java 约规对齐）

| 字段类型 | 规则 | 示例 |
|---|---|---|
| Boolean | `is_` 或 `has_` 前缀，避免否定 | `is_active`、`has_paid` ❌ `not_deleted` |
| 数量 / 计数 | `_count` 后缀 | `order_count`、`total_count` |
| 时间戳（绝对时间） | `_at` 后缀 | `created_at`、`paid_at`、`expired_at` |
| 时间戳（日期） | `_date` 后缀 | `birth_date`、`expiry_date` |
| 时间范围 | `_before` / `_after` 后缀 | `created_before` |
| 持续时长 | `_seconds` / `_ms` 后缀，明确单位 | `duration_seconds`、`timeout_ms` |
| 金额 | `_amount` 后缀（String，元，2 位小数） | `pay_amount`、`refund_amount` |
| 货币 | `_currency` 后缀（ISO 4217） | `pay_currency` |
| 列表 / 数组 | **复数名词**，不要 `_list` 后缀 | `items` ❌ `item_list` |
| 关联 ID | `_id` 后缀 | `user_id`、`order_id` |
| 关联编码 | `_code` 后缀 | `sku_code`、`user_code` |

## 6. 响应规范

完全遵循 **v1.6 响应规范**，本规范不重复定义。要点引用：

| 维度 | 规范位置 |
|---|---|
| 响应信封（code/message/data/error/trace_id/timestamp） | v1.6 §4 |
| HTTP Status 与 code 的关系（业务异常统一 HTTP 200） | v1.6 §3 |
| 错误码体系（10xxx 系统类、10700 部分成功等） | v1.6 §7 |
| 分页（Offset / Cursor / Keyset 三种模式） | v1.6 §6 |
| HTTP Header（X-Trace-Id / X-RateLimit-* / Idempotency-Key） | v1.6 §5.4 |
| 字段命名（snake_case、金额 String、ID String） | v1.6 §5 |

## 7. 命名规范（核心章节，对齐阿里 Java 约规）

### 7.1 URL 命名（汇总）

| 元素 | 规则 | 示例 |
|---|---|---|
| 资源路径段 | 全小写 + kebab-case + 复数名词 | `/user-profiles`、`/orders` |
| 路径参数 | snake_case | `{order_id}`、`{user_id}` |
| 查询参数 | snake_case | `created_after`、`is_active` |
| 版本 | `v` + 数字，全小写 | `v1`、`v2` |
| 业务动作 | 小写单词 / kebab-case | `/cancel`、`/mark-paid` |

### 7.2 Java 包名

遵循阿里约规：

| 规则 | 正例 | 反例 |
|---|---|---|
| 全小写、单数、点分隔 | `com.company.order.service` | ❌ `com.company.Orders.Service` |
| 一级包名：公司域名反写 | `com.alibaba`、`com.tencent` | - |
| 二级包名：项目 / 模块 | `com.company.user`、`com.company.payment` | - |
| 三级包名：分层 | `.controller`、`.service`、`.mapper`、`.entity`、`.dto`、`.vo`、`.config`、`.common` | - |
| 禁止大写、数字开头、下划线 | - | ❌ `com.company.User_Service`、`com.company.1pay` |

**推荐包结构**：

```
com.company.<module>
├── controller       # HTTP 入口
├── service          # 业务逻辑接口
│   └── impl         # 业务逻辑实现
├── mapper           # MyBatis Mapper 接口
├── entity           # 数据库实体（PO）
├── dto              # 数据传输对象（请求/响应）
├── vo               # 视图对象（前端展示）
├── bo               # 业务对象（Service 间传递）
├── enums            # 枚举
├── config           # 配置类
├── common           # 通用工具、常量、异常
└── exception        # 自定义异常
```

### 7.3 Java 类命名（阿里约规强制）

| 类型 | 命名规则 | 正例 | 反例 |
|---|---|---|---|
| 普通类 | UpperCamelCase | `OrderService` | ❌ `orderService`、`Order_Service` |
| Controller | `<Resource>Controller` | `OrderController` | ❌ `OrderApi`、`OrderHandler` |
| Service 接口 | `<Resource>Service` | `OrderService` | ❌ `IOrderService`（禁止 `I` 前缀） |
| Service 实现 | `<Resource>ServiceImpl` | `OrderServiceImpl` | ❌ `OrderService` 与接口重名 |
| Mapper | `<Resource>Mapper` | `OrderMapper` | ❌ `OrderDao`（除非用 JPA） |
| Entity（PO） | `<Resource>DO` 或 `<Resource>Entity`（推荐 `DO`） | `OrderDO`、`UserDO` | ❌ `Order`（避免与领域模型混淆） |
| DTO（请求/响应） | `<Resource><Action>Request/Response` | `OrderCreateRequest`、`OrderQueryResponse` | ❌ `OrderDTO`（无明确含义） |
| VO（视图） | `<Resource>VO` | `OrderDetailVO`、`OrderListItemVO` | - |
| BO（业务对象） | `<Resource>BO` | `OrderBO` | - |
| 配置类 | `<Module>Config` | `RedisConfig`、`WebMvcConfig` | - |
| 异常类 | `<Meaning>Exception` | `BizException`、`OrderNotFoundException` | - |
| 枚举类 | `<Resource>Type` / `<Resource>Status` 等 | `OrderStatus`、`PayType` | ❌ `OrderStatusEnum`（阿里约规禁止 Enum 后缀） |
| 常量类 | `<Module>Constants` | `OrderConstants` | - |
| 工具类 | `<Module>Utils` 或 `<Module>Helper` | `DateUtils`、`MoneyUtils` | - |
| 抽象类 | `Abstract` 或 `Base` 前缀 | `AbstractOrderService`、`BaseController` | - |
| 测试类 | `<ClassNameTest>` | `OrderServiceTest` | - |

### 7.4 Java 方法命名

遵循阿里约规 lowerCamelCase + 动词前缀：

| 类型 | 规则 | 正例 |
|---|---|---|
| 普通方法 | lowerCamelCase，动词开头 | `getOrderById`、`createOrder` |
| 查询 | `get`/`find`/`query`/`list`/`search` 前缀 | `getById`、`findByName`、`listByStatus`、`searchByConditions` |
| 创建 | `create`/`save`/`add` | `createOrder`、`saveUser` |
| 更新 | `update`/`modify`/`change` | `updateStatus`、`modifyAddress` |
| 删除 | `delete`/`remove` | `deleteById`、`removeFromCart` |
| 判断 | `is`/`has`/`can`/`should` 前缀，返回 boolean | `isPaid`、`hasStock`、`canCancel` |
| 转换 | `to<X>` 前缀 | `toDTO`、`toVO`、`toString` |
| Controller 方法 | 与 HTTP 动词语义对齐 | `GET → get/list/search`、`POST → create/submit`、`PATCH → update/modify`、`DELETE → delete/remove` |
| 私有方法 | lowerCamelCase，前缀 `_` 不强制 | `parseQuery`、`buildFilter` |

**反例**：

- ❌ `GetOrder()`（首字母大写）
- ❌ `getorderbyid`（无大小写）
- ❌ `get_order_by_id`（下划线）
- ❌ `doSomething()`（语义不清）

### 7.5 变量命名

| 类型 | 规则 | 正例 | 反例 |
|---|---|---|---|
| 局部变量 | lowerCamelCase，名词为主 | `orderId`、`userList` | ❌ `a`、`temp1` |
| 成员变量 | lowerCamelCase（禁止 `_` 前缀 / `m_` 前缀） | `private Long userId;` | ❌ `private Long _userId;`、`mUserId` |
| 常量 | UPPER_SNAKE_CASE | `MAX_PAGE_SIZE`、`DEFAULT_TIMEOUT_MS` | ❌ `maxPageSize` |
| 集合 | 复数名词 或 `<Name>List/Set/Map` | `orders`、`orderList`、`userMap` | ❌ `orderArrayList` |
| Boolean | `is`/`has`/`can`/`should` 前缀 | `boolean isPaid;`、`boolean hasPermission;` | ❌ `Boolean paid;` |

> ⚠️ 阿里约规强制：**Boolean 字段不要以 `is` 开头**会有序列化坑（Lombok / Jackson），但 v1.6 信封与 body 字段（对外）**必须**用 `is_` 前缀。**约定**：Java 实体成员变量不加 `is`（避免序列化问题），通过 Jackson `@JsonProperty("is_paid")` 注解对外暴露 snake_case 名字。

### 7.6 常量命名

- 全部 UPPER_SNAKE_CASE，单词间下划线分隔
- 必须用 `static final` 修饰
- 长名要语义完整，禁止缩写到无法理解

```java
// 正例
public static final int MAX_PAGE_SIZE = 100;
public static final String DEFAULT_CURRENCY = "CNY";
public static final long CACHE_TTL_SECONDS = 3600L;

// 反例
public static final int MAX = 100;              // ❌ 含义不明
public static final String CURRENCY = "CNY";    // ❌ 缺少限定词
public static final int size = 100;             // ❌ 不是 UPPER_SNAKE
```

### 7.7 枚举命名

- 枚举类名 UpperCamelCase，**禁止 `Enum` 后缀**（阿里约规）
- 枚举值 UPPER_SNAKE_CASE
- 必须包含 `code`（数字）与 `description`（中文）字段
- 通过 `@JsonValue` 暴露 code，便于序列化

```java
public enum OrderStatus {
    PENDING(1, "待支付"),
    PAID(2, "已支付"),
    SHIPPED(3, "已发货"),
    COMPLETED(4, "已完成"),
    CANCELLED(5, "已取消");

    @JsonValue
    private final int code;
    private final String description;

    OrderStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
```

> 注意：对外 API 序列化枚举时**优先用字符串字面值**（如 `"PAID"`），见 v1.6 §5.1「禁止数字枚举」原则。

## 8. OpenAPI 3.x 描述规范

### 8.1 必填描述项

每个接口必须填写以下 OpenAPI 字段（不接受缺省）：

| 字段 | 必填 | 内容要求 |
|---|---|---|
| `summary` | ✅ | 一句话概括，≤ 30 字符；用于接口目录列表 |
| `description` | ✅ | 详细说明；含业务背景、特殊规则、依赖关系 |
| `tags` | ✅ | 资源分组标签，对应 Controller 类名简写（如 `Order`、`User`） |
| `operationId` | ✅ | 全局唯一的操作 ID，格式 `<verb><Resource>ById` 或 `<verb><Resource>`，如 `getOrderById`、`listOrders`、`createOrder`、`cancelOrder` |
| `parameters[].description` | ✅ | 每个参数必须有中文说明 |
| `parameters[].example` | ✅ | 必须给出示例值 |
| `requestBody.description` | ✅ | - |
| `responses.<code>.description` | ✅ | 至少描述 200、400（基础设施）、500（基础设施）三种 |
| `responses.<code>.content` | ✅ | 200 响应必须包含 schema 引用 v1.6 信封 |

### 8.2 参数描述模板

```yaml
parameters:
  - name: order_id
    in: path
    required: true
    description: |
      订单 ID。
      - 类型：String（雪花 ID）
      - 示例：892310293123123
    schema:
      type: string
      minLength: 1
      maxLength: 32
    example: "892310293123123"
```

### 8.3 响应描述模板

每个接口必须描述所有可能的业务错误码（对应 v1.6 §7）：

```yaml
responses:
  "200":
    description: 业务成功或业务失败（HTTP 200 + 业务 code）
    content:
      application/json:
        schema:
          $ref: "#/components/schemas/ApiResponse"   # 引用 v1.6 信封
        examples:
          success:
            summary: 成功示例
            value:
              code: 0
              message: "success"
              data: { "id": "892310293123123" }
              error: null
              trace_id: "c0a8010116983728001"
              timestamp: 1718660400000
          not_found:
            summary: 订单不存在
            value:
              code: 10400
              message: "请求资源不存在"
              data: null
              error: null
              trace_id: "c0a8010116983728002"
              timestamp: 1718660400000
```

### 8.4 Java 注解推荐（springdoc-openapi v2）

```java
@Tag(name = "Order", description = "订单管理")
@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    @Operation(
        summary = "查询订单详情",
        description = "根据订单 ID 查询订单完整信息，含子项与状态历史。",
        operationId = "getOrderById",
        responses = {
            @ApiResponse(responseCode = "200", description = "订单详情或业务错误",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @ApiResponse(responseCode = "404", description = "路由不存在（基础设施）")
        }
    )
    @GetMapping("/{order_id}")
    public ResponseEntity<ApiResponse<OrderDetailVO>> getOrder(
        @Parameter(description = "订单 ID", required = true, example = "892310293123123")
        @PathVariable("order_id") String orderId
    ) {
        // ...
    }
}
```

### 8.5 Schema 复用

所有公共 Schema（信封、分页信封、错误对象）必须抽取到 `components.schemas`，通过 `$ref` 引用，禁止重复定义：

```yaml
components:
  schemas:
    ApiResponse:
      type: object
      required: [code, message, data, error, trace_id, timestamp]
      properties:
        code: { type: integer }
        message: { type: string }
        data: { description: "业务数据" }
        error: { type: [array, "null"] }
        trace_id: { type: string }
        timestamp: { type: integer }
    PageResponse:
      type: object
      required: [list, total, page, page_size, has_more, summary]
      properties:
        list: { type: array }
        total: { type: integer, minimum: 0 }
        page: { type: integer, minimum: 1 }
        page_size: { type: integer, minimum: 1 }
        has_more: { type: boolean }
        summary: { type: [object, "null"] }
```

## 9. 接口分类与设计模式

### 9.1 标准 CRUD（资源型）

```
GET    /v1/orders               # 列表（分页）
POST   /v1/orders               # 创建
GET    /v1/orders/{order_id}    # 详情
PATCH  /v1/orders/{order_id}    # 部分更新
DELETE /v1/orders/{order_id}    # 删除
```

### 9.2 业务动作型

```
POST /v1/orders/{order_id}/cancel          # 取消
POST /v1/orders/{order_id}/mark-paid       # 标记已付
POST /v1/orders/{order_id}/refund          # 退款
POST /v1/orders/{order_id}/shipments       # 发货（创建子资源 shipment）
```

### 9.3 子资源型

```
GET    /v1/orders/{order_id}/items         # 订单子项列表
POST   /v1/orders/{order_id}/items         # 追加子项
PATCH  /v1/orders/{order_id}/items/{item_id}  # 修改子项
DELETE /v1/orders/{order_id}/items/{item_id}  # 删除子项
```

### 9.4 批量型

```
POST   /v1/orders/batch                    # 批量创建（N ≤ 100，同步 10700）
POST   /v1/orders/batch-search             # 批量复杂查询
PATCH  /v1/orders/batch                    # 批量更新
POST   /v1/orders/batch-delete             # 批量删除（推荐 POST，避免 DELETE body）
```

### 9.5 异步任务型（N > 100 或耗时操作）

```
POST   /v1/orders/batch-export             # 提交导出任务
GET    /v1/jobs/{job_id}                   # 轮询任务状态
GET    /v1/jobs/{job_id}/result            # 获取任务结果（如下载链接）
DELETE /v1/jobs/{job_id}                   # 取消任务
```

详见 v1.6 §7.11.1。

## 10. 版本与兼容

### 10.1 版本策略

| 变更类型 | 处理方式 |
|---|---|
| 新增字段 / 新增可选参数 / 新增端点 | **不升版本**（兼容） |
| 删除字段 / 改字段类型 / 改语义 / 改默认值 | **必须升版本**（`/v1/` → `/v2/`） |
| 收紧参数校验（原本宽松现在严格） | **必须升版本** |
| 字段重命名 | **必须升版本** |

### 10.2 弃用流程

| 阶段 | 动作 |
|---|---|
| T0 | 在新版本上线时，旧版本响应头加 `Deprecation: true` 与 `Sunset: <date>`（RFC 8594） |
| T0~T6M | 文档与监控持续提示弃用；接收客户反馈 |
| T6M | 旧版本下线，返回 410 Gone（基础设施层，无业务信封） |

### 10.3 版本选择

- **路径版本**（`/v1/`）：本规范强制使用
- **Header 版本**（`Api-Version: 1`）：**禁止**（与路径版本混用易歧义）
- **查询参数版本**（`?version=1`）：**禁止**

## 11. 安全与鉴权

### 11.1 鉴权方式

| 场景 | 方案 | 鉴权位置 |
|---|---|---|
| 内部用户（前端调用） | JWT Bearer Token | `Authorization: Bearer <jwt>` |
| 服务间调用 | mTLS 或 服务专网 | 不走业务鉴权 |
| 第三方对接 | API Key + HMAC 签名 | `X-Api-Key` + `X-Signature` + `X-Timestamp` |
| 用户 OAuth 2.0 | 标准 Authorization Code Flow | `Authorization: Bearer <access_token>` |

### 11.2 敏感参数处理

- **禁止**在 URL 中传递敏感参数（token、密码、身份证号）——会被日志、网关、浏览器历史记录
- 敏感数据必须在 **请求体**中传递
- 响应中敏感字段（密码哈希、token、完整手机号）必须脱敏或不返回

| 字段 | URL | Body |
|---|---|---|
| Token | ❌ | ✅ |
| 密码 | ❌ | ✅ |
| 身份证号 | ❌ | ✅ |
| 手机号（作为查询条件） | ❌（URL 太显眼） | ✅（推荐） |
| 银行卡号 | ❌ | ✅ |

### 11.3 速率限制

- 所有接口默认开启限流（按用户 / IP / API 维度）
- 限流触发后响应 10500（v1.6 §7.9），并通过 Header 暴露 `Retry-After` / `X-RateLimit-*`

## 12. 文件上传 / 下载

### 12.1 上传

```
POST /v1/files
Content-Type: multipart/form-data

# 表单字段
file: <binary>          # 必填
purpose: "order_attachment"   # 业务用途，可选
```

**响应**（v1.6 信封）：

```json
{
  "code": 0,
  "data": {
    "file_id": "file_abc123",
    "url": "https://cdn.example.com/files/file_abc123.pdf",
    "size": 102400,
    "mime_type": "application/pdf",
    "sha256": "abc..."
  }
}
```

### 12.2 下载（短链接直返）

```
GET /v1/files/{file_id}/download
Authorization: Bearer <jwt>

# 响应
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="order.pdf"
X-Trace-Id: ...

<binary>
```

> 注意：下载接口**不走业务信封**（直接返回二进制流），但仍应返回 `X-Trace-Id` 响应头用于追踪。

### 12.3 下载（签名 URL）

大文件 / CDN 直链场景，业务接口返回签名 URL，客户端直接下载：

```json
{
  "code": 0,
  "data": {
    "signed_url": "https://cdn.example.com/files/abc?signature=xxx&expires=1718664000",
    "expires_at": 1718664000
  }
}
```

## 13. 完整示例（OpenAPI 3.0 YAML）

```yaml
openapi: 3.0.3
info:
  title: 订单服务 API
  version: 1.0.0
  description: |
    订单管理接口，遵循 v1.6 响应规范与本接口定义规范。
servers:
  - url: https://api.example.com
tags:
  - name: Order
    description: 订单管理

paths:
  /v1/orders:
    get:
      tags: [Order]
      summary: 查询订单列表
      description: 支持按状态、时间范围、关键字过滤；默认按 created_at 倒序。
      operationId: listOrders
      parameters:
        - name: status
          in: query
          description: 订单状态枚举
          schema:
            type: string
            enum: [PENDING, PAID, SHIPPED, COMPLETED, CANCELLED]
          example: PAID
        - name: created_after
          in: query
          description: 创建时间下限（Unix ms）
          schema:
            type: integer
            minimum: 0
          example: 1718660400000
        - name: page
          in: query
          description: 页码，从 1 开始
          schema:
            type: integer
            minimum: 1
            default: 1
        - name: page_size
          in: query
          description: 每页条数，最大 100
          schema:
            type: integer
            minimum: 1
            maximum: 100
            default: 20
        - name: sort
          in: query
          description: 排序，格式 field:asc 或 field:desc
          schema:
            type: string
          example: created_at:desc
      responses:
        "200":
          description: 订单列表（分页响应）
          content:
            application/json:
              schema:
                allOf:
                  - $ref: "#/components/schemas/ApiResponse"
                  - type: object
                    properties:
                      data:
                        $ref: "#/components/schemas/OrderPage"
              examples:
                success:
                  value:
                    code: 0
                    message: "success"
                    data:
                      list: []
                      total: 128
                      page: 1
                      page_size: 20
                      has_more: true
                      summary: null
                    error: null
                    trace_id: "c0a8010116983728001"
                    timestamp: 1718660400000

    post:
      tags: [Order]
      summary: 创建订单
      operationId: createOrder
      parameters:
        - name: Idempotency-Key
          in: header
          description: 幂等键（强烈推荐），UUID
          schema:
            type: string
            format: uuid
          example: 7c8d2e1a-9f3b-4d6e-8a2c-1b5e9f0d3a7b
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/OrderCreateRequest"
      responses:
        "200":
          description: 创建成功或业务错误
          content:
            application/json:
              schema:
                allOf:
                  - $ref: "#/components/schemas/ApiResponse"
                  - type: object
                    properties:
                      data:
                        $ref: "#/components/schemas/OrderDetail"

  /v1/orders/{order_id}:
    get:
      tags: [Order]
      summary: 查询订单详情
      operationId: getOrderById
      parameters:
        - name: order_id
          in: path
          required: true
          description: 订单 ID
          schema:
            type: string
          example: "892310293123123"
      responses:
        "200":
          description: 订单详情
          content:
            application/json:
              schema:
                allOf:
                  - $ref: "#/components/schemas/ApiResponse"
                  - type: object
                    properties:
                      data:
                        $ref: "#/components/schemas/OrderDetail"

  /v1/orders/{order_id}/cancel:
    post:
      tags: [Order]
      summary: 取消订单
      description: |
        仅 PENDING / PAID 状态可取消。
        业务错误：
          - 10402 数据状态冲突（订单已取消 / 已发货）
          - 10300 无权限
      operationId: cancelOrder
      parameters:
        - name: order_id
          in: path
          required: true
          description: 订单 ID
          schema:
            type: string
          example: "892310293123123"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                reason:
                  type: string
                  description: 取消原因
                  example: "用户主动取消"
      responses:
        "200":
          description: 取消成功或业务错误
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ApiResponse"

components:
  schemas:
    ApiResponse:
      type: object
      required: [code, message, data, error, trace_id, timestamp]
      properties:
        code: { type: integer }
        message: { type: string }
        data: {}
        error: { type: [array, "null"] }
        trace_id: { type: string }
        timestamp: { type: integer }

    OrderPage:
      type: object
      required: [list, total, page, page_size, has_more, summary]
      properties:
        list:
          type: array
          items: { $ref: "#/components/schemas/OrderListItem" }
        total: { type: integer }
        page: { type: integer }
        page_size: { type: integer }
        has_more: { type: boolean }
        summary: { type: [object, "null"] }

    OrderListItem:
      type: object
      properties:
        id: { type: string, description: 订单 ID, example: "892310293123123" }
        status:
          type: string
          enum: [PENDING, PAID, SHIPPED, COMPLETED, CANCELLED]
          description: 订单状态
        total_amount:
          type: string
          description: 订单总额（元，2 位小数）
          example: "128.50"
        currency: { type: string, description: 币种 ISO 4217, example: "CNY" }
        created_at: { type: integer, description: 创建时间（Unix ms）, example: 1718660400000 }

    OrderDetail:
      allOf:
        - $ref: "#/components/schemas/OrderListItem"
        - type: object
          properties:
            items:
              type: array
              items: { $ref: "#/components/schemas/OrderItem" }
            paid_at: { type: integer, description: 支付时间, nullable: true }

    OrderItem:
      type: object
      properties:
        id: { type: string }
        sku_code: { type: string, description: SKU 编码 }
        name: { type: string }
        quantity: { type: integer, minimum: 1 }
        unit_price:
          type: string
          description: 单价（元，2 位小数）
          example: "12.50"
        subtotal:
          type: string
          description: 小计（元，2 位小数）
          example: "25.00"

    OrderCreateRequest:
      type: object
      required: [items]
      properties:
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            type: object
            required: [sku_code, quantity]
            properties:
              sku_code: { type: string, example: "SKU001" }
              quantity: { type: integer, minimum: 1, maximum: 999, example: 2 }
        remark:
          type: string
          maxLength: 200
          description: 订单备注
```

## 附录 A：版本变更记录

| 版本 | 日期 | 主要变更 |
|---|---|---|
| v1.0 | 2026-06-17 | 初版：URL 规范、HTTP 方法语义、命名规范（阿里 Java 约规对齐）、OpenAPI 3.x 描述规范、版本与兼容、安全、文件上下载、完整 OpenAPI YAML 示例 |

## 附录 B：命名速查表

| 元素 | 风格 | 示例 |
|---|---|---|
| URL 资源段 | lowercase + kebab-case + 复数 | `/user-profiles` |
| URL 路径参数 | snake_case | `{order_id}` |
| URL 查询参数 | snake_case | `created_after` |
| Body 字段 | snake_case | `created_at` |
| 业务动作 URL | lowercase 单词 / kebab-case | `/cancel`、`/mark-paid` |
| Java 类 | UpperCamelCase | `OrderService` |
| Java 方法 | lowerCamelCase | `getOrderById` |
| Java 变量 | lowerCamelCase | `orderId` |
| Java 常量 | UPPER_SNAKE_CASE | `MAX_PAGE_SIZE` |
| Java 包 | 全小写、单数、点分隔 | `com.company.order.service` |
| Java 枚举类 | UpperCamelCase（**禁用 `Enum` 后缀**） | `OrderStatus` |
| Java 枚举值 | UPPER_SNAKE_CASE | `PAID`、`CANCELLED` |
| Service 接口 | `<Resource>Service` | `OrderService` |
| Service 实现 | `<Resource>ServiceImpl` | `OrderServiceImpl` |
| Controller | `<Resource>Controller` | `OrderController` |
| DTO | `<Resource><Action>Request/Response` | `OrderCreateRequest` |
| VO | `<Resource>VO` | `OrderDetailVO` |
| DO | `<Resource>DO` | `OrderDO` |
| Boolean 字段（对外） | `is_` / `has_` 前缀 | `is_paid` |
| Boolean 成员变量（Java 内） | 无 `is` 前缀（防序列化坑） | `paid` |
| 数量字段 | `_count` 后缀 | `order_count` |
| 时间字段 | `_at` / `_date` / `_before` / `_after` | `created_at` |
| 金额字段 | `_amount` 后缀（String 元） | `pay_amount` |
| 货币字段 | `_currency` 后缀 | `pay_currency` |
| 列表字段 | 复数名词（**禁止 `_list`**） | `items` |

## 附录 C：与配套规范的引用关系

| 主题 | 本规范章节 | 配套规范 |
|---|---|---|
| 响应信封结构 | §6 | v1.6 §4 |
| HTTP Status 业务异常 | §6 | v1.6 §3 |
| 错误码定义 | §6 | v1.6 §7 |
| 分页信封 | §9 | v1.6 §6 |
| HTTP Header（X-Trace-Id 等） | §5.3 | v1.6 §5.4 |
| 幂等键 | §5.3 | v1.6 §5.4 |
| 异步 Job | §9.5 | v1.6 §7.11.1 |
| 金额序列化 | §5.2 | v1.6 §5.2 |
| 限流响应 | §11.3 | v1.6 §7.9 |
