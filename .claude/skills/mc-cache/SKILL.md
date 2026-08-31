---
name: mc-cache
description: 缓存设计与实现相关代码激活。覆盖多级缓存（Caffeine + Redis）、缓存策略（Cache-Aside / Write-Through / Write-Behind）、一致性（双写 / 延迟双删 / 最终一致）、三大经典问题（穿透 / 击穿 / 雪崩）、Redis 数据结构选型、分布式锁（Redisson）、限流（Lua）。触发词：缓存、Redis、Redisson、Caffeine、本地缓存、多级缓存、Cache-Aside、Write-Through、缓存一致性、延迟双删、缓存穿透、缓存击穿、缓存雪崩、布隆过滤器、热点 key、分布式锁、缓存预热、缓存淘汰、Spring Cache。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: backend
  tags: [caching, redis, redisson, caffeine, multi-level, cache-aside, write-through, consistency, cache-penetration, cache-breakdown, cache-avalanche, bloom-filter, hotkey, distributed-lock, ratelimit, spring-cache, lua]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - 缓存规范 v1.0.md
  related-skills: [mc-java-spec, mc-database-spec, mc-perf, mc-monitor]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "Redis 缓存怎么设计"
    - "多级缓存（本地 + Redis）怎么搭"
    - "缓存和数据库一致性怎么保证"
    - "缓存穿透怎么防"
    - "缓存击穿怎么防"
    - "缓存雪崩怎么防"
    - "热点 key 怎么处理"
    - "分布式锁怎么实现"
    - "缓存淘汰策略选什么"
    - "Spring Cache 注解怎么用"
---

# 缓存规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 设计 Redis 缓存 | 场景一：基础缓存 |
| 多级缓存（Caffeine + Redis） | 场景二：多级缓存 |
| Spring Cache 注解 | 场景三：声明式 |
| 缓存一致性 | 场景四：一致性策略 |
| 穿透 / 击穿 / 雪崩 | 场景五：三大问题 |
| 热点 key | 场景六：热点处理 |
| 分布式锁（Redisson） | 场景七：分布式锁 |
| 限流（Redis + Lua） | 场景八：限流 |
| 缓存监控 | 场景九：监控 |
| 退出本规范 | 「退出 mc-cache」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 客户端 | Redisson（推荐）/ Spring RedisTemplate / Lettuce |
| 本地缓存 | Caffeine |
| 注解 | Spring Cache（@Cacheable / @CacheEvict / @CachePut） |
| **适用** | 缓存设计 / 一致性 / 经典问题 / 分布式锁 / 限流 |
| **不适用** | Redis 安装运维（→ mc-deploy）、性能压测（→ mc-perf） |
| 退出 | 「退出 mc-cache」 |

## 2. 全局铁律

1. **先有 DB 再有缓存**：缓存是优化手段，不是数据源；系统必须能脱离缓存工作
2. **必须设 TTL**：所有 Key 必须过期；上限 ≤ 7 天
3. **Key 命名**：`[业务]:[子模块]:[版本]:[ID]`，如 `order:detail:v1:12345`
4. **避免大 Key**：单 Value ≤ 10KB；集合 ≤ 1 万元素；超量拆分
5. **避免热 Key**：QPS > 1 万/Key 必须分片 + 本地缓存兜底
6. **写缓存必须考虑失败**：失败不能阻塞业务（catch + log + 降级）
7. **禁用 `KEYS *` / `FLUSHALL`**：用 `SCAN` / `UNLINK`
8. **数据一致性容忍**：最终一致（延迟双删 + 补偿），强一致走 DB
9. **三大问题必防**：穿透（布隆过滤器/空对象）/ 击穿（互斥锁）/ 雪崩（随机 TTL）
10. **缓存监控必备**：命中率 / 内存 / QPS / 慢命令（详见 mc-monitor）

## 3. 场景判定

```
当前任务？
├── 实现 Redis 缓存                   → 场景一：基础
├── 多级缓存（本地 + Redis）           → 场景二：多级
├── 用 Spring Cache 注解               → 场景三：声明式
├── 缓存与 DB 一致性                   → 场景四：一致性
├── 穿透 / 击穿 / 雪崩                 → 场景五：三大问题
├── 热点 key 高 QPS                    → 场景六：热点
├── 分布式锁                           → 场景七：分布式锁
├── 限流（Redis + Lua）                → 场景八：限流
└── 缓存监控                           → 场景九：监控
```

