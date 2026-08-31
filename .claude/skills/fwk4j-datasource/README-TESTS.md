# 测试运行指南

## 🚀 快速开始

### 方式1: 使用批处理脚本（推荐）

#### 运行所有测试
```bash
# 双击或命令行运行
run-tests.bat
```

#### 运行单个测试
```bash
# 运行指定的测试类
run-single-test.bat DataSourceOnAnnotationTest
run-single-test.bat AliasConfigurationTest
run-single-test.bat TransactionTest
```

#### 交互式选择测试
```bash
# 提供菜单选择要运行的测试
run-tests-by-type.bat
```

### 方式2: 使用 PowerShell（推荐用于 CI/CD）

```powershell
# 运行所有测试
.\run-tests.ps1

# 或者直接使用 PowerShell 命令
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\jdk-17.0.7"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "D:\ldx2\idea\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dmaven.repo.local=d:\maven_repository
```

### 方式3: 直接使用 Maven 命令

```bash
# 设置 Java 17
set JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.7
set PATH=%JAVA_HOME%\bin;%PATH%

# 运行所有测试
D:\ldx2\idea\plugins\maven\lib\maven3\bin\mvn.cmd test -Dmaven.repo.local=d:\maven_repository

# 运行单个测试类
D:\ldx2\idea\plugins\maven\lib\maven3\bin\mvn.cmd test -Dtest=DataSourceOnAnnotationTest -Dmaven.repo.local=d:\maven_repository

# 运行功能测试
D:\ldx2\idea\plugins\maven\lib\maven3\bin\mvn.cmd test -Dtest=**/functional/*Test -Dmaven.repo.local=d:\maven_repository

# 运行单元测试
D:\ldx2\idea\plugins\maven\lib\maven3\bin\mvn.cmd test -Dtest=**/unit/*Test -Dmaven.repo.local=d:\maven_repository
```

## 📋 测试清单

### 功能测试 (functional/)
- ✅ DataSourceOnAnnotationTest - @DataSourceOn 注解功能测试 (10个用例)
- ✅ AliasConfigurationTest - 别名配置功能测试 (8个用例)
- ✅ TransactionTest - 事务管理功能测试 (8个用例)
- ✅ ExceptionScenarioTest - 异常场景测试 (10个用例)
- ✅ ConcurrencyTest - 并发安全测试 (8个用例)

### 单元测试 (unit/)
- ✅ MultiDataSourceManagerTest - Manager 单元测试 (13个用例)

## ⚙️ 前置条件

### 1. 初始化数据库

运行测试前，必须先初始化 PostgreSQL 测试数据库：

```bash
cd ..\ldx2t-commons-test\src\test\resources\sql
init-all-schemas.bat
```

这将创建：
- testdb1 (default 数据源)
- testdb2 (business 数据源)
- testdb3 (log 数据源)
- testdb4 (report 数据源)

### 2. 验证 PostgreSQL 服务

确保 PostgreSQL 服务正在运行：

```bash
# 检查服务状态
sc query postgresql-x64-15

# 或使用 psql 测试连接
psql -U postgres -l
```

### 3. 验证 Java 17

```bash
set JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.7
"%JAVA_HOME%\bin\java.exe" -version
```

应该看到输出：
```
java version "17.0.7"
```

## 🎯 测试场景

### 基础功能测试
```bash
# 测试数据源注入
run-single-test.bat DataSourceOnAnnotationTest
```

### 别名功能测试
```bash
# 测试别名配置和注入
run-single-test.bat AliasConfigurationTest
```

### 事务管理测试
```bash
# 测试事务提交、回滚、传播行为
run-single-test.bat TransactionTest
```

### 异常场景测试
```bash
# 测试各种异常情况的处理
run-single-test.bat ExceptionScenarioTest
```

### 并发安全测试
```bash
# 测试高并发场景（420个线程）
run-single-test.bat ConcurrencyTest
```

### Manager API 测试
```bash
# 测试 MultiDataSourceManager 的所有 API
run-single-test.bat MultiDataSourceManagerTest
```

## 📊 查看测试报告

### 控制台输出
运行测试后，控制台会显示详细的测试日志。

### HTML 报告
测试完成后，查看 HTML 报告：

```bash
# 在浏览器中打开
start target\surefire-reports\index.html
```

### XML 报告
JUnit XML 报告位于：
```
target\surefire-reports\TEST-*.xml
```

## 🐛 常见问题

### 问题1: 数据库连接失败

**错误信息**:
```
Connection refused: localhost:5432
```

**解决方案**:
1. 确认 PostgreSQL 服务已启动
2. 检查数据库是否已创建：`psql -U postgres -l`
3. 重新运行初始化脚本：`init-all-schemas.bat`

### 问题2: Java 版本错误

**错误信息**:
```
Unsupported class file major version
```

**解决方案**:
确保使用 Java 17：
```bash
set JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.7
set PATH=%JAVA_HOME%\bin;%PATH%
java -version
```

### 问题3: Spring Context 初始化失败

**错误信息**:
```
Invalid value type for attribute 'factoryBeanObjectType'
```

**解决方案**:
这个问题已经在代码中修复。确保使用最新的代码。

### 问题4: 并发测试超时

**错误信息**:
```
Timeout waiting for threads to complete
```

**解决方案**:
增加 Druid 连接池的最大连接数：
```yaml
druid:
  max-active: 20  # 增加到 20 或更多
```

### 问题5: Maven 依赖下载慢

**解决方案**:
使用本地仓库（已配置）：
```bash
-Dmaven.repo.local=d:\maven_repository
```

或配置阿里云镜像。

## 📈 性能基准

在标准开发机器上（16GB RAM, i7 CPU）的运行时间：

| 测试类 | 用例数 | 平均耗时 |
|-------|--------|----------|
| DataSourceOnAnnotationTest | 10 | ~10秒 |
| AliasConfigurationTest | 8 | ~5秒 |
| TransactionTest | 8 | ~12秒 |
| ExceptionScenarioTest | 10 | ~5秒 |
| ConcurrencyTest | 8 | ~25秒 |
| MultiDataSourceManagerTest | 13 | ~8秒 |
| **总计** | **57** | **~65秒** |

## 🔧 调试技巧

### 运行单个测试方法
```bash
# 运行特定方法
mvn test -Dtest=DataSourceOnAnnotationTest#test01_VerifyMultiDataSourceManager
```

### 启用调试日志
在 `application-pgsql-test.yml` 中修改：
```yaml
logging:
  level:
    com.ldx2t: DEBUG
    org.springframework: DEBUG
```

### 跳过测试
```bash
mvn install -DskipTests
```

### 只编译不运行测试
```bash
mvn test-compile
```

## 📝 添加新测试

1. 在 `src/test/java/com/ldx2t/commons/datasource/` 下创建测试类
2. 使用 `@SpringBootTest` 和 `@ActiveProfiles("pgsql-test")`
3. 继承测试模式（参考现有测试类）
4. 更新此文档

## 💡 最佳实践

1. **运行前先初始化数据库**: 避免 "table not found" 错误
2. **一次运行一个测试类**: 便于定位问题
3. **检查测试日志**: 每个测试都有详细的日志输出
4. **并发测试单独运行**: 避免资源竞争
5. **定期清理测试数据**: 保持数据库干净

## 🎉 成功标志

测试全部通过时，你会看到：

```
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:05 min
[INFO] Finished at: 2024-XX-XX XX:XX:XX
[INFO] ------------------------------------------------------------------------

Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
```

祝测试顺利！🚀
