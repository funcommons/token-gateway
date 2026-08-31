---
name: fwk4j-datasource
description: framework4j 多 DataSource 管理（MultiDataSourceManager + @DataSourceOn 注解注入 + Druid 连接池 + MyBatis Plus + strict/fallback）。触发词：@DataSourceOn、MultiDataSourceManager、多 DataSource、多数据源、Druid、MyBatis Plus、读写分离、strict、fallback、连接池监控。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-data
  tags: [datasource, druid, mybatis-plus, multi-datasource]
  language: zh-CN
  artifactId: framework4j-datasource
  config-prefix: framework4j.datasource
  examples:
    - "多数据源怎么配"                # → datasources map
    - "按名字注入 DataSource"         # → @DataSourceOn("business")
    - "读写分离"                     # → default=写 + read=读
    - "数据源不存在怎么办"            # → strict=true 抛异常 / false fallback
---

# framework4j-datasource 多 DataSource

## 配置

```yaml
framework4j:
  datasource:
    enabled: true
    datasources:
      default:                        # 主库（写）
        url: jdbc:postgresql://localhost/mydb
        username: postgres
        password: ${DB_PASSWORD}
        initial-size: 5
        max-active: 20
      business:                       # 业务库
        url: jdbc:postgresql://localhost/business
      log:                            # 日志库
        url: jdbc:postgresql://localhost/logs
```

## @DataSourceOn 注解注入

```java
@Service
public class OrderService {
    @DataSourceOn("business")
    private DataSource businessDs;

    @DataSourceOn(value = "log", strict = false)  // 缺失则 fallback default
    private DataSource logDs;
}
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-datasource</artifactId>
    <version>v1.1.1</version>
</dependency>
```

## MyBatis Plus 内置插件

| 插件 | 默认 | 开启方式 |
|---|---|---|
| 分页 | ✅ 加载 | 默认 |
| 防全表更新 | ✅ 加载 | 默认 |
| 乐观锁 | ❌ | `mybatis-plus-plugins.optimistic-lock: true` |
| 多租户 | ❌ | `mybatis-plus-plugins.data-permission: true` + TenantLineHandler Bean |

```yaml
framework4j:
  datasource:
    datasources:
      default:
        mybatis-plus-plugins:
          pagination: true
          block-attack: true
          optimistic-lock: true
```

用户自定义 → `@Bean MybatisPlusInterceptor`（SDK 自动退让）

## ⚠️ 依赖冲突注意事项

- `mybatis-plus-jsqlparser` 为 optional 依赖，不传递
- 消费者需内置分页插件时自行引入（版本与 mybatis-plus 对齐）
- jsqlparser 4.x ↔ 5.x 不兼容（包路径重构），禁止混用
- framework4j-compat-test 模块自动验证旧版兼容性
