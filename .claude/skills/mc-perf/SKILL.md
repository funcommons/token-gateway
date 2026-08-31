---
name: mc-perf
description: 性能优化与压测相关代码激活。覆盖后端 JVM/GC/慢 SQL/连接池/N+1、前端 Bundle/Web Vitals/懒加载、DB 索引/EXPLAIN/大表分页、网络 CDN/HTTP2/压缩、压测 JMeter/k6/Gatling。触发词：性能、压测、慢查询、N+1、JVM 调优、GC、Lighthouse、Web Vitals、LCP、bundle、懒加载、SSR、索引、EXPLAIN、大表、CDN、缓存、JMeter、k6、Gatling、性能瓶颈、P99。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: tooling
  tags: [performance, jvm, gc, slow-sql, n-plus-1, lighthouse, web-vitals, lcp, bundle, lazy-load, index, explain, cdn, jmeter, k6, gatling]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - 性能规范 v1.0.md
  related-skills: [mc-java-spec, mc-webui-spec, mc-database-spec, mc-cache, mc-monitor]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "接口响应慢怎么排查"
    - "JVM 频繁 GC 怎么调优"
    - "首页加载 5 秒怎么优化"
    - "LCP 不达标怎么办"
    - "Bundle 太大怎么瘦身"
    - "慢 SQL 怎么定位和优化"
    - "N+1 查询怎么改"
    - "大表深分页太慢"
    - "压测怎么设计场景和指标"
    - "P99 100ms 怎么保证"
---

# 性能规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 后端接口慢排查 | 场景一：接口性能 |
| JVM / GC 调优 | 场景二：JVM |
| 慢 SQL 定位 / 索引设计 | 场景三：SQL 性能 |
| N+1 检测与修复 | 场景四：N+1 |
| 大表分页优化 | 场景五：深分页 |
| 前端首屏慢 / Web Vitals | 场景六：前端性能 |
| Bundle 瘦身 / 懒加载 | 场景七：Bundle |
| CDN / 压缩 / 网络 | 场景八：网络 |
| 压测设计与执行 | 场景九：压测 |
| 性能 SLA 制定 | 场景十：SLA |
| 退出本规范 | 「退出 mc-perf」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 后端栈 | JVM 17 + Arthas + Druid 慢 SQL + JProfiler |
| 前端栈 | Lighthouse + Web Vitals + Vite Bundle Analyzer |
| DB 栈 | PostgreSQL EXPLAIN + 索引设计 |
| 压测栈 | k6（默认）/ JMeter / Gatling |
| **适用** | 性能优化、压测、瓶颈排查、SLA 制定 |
| **不适用** | 安全（→ mc-java-security）、缓存细节（→ mc-cache）、监控（→ mc-monitor） |
| 退出 | 「退出 mc-perf」 |

## 2. 全局铁律

1. **性能 SLA 优先**：核心接口 P99 ≤ 200ms / P999 ≤ 1s；非核心 P99 ≤ 1s
2. **必须先量化再优化**：「过早优化是万恶之源」；先压测定位瓶颈
3. **二八原则**：80% 性能问题来自 20% 代码，优化前先找热点
4. **慢 SQL 阈值**：生产 2000ms 告警；超 1s 必须优化
5. **N+1 零容忍**：循环内查 DB 必须改批量
6. **深分页禁 OFFSET**：> 1 万行必须改 Cursor/Keyset
7. **前端 Bundle ≤ 500KB（首屏）**：超限必须拆分懒加载
8. **LCP ≤ 2.5s / FID ≤ 100ms / CLS ≤ 0.1**（Core Web Vitals）
9. **缓存优先于代码优化**：能缓存就缓存（详见 mc-cache）
10. **压测必须在类生产环境跑**：开发环境数据量不够，结论不可信

## 3. 场景判定

```
当前任务？
├── 后端接口慢                        → 场景一：接口性能
├── JVM 频繁 GC / 内存问题            → 场景二：JVM
├── SQL 慢 / 索引                     → 场景三：SQL 性能
├── 怀疑 N+1                          → 场景四：N+1
├── 深分页卡                          → 场景五：深分页
├── 前端首屏慢 / Lighthouse 低分      → 场景六：前端性能
├── Bundle 大                         → 场景七：Bundle
├── 静态资源加载慢                    → 场景八：网络
├── 压测准备 / 执行                   → 场景九：压测
└── 制定性能目标                      → 场景十：SLA
```

