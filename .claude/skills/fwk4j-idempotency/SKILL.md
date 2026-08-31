---
name: fwk4j-idempotency
description: framework4j 幂等键防重复提交（Idempotency-Key UUID v4 + Redis Lua SETNX 48h + body hash 校验 + 回放缓存响应 + 失败重试）。触发词：Idempotency-Key、幂等、防重复提交、重复提交、body hash、回放、409 DUPLICATE_SUBMIT。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-traffic
  tags: [idempotency, redis, lua]
  language: zh-CN
  artifactId: framework4j-idempotency
  config-prefix: framework4j.idempotency
  examples:
    - "防止重复提交"                  # → Idempotency-Key header
    - "幂等键怎么用"                  # → 客户端 UUID v4
    - "重复提交返什么"                # → 409 DUPLICATE_SUBMIT
    - "Controller 异常后能重试吗"     # → 能（异常删 key）
---

# framework4j-idempotency 幂等键

## 使用方式

客户端写操作请求头加 `Idempotency-Key: <UUID v4>`，服务端自动防重。

## 行为

| 场景 | 结果 |
|---|---|
| 首次请求 | 放行 + 写入 PENDING |
| 同 key + 同 body + 已完成 | 回放缓存响应（不触 Controller） |
| 同 key + 不同 body | 409 DUPLICATE_SUBMIT |
| Controller 异常 | 删除 key（允许重试） |

## 配置

```yaml
framework4j:
  idempotency:
    enabled: true
    ttl-seconds: 172800  # 48h
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-idempotency</artifactId>
    <version>v1.1.1</version>
</dependency>
```
