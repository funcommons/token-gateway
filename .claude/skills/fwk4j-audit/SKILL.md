---
name: fwk4j-audit
description: framework4j 审计日志（@Auditable AOP + Hash Chain 防篡改 + AuditSink 扩展 + actor/ip 安全责任）。触发词：@Auditable、审计日志、Hash Chain、防篡改、append-only、AuditSink、审计、操作日志、targetIdSpel、actor、审计投毒。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-security
  tags: [audit, hash-chain, aop]
  language: zh-CN
  artifactId: framework4j-audit
  config-prefix: framework4j.audit
  examples:
    - "关键操作要审计"               # → @Auditable
    - "审计日志防篡改"               # → Hash Chain SHA-256
    - "审计记录写到 DB"              # → 实现 AuditSink
    - "审计记录谁操作的"             # → X-User-Id（网关必须覆写）
---

# framework4j-audit 审计日志

## 注解

```java
@Auditable(action = "DELETE_ORDER", targetType = "order", targetIdSpel = "#orderId")
public void deleteOrder(String orderId) { ... }

@Auditable(action = "CREATE_ORDER", targetType = "order", targetIdSpel = "#req.id", logArgs = true)
public Order createOrder(@RequestBody CreateOrderRequest req) { ... }
```

## Hash Chain 防篡改

```
hash = SHA256(prev_hash || TreeMap(action, targetType, targetId, actor, result, timestamp, args))
```

- 篡改任一条记录 → 后续 hash 校验失败
- `computeNextSnapshot` 原子返回 (prevHash, hash)
- sink 失败 CAS 回滚

## AuditSink 扩展（生产用）

```java
@Component
public class DbAuditSink implements AuditSink {
    @Override
    public void write(AuditRecord record) {
        auditMapper.insert(record);  // append-only
    }
    @Override
    public String loadLastHash() { return auditMapper.selectLastHash(); }
}
```

## 安全责任

> `actor` 取自 `X-User-Id`、`ip` 取自 `X-Forwarded-For`
> **必须由网关在入口覆写后才可信**

## 配置

```yaml
framework4j:
  audit:
    enabled: true
    hash-chain-enabled: true
    hash-algorithm: SHA-256
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-audit</artifactId>
    <version>v1.1.1</version>
</dependency>
```
