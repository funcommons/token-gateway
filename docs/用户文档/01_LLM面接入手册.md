# token-gateway LLM 面接入手册

| 项 | 内容 |
|---|---|
| 文档 | LLM 面接入手册（对话/向量/同步生图/模型目录 + 后端接入 + 适配器开发） |
| 配套 | 任务面见 `02_任务面接入手册.md`（**规划中 M2.5，未实现**）；接口契约 `03_LLM面API契约.yaml`；设计方案见 `../开发文档/01_设计方案.md` |
| 版本 | V1.2（2026-08-31，按面拆分：LLM 面 / 任务面分册） |
| 代码基底 | `fun.commons.tokengateway`（LLM 面，端口 9401，已上线端点即本文所写） |

---

## 1. 网关定位与拓扑

```
调用方 (OpenAI SDK / Anthropic SDK / curl / 业务后端)
        │  Authorization: Bearer <凭证>  或  x-api-key: <凭证>
        ▼
token-gateway (9401)  ── 协议归一 / 限流 / 幂等 / 审核开关 / 计费 saga / 日志 / 审计
        │  按 yml 能力面配置路由到后端服务
        ▼
后端服务（每类能力面独立地址，可分离部署，也可同指一个单体）:
路由 · 凭证校验 · 计费 · 审核 · 日志(rpc/mq) · 审计 · 模型目录
（协议形状由 yml adapter 单选: mmagix / tokenhub / tokengo / openapi 通用）
```

网关的三个承诺：

1. **协议归一**：OpenAI 形状的请求（`/v1/chat/completions` 等）无论命中哪种上游，一律以 OpenAI 形状返回；Anthropic 形状请求（`/v1/messages`）一律以 Anthropic 形状返回。调用方不感知上游差异。
2. **服务可分离**：调用方不感知后端——按 `model` 由 yml 路由配置寻址（通配绑定，支持灰度/影子）；计费/日志/审计等服务可独立部署、独立地址。
3. **凭证即身份**：网关不发凭证；凭证语义由命中的后端定义（MMagiX token / TokenHub `sk-thmp-*` / TokenGo token）。

---

## 2. 调用方快速开始

### 2.1 OpenAI SDK（推荐，改 base_url 即用）

```python
from openai import OpenAI

client = OpenAI(
    api_key="<你的凭证>",
    base_url="http://<gateway-host>:9401/v1"
)
resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)
```

### 2.2 curl（同步）

```bash
curl -s http://localhost:9401/v1/chat/completions \
  -H "Authorization: Bearer <你的凭证>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 2.3 流式（SSE）

请求体加 `"stream": true`，响应为 `text/event-stream`，逐段透传上游 chunk（OpenAI 格式 `data: {...}` / 结束 `data: [DONE]`；Anthropic 协议端点为 `event:` + `data:` 形状）。客户端按标准 SSE 解析即可，网关不缓存完整响应。

### 2.4 Anthropic SDK

```python
import anthropic

