# 更新日志

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
