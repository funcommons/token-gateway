# OpenAI 任务面接入手册

| 项 | 内容 |
|---|---|
| 文档 | OpenAI 协议任务面调用方手册（异步生图 background 模式 / 异步生视频 sora job 模式） |
| 前置阅读 | [产品简介](./01_产品简介.md) · [快速开始](./02_快速开始.md) · [通用约定](./03_通用约定.md)（认证/错误码/限流/幂等） |
| 配套 | 同引擎的网关原生协议见 [OneToken 任务面接入手册](./05_任务面接入手册.md)；LLM 面见[LLM 面接入手册](./04_LLM面接入手册.md) |
| 版本 | V1.1（2026-09-21，sora `seconds`→`duration` 计价别名与已知限制，issue #36）；V1.0（2026-09-14，issue #19） |

---

## 1. 定位：同一任务引擎，两种协议形状

任务面执行引擎（路由定价 → 全额预扣 → Worker 执行 → 终态退款/资源代理）对调用方暴露**两种同级协议**：

- **OneToken 协议**（`/v1/onetoken/*`）：网关原生任务协议，四模态全覆盖
- **OpenAI 协议**（本手册，`/v1/*` 官方路径）：OpenAI job 形状——**OpenAI SDK 可直接调用**，无需感知网关内部

两协议创建的是同一种任务：`id` 即 OneToken 协议的 `task_no`，可用任一协议轮询/取资源。

> **部署边界**：生视频端点（`/v1/videos*`）在 `face=task|all` 均可用；**OpenAI 协议异步生图端点仅 `face=task` 部署提供**（`face=all` 时 `POST /v1/images/generations` 归 LLM 面同步透传，异步生图请用 OneToken 协议或独立任务面部署）。

## 2. 端点总览（OpenAI 协议）

| # | 端点 | Method | 说明 | 部署 |
|---|---|---|---|---|
| 1 | `/v1/videos` | POST | 创建生视频任务（sora 形状）→ `video_generation` job | task/all |
| 2 | `/v1/videos/{id}` | GET | 轮询 job 状态 | task/all |
| 3 | `/v1/videos/{id}/content` | GET | 完成后 307 至视频代理 URL | task/all |
| 4 | `/v1/images/generations` + `background:true` | POST | 创建异步生图任务 → `image_generation` job | **仅 task** |
| 5 | `/v1/images/generations`（缺省 background） | POST | 同步生图封装（60s 超时降级 PROCESSING，见 §5） | **仅 task** |
| 6 | `/v1/images/generations/{id}` | GET | 轮询生图 job | **仅 task** |

鉴权双头（`Authorization: Bearer` 优先 / `x-api-key`），创建类端点支持 `Idempotency-Key`——同[通用约定](./03_通用约定.md)。

## 3. 异步生视频（sora job 形状）

```bash
# ① 创建（同步返回 job 对象：全额预扣，余额不足 10617 不产生任务）
curl -s http://localhost:9401/v1/videos \
  -H "Authorization: Bearer <凭证>" -H "Content-Type: application/json" \
  -d '{"model":"sora-2","prompt":"一只猫在城市夜景滑滑板","seconds":"8","size":"1280x720"}'
# → {"id":"T20260914...","object":"video_generation","status":"queued","created_at":1760000000}

# ② 轮询（3~5s 驱动；终态幂等）
curl -s http://localhost:9401/v1/videos/T20260914... -H "Authorization: Bearer <凭证>"
# → {"id":"...","object":"video_generation","status":"in_progress"}
# 终态: status=completed / failed(含 error{code,message})

# ③ 取视频（完成后 307 重定向至签名代理 URL，24h 有效；跟随重定向即得视频流）
curl -sL http://localhost:9401/v1/videos/T20260914.../content -H "Authorization: Bearer <凭证>" -o out.mp4
```

