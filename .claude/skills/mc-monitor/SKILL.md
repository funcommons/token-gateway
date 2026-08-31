---
name: mc-monitor
description: 监控告警相关代码激活。覆盖 Metrics（Prometheus + Micrometer）、Tracing（OpenTelemetry / Jaeger / Tempo）、Logging（ELK / Loki）、Alerting（Alertmanager + 钉钉/企微/Slack）、SLO/SLI 错误预算、Grafana Dashboard。触发词：监控、告警、Metrics、Prometheus、Grafana、Micrometer、Tracing、OpenTelemetry、OTel、Jaeger、Tempo、SkyWalking、Logging、ELK、Loki、EFK、Alertmanager、SLO、SLI、SLA、错误预算、燃烧率、Dashboard、RUM、APM、Sentry。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: tooling
  tags: [monitoring, alerting, metrics, prometheus, grafana, micrometer, tracing, opentelemetry, jaeger, tempo, skywalking, logging, elk, loki, alertmanager, slo, sli, sla, error-budget, rum, apm, sentry]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - 监控告警规范 v1.0.md
  related-skills: [mc-java-spec, mc-perf, mc-api-spec]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "Prometheus 接入业务指标"
    - "Grafana Dashboard 怎么搭"
    - "OpenTelemetry 链路追踪怎么接"
    - "Jaeger 查 trace 慢在哪"
    - "ELK / Loki 日志收集怎么配"
    - "Alertmanager 告警怎么发钉钉"
    - "SLO 错误预算怎么算"
    - "燃烧率告警怎么写"
    - "前端异常上报 Sentry"
    - "RUM 真实用户监控"
---

# 监控告警规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 接入业务指标 | 场景一：Metrics |
| 搭 Grafana Dashboard | 场景二：Dashboard |
| 链路追踪 | 场景三：Tracing |
| 日志收集 | 场景四：Logging |
| 告警规则与通知 | 场景五：Alerting |
| 前端异常监控 | 场景六：Sentry + RUM |
| SLO / 错误预算 | 场景七：SLO |
| 业务事件埋点 | 场景八：自定义指标 |
| APM 选型 | 场景九：APM |
| 退出本规范 | 「退出 mc-monitor」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 后端栈 | Spring Boot Actuator + Micrometer + Prometheus + OpenTelemetry |
| 前端栈 | Sentry + web-vitals（RUM） |
| 收集栈 | Prometheus（指标）+ Loki（日志）+ Tempo / Jaeger（追踪） |
| 展示 | Grafana 统一 Dashboard |
| 告警 | Alertmanager → 钉钉 / 企微 / Slack / PagerDuty |
| **适用** | 生产环境的可观测性建设 |
| **不适用** | 性能优化手段（→ mc-perf）、缓存监控细节（→ mc-cache） |
| 退出 | 「退出 mc-monitor」 |

## 2. 全局铁律

1. **可观测三支柱**：Metrics（聚合数值）+ Tracing（链路）+ Logging（详情），缺一不可
2. **统一 trace_id**：贯穿三支柱（对齐 mc-api-spec v1.6 §4.1）
3. **RED 黄金指标**：Rate / Errors / Duration，每个接口必测
4. **USE 资源指标**：Utilization / Saturation / Errors，每个资源必测
5. **告警分级**：P0 电话（核心）/ P1 钉钉 / P2 Slack
6. **告警必须有 Runbook**：每条告警附文档链接
7. **SLO 错误预算**：超预算暂停新功能，优先修稳定
8. **告警去噪**：分组 + 抑制 + repeat_interval
9. **Dashboard 必含四视图**：业务 / 接口 / 资源 / 链路追踪入口
10. **生产日志必结构化**：JSON 输出（LogstashEncoder）

## 3. 场景判定

```
当前任务？
├── 接业务指标（QPS / 延迟 / 业务计数）   → 场景一：Metrics
├── 搭监控大盘                            → 场景二：Dashboard
├── 加链路追踪 / 查 trace                 → 场景三：Tracing
├── 日志收集 / 结构化 / 检索               → 场景四：Logging
├── 告警规则 / 通知渠道                    → 场景五：Alerting
├── 前端异常上报 / 性能监控                → 场景六：Sentry + RUM
├── 定义 SLO / 错误预算                    → 场景七：SLO
├── 业务事件埋点                           → 场景八：自定义指标
└── APM 选型                              → 场景九：APM
```