client = anthropic.Anthropic(
    api_key="<你的凭证>",
    base_url="http://<gateway-host>:9401"   # SDK 自动拼 /v1/messages
)
msg = client.messages.create(
    model="claude-sonnet-4-5",
    max_tokens=1024,
    messages=[{"role": "user", "content": "你好"}],
)
```

---

## 3. 认证

| 方式 | Header | 说明 |
|---|---|---|
| Bearer（推荐） | `Authorization: Bearer <凭证>` | OpenAI 生态默认 |
| API-Key 头 | `x-api-key: <凭证>` | Anthropic 生态默认；两头同发以 Bearer 优先 |

- 凭证校验在管线第一步（`TOKEN_VALIDATE` 能力面），失败返回 **10202 令牌无效 / 10200 未认证**（HTTP 401 + 错误信封）。
- 凭证向哪个后端校验，由模型路由命中的后端决定——**换后端不换调用方式**。
- 凭证不会出现在任何网关日志与错误信息中。

---

## 4. 端点总览（LLM 面）

| # | 端点 | Method | 协议形状 | 流式 |
|---|---|---|---|---|
| 1 | `/v1/chat/completions` | POST | OpenAI | ✅ `stream:true` |
| 2 | `/v1/messages` | POST | Anthropic | ✅ `stream:true` |
| 3 | `/v1/messages/count_tokens` | POST | Anthropic | — |
| 4 | `/v1/embeddings` | POST | OpenAI | — |
| 5 | `/v1/images/generations` | POST | OpenAI（同步生图） | — |
| 6 | `/v1/models` | GET | OpenAI | — |

> 任务面端点（videos/images/audios/tts 异步任务）见 `02_任务面接入手册.md`（规划中 M2.5）。
> 完整字段契约见 `03_LLM面API契约.yaml`。

### 4.1 Chat Completions 要点

- 请求：`model`（必填，未传时网关回退默认模型）+ `messages`（必填）+ 标准 OpenAI 可选参数（`max_tokens` / `temperature` / `tools` / `stream` 等）。
- `tools` 调用链会做 Anthropic 工具链 sanitizer 清洗（跨协议工具调用兼容）。
- 成功响应 = **上游原样（协议归一后）透传，非信封**；`usage` 里的 `prompt_tokens / completion_tokens / cached_tokens` 是计费结算依据。
- 缺 `usage` 的上游：按估算 tokens 结算（估算值略高于实际，误差方向对调用方无成本损失）。
- 计费 saga：转发前预扣（余额不足 → 10617，HTTP 402 语义信封）→ 转发 → 按实际 usage 结算；全失败自动全额退款。

### 4.2 Embeddings / Images 要点

- `/v1/embeddings`：`model` + `input`（字符串或数组），返回 OpenAI embedding 形状。
- `/v1/images/generations`：`model` + `prompt` + 可选 `size`/`n`，按生成张数计费。
- 两者无流式；上游为 Anthropic 形状时同样被归一为 OpenAI 形状返回。

### 4.3 Models 要点

`GET /v1/models` 返回调用方可用模型目录（`object:"list"` + `data[]`），来自 `MODEL_CATALOG` 能力面。目录为空的常见原因：凭证对应租户未开通任何模型。

---

## 5. 响应形态（成败判定铁律）

| 形态 | HTTP | Body | 判定 |
|---|---|---|---|
| 成功（非流式） | 200 | 上游业务形状（OpenAI/Anthropic），**无信封** | 有 `choices`/`content`/`data` 即成功 |
| 成功（流式） | 200 | `text/event-stream` | 逐段解析，`[DONE]`/`message_stop` 结束 |
| 业务/系统错误 | 4xx/5xx | 6 字段信封 | `code != 0` 即失败 |

错误信封：

```json
{
  "code": 10202,
  "message": "令牌无效",
  "data": null,
  "error": [{"field": null, "code": null, "message": "令牌无效", "value": null}],
  "trace_id": "c0a80101-...",
  "timestamp": 1756600000000
}
```

> **注意**：不要用 `HTTP status` 之外的启发式判失败，也不要假设成功响应带 `code` 字段——成功是透传形状。

---

## 6. 横切规范

### 6.1 链路追踪

- 请求可带 `X-Trace-Id`（不传网关生成）；响应头**必回** `X-Trace-Id`。
- 排障时提供 `trace_id` 即可贯通网关日志 ↔ 后端日志 ↔ 访问日志。

### 6.2 限流

- 维度：按凭证（apiKey）+ 全局两层；固定窗口。
- 超限：**HTTP 429** + 信封（10500）+ 响应头：

| Header | 含义 |
|---|---|
| `Retry-After` | 建议等待秒数（客户端应指数退避） |
| `X-RateLimit-Limit` | 窗口配额 |
| `X-RateLimit-Remaining` | 剩余额度 |
| `X-RateLimit-Reset` | 重置等待秒数 |

### 6.3 幂等

- 写操作（全部 POST `/v1/**`）可带 `Idempotency-Key: <uuid v4>`。
- 同一凭证 + 同 key 的重复请求在窗口内被**拒绝式去重**（10501 重复提交），不会重复计费。
- 建议：所有非幂等安全的调用（图片生成等）必带。

### 6.4 超时预算

| 环节 | 预算 |
|---|---|
| 连接上游 | 3s |
| 首字节 | 30s |
| 总时长 | 300s（SSE 长连接同预算） |
| 网关→后端能力调用 | 注册配置（route 3s / billing 5s / moderation 2s 缺省） |

超时返回 10003（服务调用超时）信封；上游全失败返回 10004。

---

## 7. 错误码速查

| code | HTTP | 含义 | 调用方处置 |
|---|---|---|---|
| 10001 | 500 | 系统繁忙 | 退避重试 |
| 10003 | 504 | 服务调用超时 | 退避重试；长文本检查 max_tokens |
| 10004 | 502 | 第三方服务异常（上游全失败） | 退避重试；持续失败联系平台 |
| 10100~10106 | 400 | 参数错误/缺失/格式/范围/JSON；10106 亦用于内容安全拒绝 | 修请求，勿重试 |
| 10200 | 401 | 未认证/令牌过期 | 换有效凭证 |
| 10202 | 401 | 令牌无效 | 换有效凭证 |
| 10300 | 403 | 权限不足（模型未开通） | 联系平台开通 |
| 10400 | 404 | 模型不存在/无可用渠道 | 核对 model 名 |
| 10402 | 409 | 状态冲突 | 按业务处理 |
| 10500 | 429 | 请求过频 | 按 `Retry-After` 退避 |
| 10501 | 409 | 重复提交（幂等 key 命中） | 换新 Idempotency-Key |
| 10617 | 402 | 余额不足 | 充值后重试 |
| 10700 | 200 | 部分成功 | 按 `data` 内明细处理 |

---

## 8. 后端服务接入（yml 配置，无注册概念）

> **配置即接入**：在项目 yml 中按**能力面**配置后端服务（url + 鉴权 + 开关），每类服务一个地址，
> 日志/计费/审核等服务可分离部署。变更 = 改 yml + 重启。字段全量语义见《设计方案》§5。

### 8.1 协议适配器选型（全局单选）

| 你的后端是 | adapter | 说明 |
|---|---|---|
| MMagiX 主应用 | `mmagix` | 七能力全量，现网形态（M1） |
| TokenHub（thmp-app） | `tokenhub` | route 面 HMAC 四头契约；透传/契约双模式 |
| TokenGo | `tokengo` | OpenAI 兼容直通（M3 对齐中） |
| **任意语言实现的三方系统** | `openapi` | **内置通用适配器**——按 《后端接入开发手册》实现能力面契约即可，不限语言 |
| 协议特殊、契约表达不了 | `custom:<spiName>` | Java SPI 路径（§9） |

### 8.2 配置示例（服务分离部署）

```yaml
token-gateway:
  adapter: mmagix
  route:                    # 路由/分发
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
    routes:
      - models: ["gpt-*", "*"]
  token-validate:           # 凭证校验（可与 route 同址）
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
  billing:                  # 计费服务（独立部署示例）
    url: http://billing-svc:9410
    auth: jwt
    jwt-secret: ${GW_BILLING_JWT_SECRET}
  moderation:               # 内容审核（可整体关闭）
    enabled: true
    fail-open: true
    url: http://moderation-svc:9420
    auth: key
    key: ${GW_MODERATION_KEY}
  access-log:               # 日志服务（rpc | mq 两类通道）
    enabled: true
    transport: rpc          # rpc=同步调日志服务 / mq=Kafka|RocketMQ 异步
    url: http://log-svc:9430
    auth: none
    # transport: mq 时改配：
    # mq:
    #   type: kafka          # kafka | rocketmq
    #   bootstrap: kafka-1:9092
    #   topic: token-gateway-access-log
  audit:                    # 审计服务
    url: http://audit-svc:9440
    auth: jwt
    jwt-secret: ${GW_AUDIT_JWT_SECRET}
  model-catalog:
    url: http://localhost:9400
    auth: jwt
    jwt-secret: ${GW_JWT_SECRET}
```

七类全部指向同一地址 = **单体模式**（如 MMagiX 9400 一址七面共用）；不同 host = **分离模式**。部署拓扑变化不改代码，只改 yml。

### 8.3 后端鉴权三式

> 权威定义见《后端服务对接安全契约方案》（`../开发文档/04_后端服务对接安全契约方案.md`）。

| auth | 发送方式 | 推荐级别 | 适用 |
|---|---|---|---|
| `jwt` | HS256 签名 JWT（`Authorization: Bearer`，claims 含 iss/caller/tenant_id） | **推荐（默认）** | 需要身份语义——**现网 internal-token 即此形态**；换 secret 须网关同步重铸；跨网段叠加逐请求签名 |
| `key` | `X-API-Key: <静态key>` | 可用（内网限定） | 简单共享密钥（恒定时间比较） |
| `none` | 不发鉴权头 | 受限可用 | 仅 localhost/sidecar 同机隔离；白名单作纵深不作替代 |

凭证一律环境变量注入禁入仓；日志只出现掩码。`token` 静态令牌式已移除（既有系统按 jwt 重铸）。

### 8.4 关键开关与 billing 三值

| 配置 | 行为 |
|---|---|
| `moderation.enabled` | 是否内容审核扫描；`fail-open` 控制审核依赖故障时放行（对齐 fail-open 文档口径） |
| `access-log.enabled` | 是否日志落库（off = 仅内存计数）；`transport` 选 rpc 同步 / mq 异步（Kafka\|RocketMQ，at-least-once） |
| `billing` | `direct`=网关 saga 计费（走计费服务）/ `passthrough`=后端自计费（THMP sk- 闭环）/ `off`=不计费（内网/BYOK） |
| `health-report` | 渠道健康信号回传（record-success/failure）；off 时需在监控侧补偿 |

---

## 9. 三方适配器开发指南（Java SPI 路径）

> 语言无关的首选路径是 §8.1 的 `openapi` 通用适配器（按 《后端接入开发手册》实现能力面契约，Go/Python/Node 均可）。本章仅当协议特殊、通用契约表达不了时才走。

### 9.1 依赖与接口

新建 Maven 模块，**只依赖 `gateway-spi`**（不依赖网关核心）：

```java
public class AcmeAdapter implements BackendAdapter {
    public String backendId() { return "acme"; }
    public Set<Capability> capabilities() {
        return Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE, Capability.BILLING);
    }
}
// 按能力实现六接口之一或多者：TokenValidator / RouteResolver / ModerationScanner
//                               / BillingClient / AccessLogSink / ModelCatalog
```

注册方式二选一：`META-INF/services`（ServiceLoader）或 Spring `@Bean`（装配模块内）。

### 9.2 铁律（违反 = 接入验收不过）

1. 适配器**无状态**；缓存/连接池自管且可被注册表重载。
2. 错误必须以信封错误码上抛（10202/10004/10617 语义），**不得吞错改语义**。
3. 超时用注册配置注入的预算，**不得自带硬编码超时覆盖**。
4. `TokenContext` 等承载凭证的对象 toString 必须脱敏。
5. 能力声明如实——声明了 `BILLING` 就必须完整实现 preConsume/settle/refund 三件套。

### 9.3 自测清单

- [ ] validate：无效凭证 → 10202；过期 → 10200
- [ ] resolve：未知模型 → 10400；返回 DistributeVO 字段完整（baseUrl/凭证/modelMapping）
- [ ] billing（如声明）：预扣不足 → 10617；settle 与 refund 幂等
- [ ] 全链路跑通冒烟脚本（compose 内联后端桩）

---

## 10. 接入验收清单（调用方）

- [ ] base_url 指向网关（9401），SDK 认证头选用 Bearer 或 x-api-key
- [ ] 成功判定按 §5（透传形状无信封）；错误处理覆盖 10202/10500/10004 三类高频
- [ ] 429 时读取 `Retry-After` 指数退避（勿死循环重试）
- [ ] 非幂等调用带 `Idempotency-Key`
- [ ] 流式消费按标准 SSE 解析，处理 `[DONE]` 与中途断连
- [ ] 记录响应头 `X-Trace-Id` 用于排障

## 11. 常见问题

| 现象 | 根因 | 处置 |
|---|---|---|
| 401 但凭证肉眼有效 | 命中后端侧凭证被禁用/租户停用 | 查后端凭证状态；禁用即时生效 |
| 10400 模型不存在 | model 名不在任何已注册后端路由内 | `GET /v1/models` 核对；联系平台加路由 |
| 响应无 `code` 字段 | 正常——成功是透传形状 | 按 §5 判定 |
| 流式中途断开无结算依据 | 上游断连 | 网关按已产出估算结算，调用方重试即可 |
| 429 频繁 | 凭证维度窗口打满 | 申请提额或客户端限速 |