### 场景一：接口性能排查

**5 步定位法**：① 网关日志看总耗时 → ② APM/trace 看分段（Controller → Service → DB → RPC）→ ③ 数据库慢 SQL → ④ 外部依赖（Redis/RPC） → ⑤ JVM GC。

**工具**：Arthas（在线诊断）/ SkyWalking / OTel Trace / Druid 监控。

**常见瓶颈**：DB 慢 SQL（60%）/ N+1（20%）/ 远程 RPC 慢（10%）/ GC（5%）/ 序列化（5%）。

**详细排查清单**：见 `./性能规范 v1.0.md` §2。

### 场景二：JVM 调优

**关键参数**：`-Xms` = `-Xmx`（避免动态扩容）/ `-XX:+UseG1GC`（JDK 17 推荐）/ `-XX:MaxGCPauseMillis=200` / `-XX:+HeapDumpOnOutOfMemoryError`。

**GC 日志**：`-Xlog:gc*:file=/var/log/gc.log:time,uptime:filecount=10,filesize=50m`。

**工具**：jstat / jcmd / Arthas `dashboard` / GCEasy（在线分析）。

**GC 调优目标**：GC 占比 < 5% / Full GC 频率 < 1 次/天 / 停顿 < 200ms。

**完整配置 + 诊断**：见主规范 §3。

### 场景三：SQL 性能

**EXPLAIN 速读**：优先 Index Scan / Index Only Scan；警惕 Seq Scan on 大表 / Bitmap Heap Scan with high cost。

**索引设计原则**：
- WHERE 高频列建索引；JOIN 关联列必须索引
- 复合索引：等值列在前、范围列在后、排序列最后
- 覆盖索引：`INCLUDE (col1, col2)` 避免 heap fetch
- 部分索引：`WHERE is_deleted = 0` 减小索引体积
- 禁全模糊 `LIKE '%xx%'`；可前缀 `LIKE 'xx%'`

**慢 SQL 处理流程**：Druid 慢日志（> 2000ms） → EXPLAIN → 加索引或改写 → 压测验证 → 上线。

**详细规则**：见主规范 §4。

### 场景四：N+1 检测与修复

**检测**：Druid 监控看同一时段同类 SQL 次数；MyBatis Plus `Options` 配 `default-batch-fetch-size`；单元测试加 `assertThatSqlExecutions().hasSize(1)`。

**修复模式**：

| 场景 | 方案 |
|---|---|
| 列表关联用户名 | 批量 `selectByIds(userIds)` → 转 Map |
| 子对象列表 | MyBatis `resultMap` + `collection` 一次性 JOIN 或 子查询 |
| 懒加载触发 | 改 `@Data` 删除 + `@TableField(select = false)` 显式 fetch |

**完整示例**：见主规范 §5。

### 场景五：深分页

**禁用**：`OFFSET > 10000` 的查询。

**替代方案**（按 v1.6 §6 三种分页）：

| 场景 | 方案 |
|---|---|
| 后台管理（跳页） | OFFSET 限制 ≤ 1 万，超量提示用搜索 |
| 移动端无限滚动 | **Cursor 分页**（next_cursor） |
| 高性能列表 | **Keyset 分页**（last_id + last_value） |

**Keyset 模板**：`WHERE (created_at, id) < (last_value, last_id) ORDER BY created_at DESC, id DESC LIMIT 20`。

**详细规则**：见主规范 §6。

### 场景六：前端性能

**Core Web Vitals**：

| 指标 | 阈值 | 含义 |
|---|---|---|
| LCP（Largest Contentful Paint） | ≤ 2.5s | 最大内容渲染时间 |
| FID（First Input Delay）/ INP | ≤ 100ms / 200ms | 首次输入延迟 / 交互延迟 |
| CLS（Cumulative Layout Shift） | ≤ 0.1 | 累积布局偏移 |
| TTFB（Time To First Byte） | ≤ 800ms | 首字节时间 |
| FCP（First Contentful Paint） | ≤ 1.8s | 首次内容绘制 |

**Lighthouse 目标**：Performance ≥ 90。