### 场景一：基础缓存（Cache-Aside）

**经典模式**：读 → L1（Redis）miss → L2（DB） → 回填；写 → 写 DB → 删 Cache（不要更新 Cache）。

**TTL 策略**：热数据 5-30min / 中频 30min-2h / 字典 1-7day / 空对象 30s-5min（防穿透）/ 锁 30s。**防雪崩**：基础 TTL ± 10% 抖动。

**Key 命名**：`[业务]:[子模块]:[版本]:[实体ID]`；禁超长 / 含特殊字符 / entity JSON 当 key。

**序列化**：Jackson 统一（mc-java-spec §4.6），禁 JDK 序列化。

**详细代码模板 + Key 设计 + TTL 策略**：见 `./缓存规范 v1.0.md` §2。

### 场景二：多级缓存（Caffeine + Redis）

**适用**：超高 QPS（> 1 万）/ 数据稳定 / 单机一致性可容忍。

**三级查询**：L1（Caffeine）→ L2（Redis）→ L3（DB）；每级 miss 回填下一级。

**L1 配置**：`maximumSize(10_000)` + `expireAfterWrite(5min)` + `recordStats()`。

**多机同步**：Redis Pub/Sub 或 Kafka 广播失效（各实例本地缓存同步 invalidate）。

**一致性窗口**：L1 接受短暂不一致（5-30min 内）；敏感业务可减小 TTL 或重大变更前等几秒。

**详细规则 + 完整代码**：见主规范 §3。

### 场景三：Spring Cache 注解

**适合**：简单 CRUD 场景，零侵入。

**注解**：
- `@Cacheable(value, key, unless)`：缓存查询结果（unless 跳过 null）
- `@CachePut(value, key)`：更新并写入
- `@CacheEvict(value, key, allEntries)`：删除（单个或全部）
- `@Caching`：复合操作

**配置**：`spring.cache.type=redis` + `time-to-live=30m` + `cache-null-values=false`。

**局限**：不灵活（key/TTL 固化注解）/ 难延迟双删 / 难多级 / 不易监控。**推荐**：复杂业务走场景一/二。

**详细规则**：见主规范 §3 + 主规范 §2.4 序列化。

### 场景四：一致性策略

| 策略 | 一致性 | 适用 |
|---|---|---|
| **Cache-Aside**（旁路） | 最终（弱） | 通用默认 |
| **延迟双删** | 接近强 | 强一致要求 |
| **Write-Through** | 强 | 写多读少 |
| **Write-Behind** | 最终（弱） | 写超高频 |
| **Binlog 订阅** | 最终（弱） | 强一致但解耦 |

**Cache-Aside 关键**：写 DB 后**删除** Cache（不要更新，避免并发顺序错乱）。

**延迟双删**：① 删 Cache → ② 写 DB → ③ 异步延迟 500ms-1s 再删（防并发读脏数据回填）。

**Binlog 订阅**：DB binlog → Canal → MQ → 消费 → 删 Cache（强一致、解耦）。

**实战陷阱**：禁「先删 Cache 再写 DB」/ 删 Cache 失败要降级不阻塞 / 事务内删 Cache 要放 `afterCommit`。

**详细规则 + 完整代码**：见主规范 §4。

### 场景五：三大经典问题

| 问题 | 现象 | 解决 |
|---|---|---|
| **穿透**（Penetration） | 查不存在的 key，每次穿透 DB | ① 缓存空对象（短 TTL）② 布隆过滤器 ③ 接口层校验 |
| **击穿**（Breakdown） | 热点 key 过期瞬间，海量请求打 DB | ① 互斥锁（Redisson）② 物理永不过期 + 异步刷新 |
| **雪崩**（Avalanche） | 大量 key 同时过期 | ① 随机 TTL（基础 ± 10%）② 多级缓存兜底 ③ 限流降级 |

**布隆过滤器**：`redissonClient.getBloomFilter(...)` + `tryInit(1_000_000, 0.01)` + 预热 + 新增数据 add。

**互斥锁防击穿**：`tryLock(0, 10, SECONDS)` + 双重检查 + miss 时回填。

