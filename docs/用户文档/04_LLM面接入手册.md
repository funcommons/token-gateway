# LLM 面接入手册

| 项 | 内容 |
|---|---|
| 文档 | LLM 同步面调用方手册（对话 / 向量 / 同步生图 / 模型目录） |
| 前置阅读 | [产品简介](./01_产品简介.md) · [快速开始](./02_快速开始.md) · [通用约定](./03_通用约定.md)（认证/错误码/限流/幂等） |
| 配套 | 任务面见[任务面接入手册](./05_任务面接入手册.md)；字段级契约见 `07_LLM面API契约.yaml` |
| 版本 | V2.0（2026-09-02，阿里云文档风格重构；后端接入内容移至开发文档） |

---

## 1. 端点总览

| # | 端点 | Method | 协议形状 | 流式 |
|---|---|---|---|---|
| 1 | `/v1/chat/completions` | POST | OpenAI | ✅ `stream:true` |
| 2 | `/v1/messages` | POST | Anthropic | ✅ `stream:true` |
| 3 | `/v1/messages/count_tokens` | POST | Anthropic | — |
| 4 | `/v1/embeddings` | POST | OpenAI | — |
| 5 | `/v1/images/generations` | POST | OpenAI（同步生图） | — |
| 6 | `/v1/models` | GET | OpenAI | — |

**协议归一承诺**：OpenAI 形状进 → 无论命中哪种上游 → OpenAI 形状出；Anthropic 同理。调用方不感知上游差异。

---

## 2. Chat Completions（`/v1/chat/completions`）

对话生成主端点，OpenAI 协议。

### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | 是 | 模型名（路由定价依据；未传时网关回退默认模型） |
| `messages` | array | 是 | 标准 OpenAI 消息数组 |
| `max_tokens` / `temperature` / `tools` / `stream` … | — | 否 | 标准 OpenAI 可选参数原样支持 |

### 请求示例

::: code-group

```bash [curl]
curl -s http://<gateway-host>:9401/v1/chat/completions \
  -H "Authorization: Bearer <凭证>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"你好"}]}'
```

```python [OpenAI SDK]
resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)
```

```python [流式]
stream = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "你好"}],
    stream=True,
)
for chunk in stream:          # 标准 OpenAI SSE 增量
    print(chunk.choices[0].delta.content or "", end="")
```

:::

### 响应说明

- **成功 = 上游原样（协议归一后）透传，非信封**；`usage.prompt_tokens / completion_tokens / cached_tokens` 是计费结算依据。
- 缺 `usage` 的上游：按估算 tokens 结算（估算值略高于实际，误差方向对调用方无成本损失）。
- 计费 saga：转发前预扣（余额不足 → **10617 / HTTP 402**）→ 转发 → 按实际 usage 结算；全失败自动全额退款。
- `tools` 调用链自动做 Anthropic 工具链 sanitizer 清洗（跨协议工具调用兼容）。

### 流式（SSE）

请求体加 `"stream": true`，响应 `text/event-stream`：OpenAI 格式 `data: {...}` 逐 chunk + `data: [DONE]` 结束。网关不缓存完整响应；中途断连按已产出估算结算，重试即可。

---

## 3. Messages（`/v1/messages`，Anthropic 协议）

Anthropic 形状端点，参数与官方一致（`model` / `max_tokens` / `messages` / `stream` 等）。

```bash
curl -s http://<gateway-host>:9401/v1/messages \
  -H "x-api-key: <凭证>" -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4-5","max_tokens":1024,
       "messages":[{"role":"user","content":"你好"}]}'
```

流式加 `"stream": true`，响应为 Anthropic SSE（`event:` + `data:` 形状，`message_stop` 结束）。

`/v1/messages/count_tokens`：不计费的 token 预估端点（不计费路径，用于调用方预算自检）。

---

## 4. Embeddings（`/v1/embeddings`）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | 是 | 向量模型名 |
| `input` | string \| array | 是 | 文本或文本数组 |

返回 OpenAI embedding 形状（`data[].embedding`）。上游为 Anthropic 形状时同样归一为 OpenAI 形状返回。

---

## 5. Images Generations（`/v1/images/generations`，同步生图）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | 是 | 生图模型名 |
| `prompt` | string | 是 | 提示词 |
| `size` / `n` | — | 否 | 尺寸 / 张数（按张数计费） |

> **注意区分**：本端点是**同步**生图（请求-响应即返）；异步图像任务（长耗时工作流）走任务面 `POST /v1/images`，见[任务面接入手册](./05_任务面接入手册.md)。

---

## 6. Models（`GET /v1/models`）

返回调用方可用模型目录（`object:"list"` + `data[]`）。**目录为空的常见原因**：凭证对应租户未开通任何模型——联系平台开通。

---

## 7. 常见错误（本面高频）

| code | 场景 | 处置 |
|---|---|---|
| 10617 | 预扣时余额不足 | 充值后重试；请求未转发，不产生费用 |
| 10400 | model 名不存在/无可用渠道 | 先 `GET /v1/models` 核对 |
| 10004 | 上游全失败 | 已自动全额退款，退避重试 |
| 10500 | 触发限流 | 读 `Retry-After` 指数退避 |

完整错误码表见[通用约定 §3](./03_通用约定.md)。

## 8. 接入验收清单

- [ ] base_url 指向网关，SDK 认证头选用 Bearer 或 x-api-key
- [ ] 成功判定按透传形状（无信封）；错误处理覆盖 10202 / 10500 / 10004 三类高频
- [ ] 429 时读取 `Retry-After` 指数退避（勿死循环重试）
- [ ] 非幂等调用带 `Idempotency-Key`
- [ ] 流式消费按标准 SSE 解析，处理 `[DONE]` 与中途断连
- [ ] 记录响应头 `X-Trace-Id` 用于排障

## 9. 后端/平台方入口

后端服务接入（能力面 yml 配置、鉴权三式、适配器开发）不属于本文，见开发文档：
[后端接入开发手册](../开发文档/02_后端接入开发手册.md) · [后端服务对接安全契约方案](../开发文档/04_后端服务对接安全契约方案.md) · [设计方案](../开发文档/01_设计方案.md)。
