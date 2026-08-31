# framework4j-audit

> 审计日志 SDK：`@Auditable` AOP + Hash Chain 防篡改（mc-java-security 铁律 10）

## 简介

企业合规刚需：关键操作（登录/改密/角色变更/删除/资金操作/数据导出）必须审计，且审计表不可篡改。

## 核心能力

| 能力 | 实现 |
|---|---|
| **注解驱动** | `@Auditable(action, targetType, targetIdSpel)` + AOP |
| **完整上下文** | actor / ip / userAgent / trace_id / 入参 / 返回值 |
| **防篡改** | Hash Chain：每条 hash = `SHA256(prev_hash \|\| content)` |
| **异常也审计** | `logOnError=true`（默认），result=ERROR + errorMessage |
| **异步写入** | `AuditSink` 抽象，业务方实现（DB/Kafka/ELK） |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-audit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
framework4j:
  audit:
    enabled: true
    hash-chain-enabled: true
    hash-algorithm: SHA-256       # 或 SHA-512
    actor-header: X-User-Id
    actor-fallback: anonymous
    ip-header: X-Forwarded-For
    table-name: audit_log
```

### 3. 注解使用

```java
@Service
public class OrderService {

    @Auditable(action = "DELETE_ORDER", targetType = "order", targetIdSpel = "#orderId")
    public void deleteOrder(String orderId) {
        orderMapper.deleteById(orderId);
    }

    @Auditable(action = "UPDATE_ROLE",
               targetType = "user_role",
               targetIdSpel = "#userId",
               logResult = true)  // 记录返回值
    public Role updateRole(String userId, String newRole) {
        return roleService.update(userId, newRole);
    }

    @Auditable(action = "BATCH_EXPORT",
               targetType = "report",
               logArgs = false)  // 不记录入参（避免大数据）
    public byte[] export(String query) {
        return reportService.export(query);
    }
}
```

### 4. 自定义 AuditSink（生产环境）

```java
@Component
public class DbAuditSink implements AuditSink {

    @Autowired
    private AuditLogMapper mapper;

    @Override
    public void write(AuditRecord record) {
        AuditLogDo log = new AuditLogDo();
        log.setAction(record.getAction());
        log.setActor(record.getActor());
        log.setHash(record.getHash());
        log.setPrevHash(record.getPrevHash());
        // ... 其他字段
        mapper.insert(log);  // append-only（DB 用户权限 REVOKE UPDATE/DELETE）
    }

    @Override
    public String loadLastHash() {
        return mapper.selectLastHash();  // 启动时加载，用于初始化 HashChain
    }
}
```

## Hash Chain 防篡改

每条审计记录的 hash：
```
hash = SHA256(prev_hash || action || targetType || targetId || actor || result || timestamp || args)
```

篡改任一条记录 → 后续所有 hash 校验失败 → 检测到篡改。

**表设计要求**（mc-database-spec）：
```sql
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id VARCHAR(64),
    actor VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,       -- SUCCESS / ERROR
    error_message TEXT,
    args_json JSONB,
    result_json JSONB,
    ip VARCHAR(64),
    user_agent VARCHAR(256),
    trace_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    prev_hash CHAR(64) NOT NULL,        -- 上一条 hash
    hash CHAR(64) NOT NULL              -- 本条 hash
);

-- append-only：DB 用户权限 REVOKE UPDATE, DELETE ON audit_log
```

## 自动装配

- `AuditAutoConfiguration` 注册 `AuditService` + `AuditAspect` + `HashChainService`
- **v2.2 自动选 sink**：
  - 检测到 `DataSource` bean → `JdbcAuditSink`（append-only INSERT 到 `audit.table-name`）
  - 未检测到 → `InMemoryAuditSink` fallback（开发/测试，重启即丢）
- 业务方无需任何代码改动 — 配 DataSource + `audit.table-name` 即生效
- 通过 `framework4j.audit.enabled=false` 关闭

### 生产 DDL（PostgreSQL）

```sql
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64),
    actor VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    error_message TEXT,
    args_json TEXT,
    result_json TEXT,
    ip VARCHAR(45),
    user_agent VARCHAR(255),
    trace_id VARCHAR(64),
    timestamp TIMESTAMPTZ NOT NULL,
    prev_hash VARCHAR(128) NOT NULL,
    hash VARCHAR(128) NOT NULL UNIQUE  -- 防同 hash 重复写
);
CREATE INDEX idx_audit_target ON audit_log (target_type, target_id);
CREATE INDEX idx_audit_actor_time ON audit_log (actor, timestamp DESC);
CREATE INDEX idx_audit_time ON audit_log (timestamp DESC);

-- append-only：DB 用户权限 REVOKE UPDATE, DELETE ON audit_log
```

## 相关文档

- `Java开发准则.md` 安全 P0 必查 6 项
- mc-java-security 铁律 10 关键操作入审计表
- mc-database-spec 审计日志表设计

## v2.1 功能增强

### 审计查询 API

```java
public interface AuditQueryService {
    // 分页查询审计记录
    AuditPage query(String actor, String action, Instant start, Instant end,
                    int page, int pageSize);

    // 校验 hash chain 完整性（指定时间范围）
    ChainVerifyResult verifyChain(Instant start, Instant end);
}
```

业务方实现 `AuditQueryService`（基于 DB / ES / Kafka），SDK 提供接口契约。

### 安全责任边界（重要）

> **`actor` 取自 `X-User-Id` Header、`ip` 取自 `X-Forwarded-For`**
>
> 这两个 Header **必须由网关在入口处剔除/覆写后才可信**。
> 消费者应用必须在网关层：
> 1. 不允许客户端传 `X-User-Id`
> 2. `X-Forwarded-For` 由网关重写为真实出口 IP
>
> 否则任意客户端可伪造审计 actor/IP 实施审计投毒。
