# 更新日志

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.16.0] - 2026-09-22

### 修复

- **moderation.enabled 开关落地（issue #38）**：`token-gateway.moderation.enabled`（默认 false）javadoc 承诺「off 时管线跳过审核步骤」却从未被运行管线消费——此前**所有部署**逐请求无条件发审核 RPC（scan + audit 双路），宿主无审核端点时 fail-open 兜底业务无感但逐请求刷 ERROR。修复：`HttpModerationApi` 双闸（scan → 短路 PASS 回显原文 / audit → `Mono.empty()` 放行），enabled=true 路径逐字节不变；`CapabilityValidator` 新增启动告警（`moderation.url` 已显式配置但 enabled=false → WARN 提示审核将被跳过，专抓靠 bug 扫描的误配）。⚠️ **升级注意**：开关自此生效——配了 `moderation.url` 但从未显式 `enabled: true` 的部署（此前一直在靠 bug 扫描）升级后审核将停止，需要审核请显式开启。已知缺口（另立 issue 候选）：`CapabilityValidator.validate` 在仓内零生产调用方，告警仅在宿主自行调用时可见

## [0.15.0] - 2026-09-21

### 新增

- **resolve 跳 clientIp 补位（issue #37，#27 同构）**：`DistributeRequest` 增可选 `clientIp`——validate 跳（#27）与 resolve 跳透传同一次 ClientIpResolver 解析结果，能力面 ip 白名单 Key 不再因 resolve 跳 clientIp=null 被 fail-closed 恒拒（10612）。三处接线：prepare 直达 / failover 重分发经 PreparedRequest 第 9 分量驻留 / 任务面 create→resolveRoute；null=旧语义零影响。**默认 error-passthrough 白名单首次扩员**：`10612→403`（ip 白名单拒绝，与 4090 同类 Key 级安全拒绝；此前恒拒映射 502+10004 会被误当可重试故障）——本版本唯一默认行为变化
- **settle/access-log usageSource 真相位（issue #35，#33 收尾）**：`SettleRequest`/`AccessLogRequest` 增可选 `usageSource`（`UPSTREAM`=上游实测 / `ESTIMATED`=网关内容估算，取值对齐能力面 schema `usage_call_log.usage_source` CHECK 约束；缺省 null=旧语义）——估算 settle 与实测 settle 在能力面账上从此可区分，风控日批可筛除启发式数字。LLM 流式分支点赋值（hasUsage?UPSTREAM:ESTIMATED 双侧）、非流式 UPSTREAM、任务面 null（amount 直传无估算概念）；#23 重放保真扩展一位（BillingPendingRecord 携带）。Embeddings/Images 两端点暂走旧签名=null（合法，后续按需接新重载）

### 修复

- **sora 形状计价 dims 断层（issue #36，资损口）**：`POST /v1/videos` 的 `seconds` 此前不入计价维白名单 → PER_SECOND 构型显式拒绝不可用、带 duration 复合档静默底档少收。`extractPriceParams` 增 `seconds→duration` 别名（显式 duration 优先，顶层/params 内嵌均识别，值原样透传，seconds 键不进计价通道，执行载荷不变）；OneToken 四键通道零变化回归。已知限制（文档明示）：sora `size` 不映射 ratio|resolution 复合档（sora 计价仅适用 duration 维或全通配/平价构型）；token-route 分支暂不下发 dims
- **契约 yaml 隐性截断**：03_能力面接口契约.yaml 多条 plain-scalar 描述含 ` #2x` 被 YAML 当注释截断（cache_creation_tokens 条甚至破坏多行标量），转块标量修复，PyYAML 实测可解析

## [0.14.0] - 2026-09-21

### 修复

- **幂等 body hash 规约（MMagiX2 回归 2026-09-21-01 BL11 P2）**：`Idempotency-Key` 回放补 body 一致性校验——WebFilter 读请求体算 MD5（装饰重放给下游，主链零重复读取），占位值升级 `h:<md5>`（旧 `"1"` 兼容视为无 hash 放行）、首响条目附 `bodyHash`；同 key 异 body 不再回放错误首响（决定性证据：同 key + 未知模型 body 曾回放缓存的正常响应）→ **422 + 10100 拒绝**「已被不同请求体使用」；`IdempotencyStore` default 双签名向后兼容（未覆写实现退化为旧语义零破坏）

## [0.13.0] - 2026-09-21

### 新增