### 场景一：Metrics（Prometheus + Micrometer）

**自动暴露**：`/actuator/prometheus` 提供 JVM / Tomcat / HTTP 指标。

**RED 指标**（每接口必测）：QPS（Rate）、错误率（Errors）、P50/P95/P99 延迟（Duration）。

**业务指标 API**：`Counter`（累加）/ `Gauge`（瞬时）/ `Timer`（耗时分布）/ `DistributionSummary`（任意数值分布）。

**标签设计**：低基数（≤ 10 值）；**禁** user_id / order_id 等高基数。

**关键 PromQL**：QPS `sum(rate(http_requests_total[5m])) by (service)`、错误率 `errors / total`、P99 `histogram_quantile(0.99, ...)`。

**完整代码 + 标签规范 + 配置**：见 `./监控告警规范 v1.0.md` §2。

### 场景二：Grafana Dashboard

**必含四视图**：
- **业务**：订单数 / GMV / 在线用户 / 转化率
- **接口（RED）**：QPS / 错误率 / P50/P95/P99
- **资源（USE）**：CPU / 内存 / 磁盘 / 网络 / DB 连接池 / Redis
- **链路追踪入口**：链接 Tempo / Jaeger，传 trace_id 变量

**模板变量**：`$env` / `$service` / `$instance`。

**Dashboard as Code**：用 Grafana Operator 或 Provisioning；推荐 `grafonnet` (Jsonnet) 或 `grafana-foundation-sdk` (TS)。

**现成模板**：JVM (4701) / Spring Boot (11378) / PostgreSQL (9628) / Redis (11835)。

**详细规则**：见主规范 §3。

### 场景三：Tracing（OpenTelemetry）

**自动埋点**：HTTP 入口（MVC）/ HTTP 出口（RestTemplate / WebClient）/ DB（JDBC / MyBatis / HikariCP）/ 缓存（Lettuce / Redisson）/ MQ / RPC（gRPC / Dubbo）/ 异步。

**手动埋点**：`Tracer.spanBuilder("name").setAttribute(...).startSpan()` + try-with-resources + `recordException` + `setStatus(ERROR)`。

**Baggage**：跨服务传业务上下文（user.id / tenant.id）。

**采样**：生产 10-30% 概率采样；用 Collector tail_sampling 实现「错误全采、慢全采」。

**Tempo TraceQL**：`{ status = error }` / `{ duration > 1s }` / `{ service.name = "order-service" }`。

**详细规则 + 完整代码**：见主规范 §4。

### 场景四：Logging（Loki / ELK）

**结构化 JSON**：`LogstashEncoder` + `customFields: {app, env}` + MDC（trace_id 自动注入）。

**Loki 轻量**（推荐中小项目）：仅索引 metadata，全文检索弱。**ELK 全文检索强但重**（适合大规模）。

**LogQL 查询**：`{ app = "order-service", level = "ERROR" }` / `{ trace_id = "abc123" }` / `sum(rate(...[5m])) by (level)`。

**保留策略**：应用日志 30 天 / ERROR 90 天 / 审计日志 7 年（合规）。

**详细规则**：见主规范 §5 + mc-java-spec §6。

### 场景五：Alerting（Alertmanager）

**分级**：

| 级别 | 触发 | 通知 | 响应 |
|---|---|---|---|
| P0 | 核心不可用 / 资金异常 / 数据丢失 | 电话 + 短信 + 钉钉 | < 5 分钟 |
| P1 | 错误率 > 1% / P99 翻倍 / CPU > 80% | 钉钉 | < 30 分钟 |
| P2 | 磁盘 > 80% / 连接池 > 70% | Slack | < 2 小时 |
| P3 | 业务波动 | 邮件 | 工作日 |

**规则**：`for: 2m` 持续触发 / `labels.severity` 分级 / `annotations.runbook` 必填。

**告警治理**：分组（group_by）/ 抑制（inhibit_rules）/ 重复（repeat_interval）/ 升级机制（P1 30min 无确认 → P0）。

**详细规则 + 钉钉 webhook 配置**：见主规范 §6。

### 场景六：Sentry + RUM（前端）

