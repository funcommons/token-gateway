← [返回 README](./README.md)

# Spring Boot 3 + Micrometer 全链路 SQL 追踪方案

## 1. 背景与目标

在微服务架构下,为了满足线上问题排查、慢 SQL 溯源和统一运维的需求,需要对应用发出的**所有 SQL**(包括查询和变更)进行标记。

### 核心需求

1. **全量覆盖**: 所有由应用发出的 SQL(SELECT, INSERT, UPDATE, DELETE 等)均需处理
2. **规范格式**: 必须通过框架自动添加注释,格式为 `/*traceid=xxx,topic=xxx*/ SQL...`
3. **包含信息**:
   - `traceid`: 当前链路追踪 ID
   - `topic`: 项目名称(应用名)
4. **正例参考**:
   ```sql
   /*traceid=abc123xyz,topic=MyProject*/ SELECT id, name FROM users WHERE id = ?;
   ```

---

## 2. 总体设计

本方案通过扩展 Alibaba Druid 的 `FilterEventAdapter` 实现 SQL 拦截与增强。

### 2.1 核心组件架构

```mermaid
graph TB
    subgraph "应用层"
        A[业务代码<br/>Mapper/Repository]
    end

    subgraph "ORM 框架层"
        B[MyBatis Plus / JPA]
    end

    subgraph "数据源层 - Druid"
        C[DruidDataSource]
        D[TraceIdDruidFilter<br/>SQL 拦截器]
    end

    subgraph "追踪上下文"
        E1[TraceIdProvider<br/>接口]
        E2[DefaultTraceIdProvider<br/>实现类]
        F1[MDC<br/>SLF4J]
        F2[Micrometer Tracer<br/>可选]
    end

    subgraph "配置层"
        G[SqlTracingProperties<br/>追踪配置]
        H[SqlTracingAutoConfiguration<br/>自动配置]
    end

    subgraph "数据库"
        I[(PostgreSQL/MySQL)]
    end

    A -->|SQL 执行| B
    B -->|JDBC 调用| C
    C -->|拦截| D
    D -->|获取 TraceID| E1
    E1 -.实现.-> E2
    E2 -->|读取| F1
    E2 -.可选.-> F2
    D -->|读取配置| G
    H -->|注册 Filter| C
    H -->|加载配置| G
    D -->|注入 SQL 注释| I

    style D fill:#ff6b00,stroke:#333,color:#fff
    style E2 fill:#0ea5e9,stroke:#333,color:#fff
    style G fill:#10b981,stroke:#333,color:#fff
```

### 2.2 SQL 处理流程图

```mermaid
sequenceDiagram
    participant App as 业务代码
    participant MB as MyBatis Plus
    participant Druid as DruidDataSource
    participant Filter as TraceIdDruidFilter
    participant Provider as TraceIdProvider
    participant MDC as SLF4J MDC
    participant DB as 数据库

    App->>MB: userMapper.selectById(1)
    MB->>Druid: connection.prepareStatement(sql)
    Druid->>Filter: 拦截 SQL

    Filter->>Filter: 检查追踪模式<br/>(DISABLED/WRITE_ONLY/ALL)

    alt 模式为 DISABLED
        Filter->>Druid: 返回原始 SQL
    else 模式为 WRITE_ONLY 且为 SELECT
        Filter->>Druid: 返回原始 SQL (跳过读操作)
    else 模式为 ALL 或 WRITE_ONLY 写操作
        Filter->>Filter: 检查是否已有注释<br/>(防重复注入)
        Filter->>Provider: getTraceId()
        Provider->>MDC: 按优先级查找<br/>traceId, trace_id, X-B3-TraceId...

        alt MDC 中找到 TraceID
            MDC-->>Provider: 返回 TraceID (abc123)
        else MDC 中未找到
            MDC-->>Provider: 返回 null
            Provider-->>Filter: 返回 "none"
        end

        Filter->>Filter: 拼接注释<br/>/*traceid=abc123,topic=MyApp*/ SQL
        Filter->>Druid: 返回处理后的 SQL
    end

    Druid->>DB: 执行 SQL
    DB-->>App: 返回结果
```

### 2.3 配置加载流程

```mermaid
flowchart TD
    Start([Spring Boot 启动]) --> A[SqlTracingAutoConfiguration<br/>初始化]
    A --> B{检测 Micrometer}
    B -->|存在| C[记录检测到 Micrometer]
    B -->|不存在| D[记录未检测到]
    C --> E[创建 DefaultTraceIdProvider]
    D --> E

    E --> F[BeanPostProcessor<br/>处理 Bean]
    F --> G{Bean 是否为<br/>DruidDataSource?}
    G -->|否| H[跳过]
    G -->|是| I[提取数据源名称<br/>如: default, business]

    I --> J[读取配置<br/>ldx2t.commons.datasource<br/>.datasources.{name}.sql-tracing]
    J --> K{配置是否存在?}
    K -->|否| L[跳过该数据源]
    K -->|是| M{mode 是否为 DISABLED?}
    M -->|是| N[跳过该数据源]
    M -->|否| O[创建 TraceIdDruidFilter]

    O --> P[设置参数:<br/>- TraceIdProvider<br/>- topic<br/>- mode]
    P --> Q[添加到 DruidDataSource<br/>的 proxyFilters]
    Q --> R[记录日志:<br/>SQL Tracing enabled]

    L --> End([配置完成])
    N --> End
    R --> End
    H --> End

    style A fill:#10b981,stroke:#333,color:#fff
    style O fill:#ff6b00,stroke:#333,color:#fff
    style R fill:#0ea5e9,stroke:#333,color:#fff
```

---