**详细规则 + 完整代码**：见主规范 §5。

### 场景六：热点 Key

**识别**：`redis-cli --hotkeys`（需 LFU）/ Prometheus QPS 监控 / 业务预判（活动商品）。

**处理策略**：
- **本地缓存兜底**：Caffeine 缓存热 key（场景二）
- **多副本 Key**：`key:1` / `key:2` / `key:3` 随机访问分散
- **预热**：活动前定时任务预加载
- **降级**：接口层限流 / 静态化（CDN）

**详细规则 + 多副本示例 + 预热脚本**：见主规范 §6。

### 场景七：分布式锁（Redisson）

**标准模板**（详见 mc-java-spec §4.5）：`tryLock(waitTime, leaseTime, unit)` + `finally unlock` + `isHeldByCurrentThread` + WatchDog 自动续期。

**关键约束**：必配 waitTime/leaseTime / 业务时间 < leaseTime / 3 / 必配 finally unlock。

**进阶**：公平锁（`getFairLock`）/ 读写锁（`getReadWriteLock`）/ 联锁（`RedissonMultiLock`）。

**禁用**：自实现 `SETNX + EXPIRE`（非原子）/ `DEL` 误删（用 Lua 或 Redisson）。

**详细规则**：见主规范 §7。

### 场景八：限流（Redis + Lua）

**算法对比**：固定窗口（临界突刺）/ 滑动窗口（平滑）/ 令牌桶（允许突发）/ 漏桶（严格速率）。

**滑动窗口 Lua**：ZSET + `zremrangebyscore` 清窗口外 + `zcard` 计数 + `zadd` 添加。

**Redisson RRateLimiter**：`trySetRate(OVER_ALL, 100, 1, SECONDS)` + `tryAcquire(1)`。

**多维度**：IP（100/min）/ 用户（30/min）/ 全局（1000/s）。

**错误码**：10500 RATE_LIMITED（详见 mc-api-spec v1.6 §7.9）+ `Retry-After` Header。

**详细规则 + 完整 Lua 脚本**：见主规范 §8。

### 场景九：缓存监控

**核心指标**：命中率 > 90% / 内存 < 80% / 慢命令 < 1/min / 连接数 < max*70% / 大 Key 数 = 0。

**Prometheus**（Redis Exporter）：命中率 `redis_keyspace_hits / (hits + misses)` / 内存 `redis_memory_used / max` / 慢命令 `rate(redis_slowlog_length[1m])`。

**应用层**：`cacheMetrics.withMetrics("cache", key, supplier)` 包装，自动记录 hit/miss/error。

**告警**：命中率 < 80% / 内存 > 90% / 慢命令 > 10/min。

**Grafana 模板**：11835（Redis Dashboard）。

**详细规则**：见主规范 §9 + mc-monitor。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./缓存规范 v1.0.md` | 详细规则 + 完整代码 | v1.0 |
| `../mc-java-spec/Java SpringBoot 后端开发规范 v1.2.md` §4.5 | Redisson 基础（分布式锁模板） | v1.2 |
| `../mc-database-spec/SKILL.md` | DB 字段类型（避免缓存反序列化冲突） | v1.1 |
| `../mc-perf/SKILL.md` | 性能优化与压测 | v1.0 |
| `../mc-monitor/SKILL.md` 场景一 | 缓存监控指标（命中率 / 内存 / 慢命令） | v1.0 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| Redisson 客户端使用 | mc-java-spec §4.5 |
| 序列化（Jackson）影响缓存 Key | mc-java-spec §4.6 |
| DB 字段类型与缓存对齐 | mc-database-spec 场景一 |
| 缓存监控（命中率 / 内存） | mc-monitor 场景一 |
| 缓存性能压测 | mc-perf 场景九 |
| 限流错误码（10500） | mc-api-spec v1.6 §7.9 |

**缓存责任划分**：

| 层 | 关注 |
|---|---|
| **业务** | 一致性策略、TTL、Key 命名 |
| **客户端** | Redisson / Caffeine 使用 |
| **服务** | Redis 集群部署、主从、哨兵（详见 mc-deploy） |
| **监控** | 命中率、内存、慢命令（详见 mc-monitor） |