- **billing 双 face 拆分（issue #31，P0）**：第 8 面 `token-gateway.task.billing.{url,path-prefix,auth,jwt-secret,internal-token,timeout}`——**逐字段**缺省回退 `token-gateway.billing.*`。任务三端点（全额 amount 直传 + settle 确认制）与 LLM 面三端点（token 估价 + settle 四维重算退差）在 face=all 单体内不再互斥：`TaskBillingSaga` 走 task 面（新增任务族方法），`RelayOrchestrator`/`BillingReconcileJob`（#23 重放）恒通用面（隔离测试实证不串）。存量 #12 全局前缀指 task 路径 → 行为不变零迁移。demo-control-plane 计费桩补 `/v1/internal/billing/task/*` 双前缀 + 账本 amount 直传修正（此前被忽略一律 flat price）
- **上游头透传白名单（issue #32，方案 A）**：`gateway.upstream.passthrough-headers`（默认空=**fail-closed**）支持精确名与 `X-Mock-*` 前缀通配，从客户端请求头透传至上游——流式 SSE + 非流式全部上游构造点一次做齐；硬编码黑名单压过白名单（Authorization/Cookie/X-Internal-*/内部签名头/协议头，防凭证泄漏与顶掉协议头），解锁流式断连 chaos 故障注入
- **流式无 usage 内容估算法（issue #33）**：拍板「内容估算法」——无 usage 正常完成流 completion 由固定 256 改为 `max(1, 已吐内容 chars/4)`（content/thinking/tool `partial_json` 都计入；透传与 OpenAI↔Anthropic 双向转换四组合覆盖），吐 2 帧收 2 帧、吐 8K 收 8K 双向公平；仍 SUCCESS settle 不退款（无薅羊毛向量），有 usage 路径逐字节不变

### 修复

- **限流 429 绕过 error-shape（issue #34）**：`RateLimitWebFilter` 在 WebFilter 层直写响应、不进 #24 渲染链——429 拒绝分支接入 error-shape 分流（openai + /v1/chat|/v1/messages 前缀 → `{"error":{type:rate_limit_error,...}}` + 顶层 trace_id，param 显式 null 与 Jackson 出口对齐），`Retry-After`/`X-RateLimit-*` 头原样保留；任务面恒信封，默认 envelope 行为不变

## [0.12.0] - 2026-09-20

### 新增

- **LLM 面 settle/refund 失败重放兜底（issue #23，P0）**：新增 `BillingPendingStore`（Redis ZSET `tgw:billing:pending`，score=下次重试时刻）+ `BillingReconcileJob`（`gateway.billing-reconcile.*` 默认 true/30s/5 次/30s 指数退避）——settle/refund **基础设施失败**（10003 折叠码）按原参重放（preConsumeId 幂等锚），重放成功/退避重排/耗尽死信三态日志（`[Billing-Reconcile]`/`[Billing-DeadLetter]` 单行 JSON 快照含全部结算参数）；业务拒绝不重试直接死信；入队失败兜底死信
- **错误契约保真（issue #24，P0）**：LLM 面（/v1/chat、/v1/messages 前缀）错误响应形状可切换 `gateway.error-shape: envelope（默认）|openai`——openai 形状 `{"error":{message,type,param,code}}` + 顶层 `trace_id`（OpenAI SDK 直接可解析；`error.type` 按 HTTP 状态映射：401→authentication_error / 402→insufficient_quota / 403→permission_error / 404→not_found_error / 429→rate_limit_error / 5xx→api_error / 其余 4xx→invalid_request_error；`error.code`=网关业务码字符串，message/trace_id 原样保留）；默认 envelope 存量接入方零影响，任务面/内部端点恒信封。**能力面业务码透传白名单** `gateway.error-passthrough-codes`（默认 4090→403、10601→402、10602→404、10603→404、10402→409；配置按键合并、同键覆盖）：distribute/preConsume 失败原码命中 → 客户端直收原码 + 语义 HTTP 状态（白名单优先于既有 10400·20103→404、10617→402 映射），未命中走既有映射，白名单外原码不进网关公开错误码空间。通用约定/FAQ/部署手册（中英）同步
- **任务面 access-log 接线（issue #29）**：新增 `TaskAccessLogger`——任务受理（3 controller create 成功路径）+ 终态（TerminalEventHandler onTerminal/onExpired）各上报 1 条，fire-and-forget 不阻塞主链；taskNo 以 `?task_no=` 附 requestPath（契约不变）；排障四系统（work→网关→lotask→worker）串联缺口闭合

- **幂等回放语义（issue #30 完整修 + #28 主解）**：`Idempotency-Key` 从拒绝式升级为**首响回放**——2xx 非流式 ≤1MB 响应缓存（`IdempotentResponse` record + 单 key 双形态：占位 "1"/响应 JSON，TTL 回填无孤儿），TTL 内同凭证同 key 原样回放（同 status/body + `Idempotency-Replayed: true` 头）；处理中/流式/超限 → 409+10501（message 区分「处理中」）；失败不占键（非 2xx 释放，语义较旧版收紧：4xx 也释放）；`IdempotencyStore` 以 default 方法扩展三 API，存量实现零破坏。**#30 头语义 bug 修复**：非数字 `Idempotency-Key` 不再透传 billing requestId 位（workId 要求纯数字，此前按文档带 uuidgen key 100% 失败 502/10004），distribute 幂等透传保留、纯数字 key 语义不变（#12）。契约 yaml（08/09）「拒绝式」表述同步回放语义

- **usage 细分对齐（issue #26）**：`TokenUsage` 扩 reasoning/audio/cacheCreation 三维（无源 null 不造数）；settle/access-log 留痕透传；**两条转换链计费口径损失修复**——`openAiToAnthropicResponse` 此前丢 `cached_tokens`（Messages+OpenAI 上游 cacheRead 计 0）、`anthropicToOpenAIResponse` 此前丢 `cache_creation_input_tokens`；Anthropic cache 拆分定版（creation 不再并入 cached，prompt 总量语义不变）；四象限（双协议×流式/非流式）断言 + 端到端 settle body 校验；字段映射对齐 Spring AI 1.1.2 `OpenAiApi.Usage`

