← [返回 README](./README.md)

## 7. 最佳实践

### 7.1 命名规范

- **数据源名称**:使用小写字母和短横线,如 `default`、`business`、`log`
- **Bean 名称**:自动生成,遵循 `{datasourceName}[Type]` 格式
- **Mapper 接口**:放在不同包下,使用 @MapperScan 指定数据源
- **表命名**:遵循企业开发规范,使用下划线命名法

### 7.2 连接池配置建议

| 场景 | initial-size | min-idle | max-active |
|------|--------------|----------|-----------|
| 低并发(日志) | 3 | 3 | 10 |
| 中等并发(业务) | 5 | 5 | 20 |
| 高并发(核心业务) | 10 | 10 | 50 |
| 报表查询 | 2 | 2 | 10 |

### 7.3 客户端选择建议

| 场景 | 推荐组件 | 原因 |
|------|---------|------|
| 简单 CRUD | SqlSessionTemplate | 线程安全,与 Spring 集成好 |
| 复杂查询 | SqlSessionFactory | 更灵活的配置 |
| 事务管理 | PlatformTransactionManager | 支持声明式和编程式事务 |
| MyBatis Plus | BaseMapper | 自动 CRUD,代码更简洁 |

### 7.4 注入方式选择建议

根据不同场景选择合适的注入方式:

| 场景 | 推荐方式 | 优先级 | 说明 |
|------|---------|--------|------|
| 一个类一个源 | @DataSourceOn | ⭐⭐⭐⭐⭐ | 代码最简洁,语义最清晰 |
| 只用 default | 不用注解 | ⭐⭐⭐⭐ | 依赖 @Primary,简单直接 |
| 一个类多个源 | @DataSourceOn + @Qualifier | ⭐⭐⭐⭐ | 主源用 @DataSourceOn,其他用 @Qualifier |
| 精确控制注入 | @Qualifier | ⭐⭐⭐⭐ | 明确指定,最灵活 |
| 字段名匹配 | @Resource | ⭐⭐⭐⭐ | 优先按名称注入,可靠绕过 @Primary |
| 动态切换源 | MultiDataSourceManager | ⭐⭐⭐⭐⭐ | 运行时动态选择 |
| 多租户场景 | MultiDataSourceManager | ⭐⭐⭐⭐⭐ | 根据租户 ID 动态获取 |
| 代码简洁优先 | @DataSourceOn | ⭐⭐⭐⭐⭐ | 减少样板代码 |

**推荐**: 一个类用一个源选 `@DataSourceOn`, 多个源选 `@Qualifier`, 动态切换选 `MultiDataSourceManager`

### 7.5 安全建议

- 生产环境【强制】配置数据库密码
- 不同业务使用不同的数据库账号
- 敏感配置使用环境变量或配置中心
- 定期检查连接池状态,避免连接泄漏
- 开启 Druid SQL 防火墙,防止 SQL 注入
- 使用只读账号连接从库

### 7.6 性能优化建议

- 合理设置连接池参数,避免频繁创建连接
- 使用批量操作提高性能
- 开启 Druid 监控,及时发现慢 SQL
- 合理使用事务,避免大事务
- 读写分离,降低主库压力
- 使用连接池监控,及时发现问题

### 7.7 MyBatis Plus 最佳实践

- 使用 BaseMapper 简化 CRUD 操作
- 合理使用分页插件,避免全表扫描
- 使用逻辑删除而非物理删除
- 使用乐观锁处理并发更新
- 合理使用缓存,提高查询性能
- 使用 @MapperScan 指定不同数据源的 Mapper

## 8. 监控与运维

### 8.1 监控指标

暴露以下 Actuator 监控指标:

- `ldx2t.datasource.count`:数据源总数
- `ldx2t.datasource.connection.active`:活跃连接数
- `ldx2t.datasource.connection.idle`:空闲连接数
- `ldx2t.datasource.connection.create`:创建连接次数
- `ldx2t.datasource.connection.destroy`:销毁连接次数
- `ldx2t.datasource.health.status`:健康检查状态

### 8.2 Druid 监控

Druid 提供内置监控页面:

```yaml
spring:
  datasource:
    druid:
      stat-view-servlet:
        enabled: true
        url-pattern: /druid/*
        reset-enable: false
        login-username: admin
        login-password: admin
      web-stat-filter:
        enabled: true
        url-pattern: /*
        exclusions: "*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*"
```

访问 `http://localhost:8080/druid/` 查看监控信息。

### 8.3 日志记录

组件自动记录以下日志:

- 数据源初始化和销毁
- 连接池状态变化
- 健康检查结果
- 慢 SQL 记录
- 异常和错误信息

### 8.4 故障排查

#### 问题一:连接超时

**原因**:
- 数据库服务器不可达
- 网络问题
- 连接池耗尽
- 防火墙阻止连接

**解决方案**:
1. 检查数据库服务器状态
2. 检查网络连通性
3. 增大连接池参数
4. 检查是否存在连接泄漏
5. 开启 Druid 连接泄漏检测

#### 问题二:注入失败

**原因**:
- 配置错误
- Bean 名称冲突
- 数据源未启用
- Mapper 扫描路径错误

**解决方案**:
1. 检查 application.yml 配置
2. 确认 `ldx2t.commons.datasource.enabled=true`
3. 检查数据源名称是否唯一
4. 查看启动日志确认 Bean 创建情况
5. 检查 @MapperScan 配置是否正确

#### 问题三:事务不生效

**原因**:
- @Transactional 注解位置错误
- 事务管理器指定错误
- 方法不是 public
- 类内部调用

**解决方案**:
1. 确保 @Transactional 在 public 方法上
2. 多数据源场景明确指定事务管理器
3. 避免类内部方法调用
4. 检查事务传播行为配置

