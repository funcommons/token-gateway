# 更新日志

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- **submit-task-type 非法值启动告警**（#13 补强，v0.5.0 后置）：`CapabilityValidator` 对 `task.submit-task-type` 非 `modality|model` 输出 warning（仍回退 modality 建单，不阻断）——防「意图配 model 拼错 → 静默回退 → 任务无人认领永 PENDING」；配套文档收口（05/06/07 开发手册 + 用户任务面接入手册，中英）与 app yml 配置示例注释
  - 测试 +1（validator 非法值告警 / 合法值不告警）；v0.5.0 tag 内测试 +2（粒度解析 / 编排器 model 粒度）

### 文档

- **LLM 面手册新增「Spring AI 客户端接入」**（中英）：Spring AI OpenAI/Anthropic starter 指网关 base-url 即接入，网关能力（路由定价/计费 saga/failover/审核/限流）对流经的 Spring AI 流量自动生效；含凭证语义、模型目录核对、错误信封处置、Idempotency-Key 注入与任务面边界注意事项

### 修复

- **billing 面三端点路径前缀可配**（issue #14，接 #12 同款机制）：`token-gateway.billing.path-prefix`（默认 `/api/v1/internal/billing` chat 契约不变），任务面接入方指 `/v1/internal/billing/task` 接自有计费变体；E2E 实测此前 task 计费请求误入 chat 端点被幂等吞掉
- **ScriptHttpClient 显式 Content-Type 覆盖语义**（issue #15）：原 `spec.header` 追加致 `application/json,application/json` 重复头，严格上游 400；`get/post/postMultipart/getBytes` 四路径一致，测试 +2
- **资源代理回源 URI 重载**（issue #16）：`uri(String)` 模板模式对预编码签名 URL（OSS `Signature=%2B...`）重复编码致上游 403，改 `URI.create` 绕过模板处理

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