- **validate 契约补位（issue #27）**：`clientIp` 死字段激活——新增 `ClientIpResolver`（信任代理策略 `gateway.client-ip.{enabled,trusted-proxies}`，默认 0=恒取 TCP 对端防伪造；XFF 按标准逐跳追加语义从右数第 N 段，对端 null/段耗尽/畸形 fail-safe 回退）+ `TokenValidateVO.subAccountId` 可选回传；LLM 面 6 controller + 任务面 create/poll 全链路填充，controller 级透传断言 4 条
- **渠道健康上报补维度（issue #25）**：`RecordFailureRequest` 增 `upstreamStatus`/`latencyMs` 可选字段；`CANCELLED` errorCode 常量口径定版（499 当前不上报）；`ChannelHealthReporter` javadoc 漂移修正（4xx/5xx+软失败均报）；03 契约 yaml 分区补 Mmagix 形态 record-success/failure 真实契约

### 文档

- **任务面委托形态定版（issue #29 文档部分）**：`/gw/v1/task/*` 与 `TaskClient` SPI 全仓标注「预留契约、当前无实现、任务执行=lotask4j 托管形态」（中英 8 文档 + 03 yaml）；顺手修正 en design.md 形态表漂移
- **客户端 IP 解析入安全契约**：《04_后端服务对接安全契约方案》新增「客户端 IP 解析」节（伪造风险 + 信任代理策略 + 部署要求），中英同步

## [0.11.0] - 2026-09-18

### 新增

- **OneToken BASE64 大载荷入站口径 128MB（可配置）**：入站请求体 `spring.codec.max-in-memory-size` 默认 16MB→**128MB**（env `GATEWAY_MAX_BODY_SIZE`）；submit 出站载荷保持无网关侧上限（随入站请求体边界）。FAQ/部署手册同步

### 文档

- **文档治理一轮**：README（中英）对齐当前状态（阶段标记、446 测试、v0.10.0 版本引用、模块树补 starter/demo、文档表补 06/部署手册、新增贡献/安全段）；快速开始（中英）修正第三步端点形状（`/v1/videos` OpenAI job 形状误配 OneToken 形状 → 改 `/v1/onetoken/videos`）与 starter 版本引用；任务面手册（中英）状态横幅更新为全链路可用 + 资源代理 `.{ext}`/inline/`base64` 输出语义；FAQ（中英）新增 504/10003 与 base64 参考图条目；v0.1.0 测试报告归档至 `docs/archive/`；新增《部署运维手册》《CONTRIBUTING》《SECURITY》；vitepress 关闭 `ignoreDeadLinks`（构建即死链检查）
- **文档治理二轮**：中英镜像缺口补齐（backend-onboarding/security-contract 补 #22 的 504/10003 分支、任务面手册 en 补资源代理 `.{ext}` 与轮询 504 语义）；《在线文档方案》归入开发文档编号体系（09）；新增关键文档模板三件（`docs/_templates/`：ADR/测试报告/发布说明，站点构建排除）；README（中英）与 sidebar 同步

## [0.10.0] - 2026-09-18

### 修复

- **token 校验 RPC 失败与 key 真失效语义分离（issue #22）**：`HttpTokenApi.validate` 的 RPC 失败 fail 包络（10003）此前被 4 处判定点（TaskRelayOrchestrator submit/poll、RelayOrchestrator prepare、ModelsController /v1/models）与「key 真无效」混在一个 `||` 里，一律映射 401/10202 invalid token —— 主应用瞬时抖动即误导接入方按 key 失效排障（融光 ai-fusion-video 实踩：轮询 401 判死任务，实际 worker 已 SUCCESS，成功回调成孤儿）。拆两段式判定：RPC 失败/超时 → **504 + 10003**（可重试基础设施错误）；key 真失效 → 维持 **401 + 10202**。《通用约定》（中英）错误码表同步，测试 +5
- **OneToken 未知模型 20103 → HTTP 404**：bootstrap `WorkDistributeService` 抛 `MODEL_NOT_FOUND(20103)` 时网关此前仅把 10400 映射 404，其余落 502+10004 —— 接入方把确定性客户端错误当可重试上游故障。`TaskRelayOrchestrator.resolveRoute` 与 `RelayOrchestrator.obtainRoute` 判定扩展 10400‖20103，信封归一 10400，bootstrap 原始 message 保留 reason

### 测试

- 439 → 446（未知模型双编排器回归 + token 校验语义分离 5 例：RPC 失败 504 / key 失效 401 双分支，任务面覆盖 submit/poll）

## [0.9.0] - 2026-09-17

### 新增

- **worker tick 批量拉取**：`pull-batch-size` 链式认领，单 task_type 认领吞吐 0.2→6 单/s

### 修复