**工具**：Lighthouse CI / WebPageTest / Chrome DevTools Performance。

**优化清单**：图片懒加载 + WebP/AVIF、路由懒加载、关键 CSS 内联、预连接 (preconnect)、避免布局抖动。

**详细规则 + 完整清单**：见主规范 §7。

### 场景七：Bundle 瘦身

**目标**：首屏 JS ≤ 200KB gzip；总 JS ≤ 1MB。

**工具**：`vite build --mode analyze` / `rollup-plugin-visualizer`。

**策略**：

| 策略 | 收益 |
|---|---|
| 路由懒加载 | 首屏减 30~50% |
| 第三方库按需引入 | Element Plus 按需减 60% |
| Tree Shaking | 删未用代码 |
| 代码分割（vendor / common） | 缓存命中率提升 |
| 替换重库 | moment → dayjs / lodash → lodash-es |
| 移除 SourceMap（生产） | 减 30% |

**详细规则**：见主规范 §8。

### 场景八：网络与 CDN

**HTTP/2 +**：启用多路复用、头部压缩。

**压缩**：Brotli（比 Gzip 小 15-20%）优先；文本资源必压缩。

**CDN 边缘缓存**：静态资源（JS/CSS/图片/字体）走 CDN；版本号哈希文件名 + 长缓存（`Cache-Control: public, max-age=31536000, immutable`）。

**预连接**：`<link rel="preconnect" href="https://api.example.com">` 减少首请求 RTT。

**图片优化**：WebP/AVIF 替代 JPG/PNG（小 30-50%）；响应式 `srcset`；`loading="lazy"` 懒加载。

**详细规则**：见主规范 §9。

### 场景九：压测

**工具选型**：k6（默认，Go 写脚本，性能好）/ JMeter（GUI 适合 QA）/ Gatling（Scala DSL，报告强大）。

**场景设计**：

| 类型 | 场景 | 时长 |
|---|---|---|
| 容量压测 | 找系统能扛的最大 QPS | 5-10min |
| 极限压测 | 持续高 QPS 看是否崩溃 | 30-60min |
| 稳定性压测 | 80% 容量跑 8h | 8h+ |
| 突发流量压测 | 瞬时 10x QPS 看反应 | 1-2min |

**核心指标**：QPS / P50 / P95 / P99 / P999 / 错误率 / 资源使用率（CPU/内存/IO/网络）。

**k6 模板**：见主规范 §10。

### 场景十：性能 SLA

| 业务类型 | P99 | 可用性 | 备注 |
|---|---|---|---|
| 核心交易（下单/支付） | ≤ 200ms | 99.95% | 资金相关 |
| 用户主流程（登录/查询） | ≤ 500ms | 99.9% | |
| 后台管理（列表/报表） | ≤ 1s | 99.5% | |
| 异步任务 | 不限 | 99% | |
| 静态资源 | ≤ 100ms | 99.9% | CDN |

**SLO 错误预算**：可用性 99.9% = 每月允许 43 分钟不可用；超预算暂停新功能上线，优先修稳定。

**详细规则**：见主规范 §11。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./性能规范 v1.0.md` | 详细规则 + 完整代码模板 | v1.0 |
| `../mc-java-spec/Java SpringBoot 后端开发规范 v1.2.md` §9 | 后端基础性能规范（N+1/批量/连接池） | v1.2 |
| `../mc-database-spec/SKILL.md` 场景二 | SQL 性能（索引/EXPLAIN） | v1.1 |
| `../mc-api-spec/API 响应结构与错误码规范 v1.6.md` §6 | 分页性能（Cursor/Keyset） | v1.6 |
| `../mc-cache/SKILL.md` | 缓存策略（性能优化的关键手段） | v1.0 |
| `../mc-monitor/SKILL.md` | 性能监控（Prometheus/SLO） | v1.0 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 后端性能优化落地代码 | mc-java-spec §9 |
| 数据库 SQL 优化 | mc-database-spec 场景二 |
| 分页性能（Cursor / Keyset） | mc-api-spec v1.6 §6 |
| 缓存策略（性能首要手段） | mc-cache |
| 性能监控、SLO、告警 | mc-monitor |
| 前端基础（构建配置） | mc-webui-spec |