| 请求参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` / `prompt` | string | 是 | 模型名（路由定价依据）/ 提示词 |
| `seconds` | string | 否 | 时长（透传上游，如 `"4"` `"8"`；计价维映射见下） |
| `size` | string | 否 | 分辨率（如 `"1280x720"`） |
| `notify_url` | string | 否 | 网关扩展：终态回调（OneToken 协议同款验签退避语义） |

状态映射：`queued`（PENDING）→ `in_progress`（RUNNING）→ `completed` / `failed`（FAILED 与 EXPIRED 并入，`error` 携带上游/超时信息；EXPIRED 已自动全额退款）。

**计价维度（sora 形状，issue #36）**：

- `seconds` 经别名映射进计价维 `params.duration` 参与路由定价；显式 `duration` 优先（`seconds` 仅在缺 `duration` 时补位），`seconds` 键本身不进计价通道。执行载荷不变——Worker 收到的仍是原样 `seconds`。
- 已知限制：`size`（如 `1280x720`）**不做映射**——不进入复合档 `ratio|resolution` 段；sora 形状计价仅适用 duration 维或全通配/平价构型。
- 缺 duration（`seconds` 与显式 `duration` 均缺）时 PER_SECOND 构型显式拒绝：能力面 10608 → HTTP 500 `pricing_unavailable`。
- token-route 分支（`adapter=tokengo/openapi`）暂不下发 dims 计价参数。

## 4. 异步生图（background 模式）

```bash
# ① 创建（background:true = OpenAI 官方异步语义）
curl -s http://localhost:9401/v1/images/generations \
  -H "Authorization: Bearer <凭证>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-image-2","prompt":"一只戴墨镜的猫","size":"1024x1024","background":true}'
# → {"id":"T20260914...","object":"image_generation","status":"queued","created_at":1760000000}

# ② 轮询至 completed
curl -s http://localhost:9401/v1/images/generations/T20260914... -H "Authorization: Bearer <凭证>"
```

completed 响应（OpenAI images background 形状，`url` 为网关签名代理 URL，24h 有效）：

```json
{
  "id": "T20260914...",
  "object": "image_generation",
  "status": "completed",
  "output": [
    { "content": [ { "type": "output_image", "image_url": { "url": "/v1/resources/T20260914.../0?exp=...&sig=..." } } ] }
  ]
}
```

参数与同步封装一致（`model`/`prompt` 必填，`size`/`ratio`/`resolution` 透传，**`n` 仅支持 1**）。

## 5. 同步生图（缺省 background）

`POST /v1/images/generations` 不带 `background`（或 `false`）时走**同步封装**：内部创建任务并轮询至终态一次返回，语义与 [OneToken 手册 §3.5 `/v1/onetoken/images/sync`](./05_任务面接入手册.md) 完全一致（成功 `{created, data:[{url}]}`；失败 502 已退款；60s 超时降级 `{status:"PROCESSING", task_no, poll_url}` 继续异步）。

## 6. 语义要点（与 OneToken 协议同源）

| 事项 | 语义 |
|---|---|
| 计费 | 创建即**全额预扣**（按路由命中模型定价）；FAILED/EXPIRED 自动全额退款；completed 不退 |
| 任务 id | OpenAI 协议 `id` = OneToken 协议 `task_no`（`T` 开头），两协议可互查 |
| 资源 | **上游原始 URL 永不透传**；一律网关签名代理 URL（24h exp+sig） |
| 超时 | 超时钟到期 → EXPIRED → OpenAI 协议呈现为 `failed` + TIMEOUT error + 已退款 |
| 幂等 | 创建端点支持 `Idempotency-Key`；轮询终态幂等 |

## 7. 接入验收清单

- [ ] `background:true` 走异步（拿 job id 轮询）；缺省走同步（60s 超时按 PROCESSING 分流）
- [ ] 轮询终态后停止；`failed` 读 `error.message`（EXPIRED 已退款）
- [ ] 视频经 `/content` 307 取流；图片 `output[].content[].image_url.url` 过期（24h）后重查任务再取
- [ ] 记录 `X-Trace-Id` 排障；创建类调用带 `Idempotency-Key`