- **OneToken 计费维度丢失（P0 系统性少收）**：`TaskRelayOrchestrator.resolveRoute` 原只读 body 顶层 `size/ratio/resolution`，而 OneToken 协议（issue #20）的计费 dims 在 `body.params.*` → 提取为空 → 协议面 `readModelPriceQuote` 静默兜底 `1:1|1K` 底档，复合档（IMAGE_BY_SIZE）模型一律按最低档计价：2K 实扣 8 应 15（-47%）、4K 实扣 8 应 60（**-87%**）。抽 `extractPriceParams` 合并双形状（顶层同键覆盖 params）+ 补 `duration` 键（视频复合档）；实测（runninghub-gptimage2-4k）：修复前 4k/2k 均 8.00，修复后 4k=60.00、2k=15.00。MMagiX2 协议面同步补 dims 全缺 WARN（静默少收可观测）
- **资源代理 Web 可看性**：代理响应恒 `application/octet-stream` 且 URL 无扩展名，浏览器不内联渲染。三层 Content-Type 解析（回源捕获上游响应头 → 上游 URL 扩展名 → 旧缓存魔数嗅探 PNG/JPEG/GIF/WEBP/MP4/PDF）+ `{index}.ct` sidecar 持久化；路径支持 `/{index}.{ext}` 双形态（剥离后验签，与无后缀 URL 同 sig，向后兼容），新签 URL 按 `usage.outputType` 自动带后缀；`Content-Disposition: inline` + 文件名带扩展名。实测：8.7MB PNG 新旧双形态均 `image/png` 内联，字节与上游一致

### 测试

- 431 → 439（计费透传回归 2 + 资源代理 Web 可看性 4 + worker 批拉相关 2）

## [0.8.0] - 2026-09-14

### 新增

- **OpenAI 协议任务面（issue #19，任务面双协议）**：OpenAI job 形状挂任务引擎，OpenAI SDK 可直接调用；`id` = OneToken 协议 `task_no`，两协议互通
  - 生视频：`POST /v1/videos`（sora 形状 prompt/seconds/size → `video_generation` job）+ `GET /v1/videos/{id}`（queued/in_progress/completed/failed）+ `GET /v1/videos/{id}/content`（307 至签名代理 URL）；face=task/all 均可用
  - 异步生图：`POST /v1/images/generations` + `background:true` → `image_generation` job，`GET /v1/images/generations/{id}` completed 带 `output[].content[].image_url`（代理 URL）；缺省 background 走同步封装（与 `/v1/onetoken/images/sync` 同款三出口）；**仅 face=task 注册**（face=all 时该路径归 LLM 面透传，避免映射冲突）
  - 状态映射：PENDING→queued / RUNNING→in_progress / SUCCEEDED→completed / FAILED+EXPIRED→failed(error)；计费/幂等/资源代理同任务面语义
  - 新增《OpenAI 任务面接入手册》（中英，与 OneToken 手册同级；FAQ/契约 yaml 顺延重编号 07/08/09）；测试 +7（424→431）

### 变更（Breaking）

- **任务面端点迁移 `/v1/onetoken/*`（issue #20，硬切）**：网关自有任务协议与 OpenAI 官方端点解耦——`/v1/*` 只留 OpenAI/Anthropic 官方形状（SDK 兼容面），四模态 create/poll 与同步生图封装挂 `/v1/onetoken/*`（`/v1/onetoken/{videos,images,audios,tts}`、`/v1/onetoken/images/sync`）；`poll_url` 字段值同步。旧路径移除（调用方双侧可控）；`/v1/resources/**` 原路径保留（在途 sig URL 不断）。为 #19（OpenAI `background:true` 轮询透传）腾出 `GET /v1/images/generations/{id}`

### 文档

- **用户文档协议命名纠正**：OneToken 是协议名（`/v1/onetoken/*`）而非产品名——任务面手册定名「OneToken 任务面接入手册」、08 契约「token-gateway API · OneToken 任务面」；其余文档不冠协议名。新增「OpenAI 任务面接入手册」与 OneToken 手册同级（OpenAI 协议异步生图/生视频，见下「新增」）
- **任务面手册补「同步生图封装」`POST /v1/images/sync`**（中英 + 08 契约 yaml）：OpenAI 形状请求/三出口（成功 data/[url]、502 上游失败已退款、60s 超时降级 PROCESSING+poll_url）、n=1 语义、计费/幂等同任务面口径——v0.6.0 引入的端点此前零文档
- **开 issue #19**：OpenAI 官方 `background:true` 异步生图经网关 submit 通但轮询断链（face-llm 缺 `GET /v1/images/generations/{id}`），含计费点/幂等/job 渠道绑定四项待决口径

## [0.7.0] - 2026-09-14

### 修复