**Sentry 接入**：`@sentry/vue` + `BrowserTracingIntegration(router)` + `replayIntegration`。

**采样**：traces 10%、replays 普通 1%、replays 错误 100%。

**捕获**：`app.config.errorHandler` / `unhandledrejection` / 资源加载失败（`window.addEventListener('error', ..., true)`）。

**SourceMap**：`build.sourcemap: 'hidden'`（生成不引用）+ Sentry CLI 上传。

**RUM**：web-vitals 上报 LCP / INP / CLS / TTFB（详见 mc-perf §7.4）。

**详细规则**：见主规范 §7。

### 场景七：SLO 错误预算

**SLI 公式**：

```promql
# 可用性 SLI
1 - (sum(rate(http_requests_total{code=~"5.."}[28d])) / sum(rate(http_requests_total[28d])))

# 延迟 SLI
sum(rate(http_request_duration_seconds_bucket{le="0.5"}[28d])) / sum(rate(http_request_duration_seconds_count[28d]))
```

**错误预算**：可用性 99.9% = 每月允许 43.2 分钟不可用。

**燃烧率告警**：
- 1 小时烧 2% 预算 → P1（`...[1h] / (1-SLO) > 14.4` + `...[5m]`）
- 6 小时烧 5% 预算 → P2

**预算驱动开发**：剩余 > 50% 推新功能；< 20% 暂停新功能；耗尽 全力修稳定。

**详细规则**：见主规范 §8 + mc-perf §11。

### 场景八：自定义业务指标

**原则**：少而精，每个指标都进 Dashboard + 告警。

**推荐埋点**：

| 业务 | 指标 | 类型 |
|---|---|---|
| 订单创建 | `orders_created_total{type,channel}` | Counter |
| 在线用户数 | `users_online` | Gauge |
| 订单金额分布 | `order_amount_distribution` | Summary |
| 库存预警 | `inventory_low_alert{sku}` | Counter |
| 任务积压 | `jobs_pending_count{queue}` | Gauge |
| Kafka lag | `kafka_consumer_lag{topic}` | Gauge |

**事件埋点**：`@EventListener` 监听领域事件 → `meterRegistry.counter(...).increment()` + 结构化日志（addKeyValue）。

**详细规则**：见主规范 §9。

### 场景九：APM 选型

| 方案 | 适用 |
|---|---|
| **OpenTelemetry + Tempo / Jaeger**（推荐） | 云原生、多后端 |
| SkyWalking | 国内企业、一站式 |
| Pinpoint | Java 单体、无侵入 |
| 自研 | 超大规模 |
| Datadog / New Relic | 商业、中小公司 |

**推荐组合（开源全栈）**：Spring Boot Actuator + Micrometer + Prometheus + OpenTelemetry + Tempo + Loki + Sentry + Grafana + Alertmanager。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./监控告警规范 v1.0.md` | 详细规则 + 完整配置 | v1.0 |
| `../mc-java-spec/Java SpringBoot 后端开发规范 v1.2.md` §6 | 日志规范（结构化 / 脱敏 / MDC trace_id） | v1.2 |
| `../mc-perf/SKILL.md` §10-§11 | 压测 + SLO 基础 | v1.0 |
| `../mc-api-spec/API 响应结构与错误码规范 v1.6.md` §4 | trace_id 双通道 | v1.6 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 日志规范（结构化 / MDC / 脱敏） | mc-java-spec §6 |
| TraceContext 实现（trace_id 注入链路） | mc-java-spec §6.6 |
| 性能指标 + SLO 目标 | mc-perf §11 |
| 缓存监控（命中率 / 错误） | mc-cache 场景九 |
| 测试（异常注入验证告警） | mc-test 场景二 |
| 前端异常上报脱敏 | mc-web-security §10.2 |

**可观测三支柱责任划分**：

| 层 | 职责 | 工具 |
|---|---|---|
| **Metrics** | 聚合数值（"发生了什么"） | Prometheus + Micrometer |
| **Tracing** | 链路追踪（"在哪儿发生"） | OpenTelemetry + Tempo |
| **Logging** | 详情（"具体内容"） | Loki / ELK + LogstashEncoder |
| **统一** | trace_id 贯穿三支柱 | MDC + W3C TraceContext |