- **OpenAI 协议 tools 跨协议全链路丢失**（issue #18）：OpenAI 调用方带 `tools` 打到 `protocol: anthropic` 渠道时，请求/历史/响应/流式四断点全丢——
  - A1 请求：`openaiToAnthropic` 补 `tools`（function 型 `{function:{name,description,parameters}}` → `{name,description,input_schema}`，非 function 型透传）与 `tool_choice`（`required`→`any` / `{function:{name}}`→`{type:tool,name}`）转换
  - A2 历史：assistant `tool_calls` → `tool_use` 块（arguments JSON 串解析为 input 对象）；连续 `role=tool` 消息合并为 `tool_result` 块并入紧随 user 轮（`tool_call_id` 不丢）
  - A3 响应（非流式）：`anthropicToOpenAIResponse` 补 `tool_use` → `message.tool_calls`（仅工具调用时 `content=null` 对齐 OpenAI 语义）；`stop_reason=refusal` → `finish_reason=content_filter`（对齐流式映射）
  - A4/B3 流式双侧：`OpenAiSseConverter` 补 `content_block_start(tool_use)`/`input_json_delta` → `delta.tool_calls` 增量（Anthropic 块号 ↔ OpenAI tool 序号映射）；`AnthropicSseConverter` 补 `delta.tool_calls` → `tool_use` 块事件（关旧开新，参数增量 `input_json_delta`）
  - 顺带三修：`stop` → `stop_sequences`（原拷贝不存在的键致静默丢参）；`max_completion_tokens` 回退（新版 OpenAI SDK 默认键，原恒落默认 4096）；Anthropic `source.type=url` 图片直传 `image_url.url`（原静默丢弃）
  - B 方向流式 usage：`AnthropicSseConverter` 解析 OpenAI usage 末帧并入 `message_delta.output_tokens`（原调用方从 SSE 读到的 token 数恒 0；计费不受影响——settle 走独立 usageAcc）；`message_delta` 延迟到 usage 帧后发（OpenAI usage 在 finish 帧后到达，Anthropic 协议允许靠后）
  - 测试 +7（A 方向请求/历史/响应 / R1 refusal 映射 / URL 图保留 / 双侧流式 tool 事件序列 / B 方向流式 usage）
  - **已知有损面**（无对应物，不做映射，记录备查）：`presence/frequency_penalty`、`response_format`(JSON mode)、`n`、`seed`、`logprobs`（A 方向）；`top_k`、`thinking`、`tool_result.is_error`（B 方向）；gemini 转换器为未接线死代码

## [0.6.0] - 2026-09-10

### 新增

- **submit-task-type 非法值启动告警**（#13 补强，v0.5.0 后置）：`CapabilityValidator` 对 `task.submit-task-type` 非 `modality|model` 输出 warning（仍回退 modality 建单，不阻断）——防「意图配 model 拼错 → 静默回退 → 任务无人认领永 PENDING」；配套文档收口（05/06/07 开发手册 + 用户任务面接入手册，中英）与 app yml 配置示例注释
  - 测试 +1（validator 非法值告警 / 合法值不告警）；v0.5.0 tag 内测试 +2（粒度解析 / 编排器 model 粒度）

### 文档

- **LLM 面手册新增「Spring AI 客户端接入」**（中英）：Spring AI OpenAI/Anthropic starter 指网关 base-url 即接入，网关能力（路由定价/计费 saga/failover/审核/限流）对流经的 Spring AI 流量自动生效；含凭证语义、模型目录核对、错误信封处置、Idempotency-Key 注入与任务面边界注意事项

### 修复

- **billing 面三端点路径前缀可配**（issue #14，接 #12 同款机制）：`token-gateway.billing.path-prefix`（默认 `/api/v1/internal/billing` chat 契约不变），任务面接入方指 `/v1/internal/billing/task` 接自有计费变体；E2E 实测此前 task 计费请求误入 chat 端点被幂等吞掉
- **ScriptHttpClient 显式 Content-Type 覆盖语义**（issue #15）：原 `spec.header` 追加致 `application/json,application/json` 重复头，严格上游 400；`get/post/postMultipart/getBytes` 四路径一致，测试 +2
- **资源代理回源 URI 重载**（issue #16）：`uri(String)` 模板模式对预编码签名 URL（OSS `Signature=%2B...`）重复编码致上游 403，改 `URI.create` 绕过模板处理
- **ScriptHttpClient 响应缓冲放宽 32MB**（issue #17）：WebClient 默认 256KB 对任务面资源（gpt-image 系列恒回数 MB b64_json、图片/音频二进制）必超限，`Exceeded limit on max bytes to buffer` 显式失败

## [0.5.0] - 2026-09-09

### 新增

- **task-worker 远程脚本源**（issue #9，MMagiX 任务类模型转接形态）：`worker.script-source: local | remote`（默认 local，行为与现状 100% 一致，现有部署零感知）
  - remote 契约：`GET {base-url}/{taskType}` → 信封 `{version, hooks}`（单文件 Groovy 三钩子）；`cache-ttl`（**默认 60s 可配**）窗口内跳过 HTTP，过期拉取比对版本，不变不落盘
  - **降级自举**：成功拉取落本地副本（`<scriptsDir>/.cache/<taskType>/v-<version>.groovy`，原子写）；控制层不可达时降级用最后副本 + WARN，已在跑的 taskType 不中断；首启控制层不可达亦可从磁盘缓存自举
  - **fail-fast**：remote 模式 `base-url` / `task-types` 缺失即启动报错（误配裸地址跑成空脚本比报错更糟）；鉴权 `X-Internal-Token` env 注入
  - **任务内版本锁定澄清**：runTask 开始即持有不可变 `ScriptAsset` 引用，热载只换索引——create/poll/resultMapping 恒同版本；跨 Worker 重启重领任务整体重跑 create（lotask 既有语义）
  - 测试 +10（拉取/鉴权头/ttl 节流+版本跳过/版本切换+旧副本清理/宕机降级+恢复/磁盘自举/fail-fast ×2/信封无效/local 不索引 .cache）

- **任务面 submit task_type 粒度可配**（issue #13）：`token-gateway.task.submit-task-type: modality | model`（默认 modality 现状不变）
  - remote Worker 脚本以 modelCode 索引（`worker.script-remote.task-types`）时须配 `model`，否则 lotask 任务按模态建单、Worker 按模型编码拉单，无人认领永 PENDING（MMagiX E2E 实测发现）
  - 仅影响 lotask 侧 task_type 及同键的 `timeouts`/`TaskMeta.modality`（超时钟、终态 TTL 同粒度解析）；网关 API 面不变（`poll_url` 仍 `/v1/{modality}s/{taskNo}`）
  - fail-safe：`model` 粒度但 body.model 缺失/空白、或配置非法值，一律回退模态，不阻塞建单

- **face-task 分发端点配置化 + 接入方幂等键/全额透传**（issue #12，MMagiX T3 切流硬依赖）：
  - `token-gateway.route.distribute-path`：route 控制面分发端点可指（默认 chat 端点行为不变），MMagiX work 域指向 `/api/v1/internal/work-channels/distribute`
  - `DistributeVO.priceQuote` → `PreConsumeRequest.amount`：接入方分发响应回传全额积分，billing pre-consume 透传（null = 既有 token 估算行为不变）；`TaskBillingSaga.preConsumeFull` 重载
  - `TaskController` 四 create 端点接收 `Idempotency-Key` → `DistributeRequest.idempotencyKey` 携带至分发端点（MMagiX work-distribute 凭它=workId 回读快照价与渠道粘性），并优先作为 billing requestId（对账闭环）；chat 路径不传行为不变
  - app yml 固化接入形态：route/token-validate/billing 三面 `auth=jwt` + `jwt-secret` env 注入（`GATEWAY_BACKEND_INTERNAL_TOKEN`）
  - 测试 +9（distributePath 透传 / priceQuote→amount 透传 / 幂等键优先 / idempotencyKey 契约）

- **ScriptHttpClient multipart 与二进制下载**（issue #11，脚本素材上传/下载主路径）：
  - `http.postMultipart(url, parts)`：标量 + bytes 零件（ByteArrayResource 携 filename），图片/视频素材表单提交
  - `http.getBytes(url)`：二进制下载（参考图取回），出网白名单同约束
  - Worker 契约测试 +4（multipart 零件结构 / bytes 往返）

- **route 面走 token-route 的 table-id 门控**：`AdapterSelector.routeViaTokenRoute()` 除适配器族（tokengo|openapi）外还要求 `token-gateway.route.table-id` 非空白——未配置时静默回落 distribute 契约，部署面可先起进程、配齐重启即切 token-route
- **settle 契约补 owner**：`SettleRequest.ownerPartyId`（缺省 null = 旧语义，计费后端落 0 占位）；face-llm settle 自 token.user_id 数值化透传（非数值/≤0 → null 不误导对账），与 pre-consume userId 同源同口径

## [0.4.0] - 2026-09-05

### 新增（TokenGo 组件化改造 G1-G6）

- **G1 SPI 装配收尾**（issue #2）：能力面寻址收口到 `token-gateway.*` 七面配置（`CapabilityEndpoints`）——每面独立 url/auth（jwt/key/none 三式，`RpcInternalAuth` 面感知化）/超时；`TokenGatewayProperties` 绑定收口到 core（`SpiPropertiesConfiguration`）；**兼容窗口**：面 url 未配置回退 `gateway.backend.*`，存量部署零配置迁移
- **G2 tokengo 适配器**（issue #3）：`AdapterSelector` 启动期校验 adapter 单选（非法值 fail-fast）；`TokenRouteClient`（resolve 选路 + report 三态回报，data_json 契约字段映射 DistributeVO）；adapter=tokengo|openapi 时 route 面走 token-route，mmagix/tokenhub 路径零触碰
- **G3 鉴权 env 化**（issue #4）：app yml baked JWT 与 THMP dev 口令全部移除（env 注入，`GATEWAY_INTERNAL_TOKEN` / `THMP_CONTRACT_KEY_PASSPHRASE`）；双值窗轮转沿用 lotask webhook 主/备钥 grace 验签（`tenant-secret-previous`）；新增 `BakedCredentialScanTest`（仓库配置禁 baked JWT/明文密钥的 grep 断言）
- **G4 settle 携带 attempt 明细**（issue #5，M1 向后兼容增量）：`SettleRequest.attempts` 可选字段（sequence/channelId/model/errorClass/billed/用量），软失败识别升级 `SoftUpstreamException`（billed=true 供能力面记 LOSS 路由损耗）；轮换 controller 累积失败尝试，settle 携带 1 MAIN + N LOSS
- **G5 任务面接 token-route**（issue #6）：`TaskRelayOrchestrator` adapter=tokengo|openapi 时 route 走 token-route resolve；路由快照随 submit 下发 Worker 链路不变
- **G6 ModerationGate fail 策略可配**（issue #7）：`token-gateway.moderation.fail-open` 可配（缺省 true 现网口径；fail-closed 时审核面故障按 BLOCK 拦截不误放）；回归用例：审核 BLOCK 不触发渠道轮换（上游零调用、无 record-failure/refund）

## [0.3.0] - 2026-09-05

### 新增

- **嵌入模式 starter（`token-gateway-spring-boot-starter`）**：在自己的 WebFlux 应用引用 starter 即装配网关，装配口径与独立部署 app 完全同构（core 十包 + face 条件装配）
  - 自动装配：`AutoConfiguration.imports` 注册 `TokenGatewayAutoConfiguration`（component-scan core 十包 → FaceLlmAssembly/FaceTaskAssembly 按 `token-gateway.face` 条件生效）
  - **Worker 嵌入闭环**：`token-gateway.worker.enabled=true`（缺省关闭，face=task|all 生效）把任务执行 Worker 一并装进宿主进程——引用 starter 即完整 token-gateway，嵌入任务面无需另起 Worker 进程；`TokenGatewayProperties` 绑定 bean 双侧条件化（TaskFaceConfiguration/WorkerConfiguration，防同进程 BeanDefinitionOverrideException，独立部署零影响）
  - 条件防御：仅 reactive web 宿主装配（MVC 宿主静默不装配）；`token-gateway.enabled=false` 一键关闭（缺省开启）
  - **JitPack 发布**（同 framework4j 口径）：加 `.jitpack.yml`（jdk 17），用户引 `com.github.funcommons.token-gateway` + tag 即版本，零发布基础设施
  - 测试 +9：ApplicationContextRunner 条件矩阵（face 分组 ×3/enabled/非法值 fail-fast[ISE 经 scan 包装为 BeanDefinitionStoreException，断言 cause 链最深处]/worker 开关 ×3）+ 真实宿主 @SpringBootTest 嵌入冒烟

## [0.2.0] - 2026-09-04

### 新增

- **请求内渠道轮换（failover）**（移植 MMagiX cffc1ea，face-llm）：
  - 渠道可重试失败（网络故障/上游 5xx）自动退款 → 排除已失败渠道重新 distribute → 重新预扣重试；`gateway.failover.enabled/max-attempts/base-backoff-ms` 可配，退避 `base*2^(n-1)` ±20% 抖动，`enabled=false` 退化为单渠道快速失败
  - 流式请求在吐帧前轮换（已吐帧不重放，防两段回答拼接）；非流式补齐客户端取消退款
  - 渠道 `model_mapping` 重映射配置生效（此前被完全忽略，配置重映射的渠道会收到上游不存在的模型名）
  - 健康上报不双计：中间轮换轮显式 record-failure，轮换中止时旧渠道失败由终态访问日志恰好计一次（防单渠道模型组加速误封）

### 修复

- **face-llm 渠道健康上报三缺口**（issue #1，新增 `UpstreamErrorPolicy`，口径对齐 MMagiX gateway）：
  - **软失败识别**：上游 `200 + {"error":{...}}` 错误载荷（容错型代理网关的典型故障形态）不再按成功结算并触发 record-success 清零渠道失败计数——协议转换前识别错误体（OpenAI/Anthropic 双形态），按其真实 `status` 上抛并走退款路径
  - **审核失败/内容违规不再误记渠道失败**：输出审核 RPC 故障与违规 BLOCK 走 `reportErrorWithoutHealth`（访问日志照记，渠道健康信号不触发），健康渠道不再因审核服务抖动被误冻结
  - **上游真实状态码透传**：401（key 失效）/429（限流）/400/5xx 不再一律压成 502，渠道失败 errorCode 采用 `HTTP_<status>` / `UPSTREAM_ERROR` 分类；客户端信封亦透传上游状态（`upstream error HTTP_<status>` 前缀可区分上游故障与自身凭证问题）；客户端取消（499）仍不上报渠道健康
  - 测试 339→362（+23：策略单测 10 + AccessLogReporter 健康分流 5 + 四 controller 软失败/真实状态码端到端 8）；全链路冒烟 29/29 回归通过

### 测试基建

- **JaCoCo 覆盖率门禁**：agent + report 全模块，`check` 绑定 `verify` 阶段；模块阈值按实测值回落 3pp 设置并随覆盖率提升两轮 ratchet（当前 gateway-core 86% / task-worker 80% / face-llm 75% / face-task 72% / gateway-spi 77%，demo-control-plane 与 app 为非生产制品豁免），低于阈值构建失败
- **覆盖率提升专项**（gate 接入当日实测 → 提升）：gateway-core 83.5%→89.2%、task-worker 52.2%→83.9%、face-llm 57.4%→78.8%、gateway-spi 62.4%→80.0%、face-task 61.6%→75.0%；新增 FormatConverter 全分支表驱动 / AnthropicToolChainSanitizer / WorkerLotaskClient（MockWebServer）/ DryRunController / ResourceProxyController / 超时钟+对账 job / NotifyDispatcher（签名+退避）/ HttpChannelApi / OnFaceCondition / SPI 模型速测共 10 个测试类
- CI 由 `mvn package` 升级为 `mvn -B verify`（门禁生效），并上传各模块 jacoco.csv 报告制品
- **全链路冒烟负路径扩展（18 → 28 断言，notify 验签双侧设钥时满配 29）**：
  - 未知 task_no poll → 404 + 业务码 10400
  - webhook 篡改：携带真实 lotaskId + 伪造签名谎报 FAILED → 载荷被拒、verify-then-act 回查平台核实（非终态忽略）、状态不被污染、不产生 notify
  - 超时钟闭环：注入过期 deadline → 判定 EXPIRED + 错误码 TIMEOUT + 全额退款 + EXPIRED notify（audio 模态无 Worker 脚本，平台任务恒 QUEUED，零竞态确定性触发）
  - 对账零差异升级为 settle（SUCCEEDED）+ refund（EXPIRED）双路径闭环断言

## [0.1.0] - 2026-09-02

首个公开版本：通用模型能力网关（LLM 同步面 + 任务四模态面），任务面由 lotask4j 平台托管，全链路冒烟 18/18 通过。

### LLM 同步面

- 6 端点：`/v1/chat/completions`（同步+SSE 流式）、`/v1/messages`、`/v1/models`、`/v1/messages/count_tokens`、`/v1/embeddings`、`/v1/images/generations`
- RelayOrchestrator 中继管线：凭证验证 → 渠道路由 → 计费 saga（预扣-结算-退款）→ 内容审核 → 上游转发
- SSE 透传（帧重组 + 心跳）+ OpenAI/Anthropic 双协议转换（SSE 状态机）
- 横切层：链路追踪 / 限流 / 幂等（Idempotency-Key 拒绝式去重）/ 内容审核闸门
- 渠道健康上报（record-success/failure）、访问日志异步上报、THMP 契约面（HMAC 签名 + 影子比对 + 灰度切流，默认关闭）

### 任务面（face-task，M2.5）

- 四模态（video/image/audio/tts）create/poll；任务状态由 **lotask4j V4+ 平台托管**（网关无 DB，仅资源缓存盘）
- 计费 saga：全额预扣 → 终态 settle（成功）/ 全额 refund（失败，幂等防重）
- 终态 webhook 接收（HMAC 三头验签 + 双钥 grace + Event-Id 去重 + 无签名 verify-then-act 回查）
- 调用方 notify 回调（`X-THMP-Signature` + 1m/10m/1h 退避重试）
- 资源代理：`/v1/resources/{task_no}/{idx}?exp=&sig=` 免凭证直取，上游 URL 永不透传（fail-closed），缓存盘 write-through
- 超时钟（按模态 deadline 判 EXPIRED 退款）+ 对账兜底（双 job，Redis 可重建）
- 取消：submit 侧幂等 / 调用方 cancel / Worker 检测 CANCELLING 上报 CANCELLED

### 任务执行 Worker（task-worker）

- 独立进程独立扩缩：lotask4j worker API 拉单 / fencing（executionToken+version）回传 / lease 续约 / 取消检测
- Groovy 三钩子脚本（create/poll/resultMapping）+ SecureAST 沙箱（AST 黑名单 + 构造器检查 + 出网白名单 fail-closed + 钩子超时硬上限）
- 脚本真源在仓 `scripts/`，版本序热更；首两个上游适配：样例脚本 + token-mock（联调默认上游）
- lotask4j 对接鉴权：client_credentials 真登录 + Redis 共享 token（单租户单会话互斥适配 + 登录单飞锁 + 401 自愈）+ 写端点 HMAC 四头

### 装配与形态

- Maven 七模块：gateway-spi（SPI 冻结）/ gateway-core（共享基建）/ face-llm / face-task / task-worker / demo-control-plane / app
- `token-gateway.face = llm | task | all` 同 jar 异配置独立部署（`@ConditionalOnFace`，非法值启动 fail-fast）
- demo-control-plane：控制层能力面桩（联调用）+ 内存计费账本 + notify 回调靶

### 文档

- 中英双语 VitePress 文档站（GitHub Pages 自动部署）：用户文档六册（简介/快速开始/通用约定/LLM 面/任务面/FAQ）+ API 契约 yaml ×2 + 开发文档七册（设计方案/后端接入/安全契约/lotask4j 托管方案/任务面开发手册/租户开通 runbook/能力面契约）
- 全链路冒烟：`scripts/smoke.sh`（PASS/FAIL 矩阵，非零退出码可挂 CI）+ `docker-compose.smoke.yml`

### 安全纪律

- 凭证一律环境变量注入禁入仓；日志/管理面只出现掩码；签名比较恒定时间；调用方凭证不落日志；TokenContext toString 脱敏

### 已知限制

- 后端能力面 RPC 仍按 MMagiX 契约直连（M1 适配器化 `MmagixAdapter` + SPI 管线未完成，见《01_设计方案》分期路线）
- 任务面已验证 video 模态全链路；image/audio/tts 端点就绪但无真实上游脚本
