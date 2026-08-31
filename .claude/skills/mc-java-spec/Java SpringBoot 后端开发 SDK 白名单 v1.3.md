# Java SpringBoot 后端开发 SDK 白名单 v1.3

> 版本：v1.3
> 发布日期：2026-06-18
> 下次评审：2026-12-18
> 适用范围：所有基于 Spring Boot 3.2 + Java 17 的后端项目
> 基准文档：《Java SpringBoot 后端开发规范 v1.2》

> **v1.3 主要变更**：
> - 🔴 **JSON 库全面切换 fastjson2 → Jackson**（与 java-spec v1.2 对齐）
>   - §3 移除 fastjson2 / fastjson2-extension-spring-boot-starter / fastjson2-extension-spring6 三个依赖
>   - §3 补充 Jackson 关键模块（Spring Boot 已传递，按需显式声明）
> - 🔴 §4 MyBatis Plus 描述更新：`JacksonTypeHandler` 是 MyBatis Plus 自带，**无需额外依赖**
> - 🟡 §10 `LDX2T Commons` 待迁移标记（v1.x 内部 SDK 仍在用 fastjson2，下一版本切 Jackson）
> - 🟡 §6.1 `LogstashEncoder` 不再依赖 fastjson2（实际基于 Jackson）

---

## 0. 说明

### 0.1 白名单定位

本白名单**只**回答两个问题：

1. **能用什么**：哪些 Maven 坐标允许出现在业务 `pom.xml`
2. **用什么版本**：推荐版本与最低兼容版本

**不在本白名单范畴**：

| 主题 | 规范位置 |
|---|---|
| Java 编码规则、Spring Boot 配置、Lombok 使用等 | `./Java SpringBoot 后端开发规范 v1.2.md` |
| API 设计、URL 规则、响应信封、错误码、分页 | `../mc-api-spec/API 响应结构与错误码规范 v1.6.md` 与 `API 接口定义规范 v1.0.md` |
| 数据库设计、SQL 规范 | `../mc-database-spec/` |
| Maven 父 POM 配置、报备流程、BOM 引入方式 | java-spec v1.2 §2 |

### 0.2 版本号说明

| 列 | 含义 |
|---|---|
| **推荐版本** | 当前生产推荐版本（截至发布日期已验证） |
| **最低兼容版本** | Spring Boot 3.2 + Java 17 环境下的最低可用版本 |
| **License** | 开源协议 |

实际版本号由父 POM 的 `<dependencyManagement>` 统一锁定，业务 `pom.xml` **不写** `<version>`。

### 0.3 BOM 清单

父 POM 必须通过 `<dependencyManagement> <scope>import</scope>` 引入以下 BOM：

| BOM 坐标 | 作用 | 推荐版本 |
|---|---|---|
| `org.springframework.boot:spring-boot-dependencies` | Spring Boot 全家桶版本基线（含 Jackson 全套） | `${spring-boot.version}`（3.2.x） |
| `com.baomidou:mybatis-plus-bom` | MyBatis Plus 生态版本 | 3.5.7+ |
| `org.redisson:redisson-bom` | Redisson 生态版本 | 3.31.0+ |
| `io.opentelemetry:opentelemetry-bom` | OpenTelemetry 全套版本 | 1.38.0+ |
| `io.micrometer:micrometer-bom` | Micrometer 指标 / 追踪门面 | 1.13.x（由 SB3.2 管理） |

> **Jackson 版本由 `spring-boot-dependencies` BOM 统一管理**，业务 POM 无需显式声明 Jackson 版本。

### 0.4 许可证底线

| 协议 | 是否允许 | 说明 |
|---|---|---|
| Apache 2.0 / MIT / BSD | ✅ 允许 | 主流宽松协议 |
| MPL 2.0 / EPL 2.0 | ✅ 允许 | 弱传染，文件级 |
| LGPL | ⚠️ 限制 | 静态链接可用，动态修改需开源；按需评估 |
| **GPL / AGPL / SSPL** | ❌ **禁止** | 传染性 / 商业风险 |
| 商业 License（非开源） | ❌ **禁止** | 法律风险 |

引入新 SDK 必须提供 License 全文并经架构组 + 法务审批。

---

## 1. Web 应用与核心框架

*Spring Boot 基础脚手架，强制使用。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License |
|---|---|---|---|---|
| Spring Boot Core | `org.springframework.boot:spring-boot-starter` | 3.2.x | 3.2.0 | Apache 2.0 |
| Spring Boot Web | `org.springframework.boot:spring-boot-starter-web` | 3.2.x | 3.2.0 | Apache 2.0（**传递引入 Jackson**） |
| Spring Boot AOP | `org.springframework.boot:spring-boot-starter-aop` | 3.2.x | 3.2.0 | Apache 2.0 |
| Spring Validation | `org.springframework.boot:spring-boot-starter-validation` | 3.2.x | 3.2.0 | Apache 2.0 |
| Config Processor | `org.springframework.boot:spring-boot-configuration-processor` | 3.2.x | 3.2.0 | Apache 2.0（`<optional>true</optional>`） |

> Spring Boot 版本统一由 `spring-boot-dependencies` BOM 管理，业务 POM 不写版本。

---

## 2. API 接口文档

*OpenAPI 3 + SpringDoc v2（Spring Boot 3 专用）。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| SpringDoc UI | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.6.x | **2.2.0**（SB3 入门门槛） | Apache 2.0 | **注意是 starter-webmvc-ui，1.x 不兼容 SB3** |
| Swagger Annotations | `io.swagger.core.v3:swagger-annotations` | 2.2.x | 2.2.0 | Apache 2.0 | 通常由 springdoc 传递引入 |

> API 注解使用规范见 java-spec v1.2 §5.2 与 mc-api-spec 接口定义规范 v1.0 §8。

---

## 3. 通用工具与 JSON 处理（v1.3 重写：Jackson 取代 fastjson2）

### 3.1 JSON 处理

*Spring Boot 默认集成 Jackson，无需额外依赖。仅在需要扩展模块时显式引入。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Jackson Databind | `com.fasterxml.jackson.core:jackson-databind` | 2.17.x | 2.15.0 | Apache 2.0 | 由 spring-boot-dependencies 管理；通常无需显式声明 |
| Jackson Annotations | `com.fasterxml.jackson.core:jackson-annotations` | 2.17.x | 2.15.0 | Apache 2.0 | 由 databind 传递 |
| Jackson Core | `com.fasterxml.jackson.core:jackson-core` | 2.17.x | 2.15.0 | Apache 2.0 | 由 databind 传递 |
| JSR-310 时间模块 | `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.17.x | 2.15.0 | Apache 2.0 | Java 8 时间类型支持；spring-boot-starter-json 已含 |
| Spring Boot JSON Starter | `org.springframework.boot:spring-boot-starter-json` | 3.2.x | 3.2.0 | Apache 2.0 | 含 Jackson + JSR-310 + JDK8 模块（web 已传递） |
| Jackson Parameter Names | `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.17.x | 2.15.0 | Apache 2.0 | 反序列化时识别构造器参数名（record 场景推荐） |
| Jackson Kotlin Module | `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.17.x | 2.15.0 | Apache 2.0 | （可选）Kotlin 项目专用 |
| Datatype JDK8 | `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.17.x | 2.15.0 | Apache 2.0 | Optional / 等支持 |

> 💡 **典型项目无需显式声明 Jackson 任何坐标**：`spring-boot-starter-web` → `spring-boot-starter-json` → Jackson 全套已自动传递。

> ⚠️ **v1.3 移除项**：
> - ~~`com.alibaba.fastjson2:fastjson2`~~
> - ~~`com.alibaba.fastjson2:fastjson2-extension-spring-boot-starter`~~
> - ~~`com.alibaba.fastjson2:fastjson2-extension-spring6`~~
>
> **理由**：Jackson 原生支持全局 snake_case（一行配置）、Spring Boot 默认集成、无 autotype 历史 RCE 风险。详见 java-spec v1.2 §1.4。

### 3.2 工具类

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Lombok | `org.projectlombok:lombok` | 1.18.34+ | 1.18.30 | MIT | `<scope>provided</scope>`；Entity 禁用 `@Data`（见 java-spec §3.2） |
| Commons Lang3 | `org.apache.commons:commons-lang3` | 3.14.0+ | 3.12.0 | Apache 2.0 | StringUtil 等 |
| Commons Collections | `org.apache.commons:commons-collections4` | 4.4 | 4.4 | Apache 2.0 | 集合操作（含 `CollectionUtils.isEmpty`） |
| Commons IO | `commons-io:commons-io` | 2.16.1+ | 2.11.0 | Apache 2.0 | IO 工具 |
| Commons Codec | `commons-codec:commons-codec` | 1.17.0+ | 1.15 | Apache 2.0 | Base64 / Hex |
| Guava | `com.google.guava:guava` | 33.3.0-jre+ | 32.0.0-jre | Apache 2.0 | 仅用不可变集合等，避免与 Commons 重叠 |
| Hutool | `cn.hutool:hutool-all` | 5.8.32+ | 5.8.0 | MulanPSL-2.0 | （可选）项目组申请后可用 |

---

## 4. 数据存储与搜索引擎

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| MyBatis Plus Starter | `com.baomidou:mybatis-plus-spring-boot3-starter` | 3.5.7+ | **3.5.5**（SB3 稳定门槛） | Apache 2.0 | **注意是 boot3-starter，旧版 `mybatis-plus-boot-starter` 不兼容 SB3**；自带 `JacksonTypeHandler`，无需额外依赖 |
| MP Generator | `com.baomidou:mybatis-plus-generator` | 3.5.7+ | 3.5.5 | Apache 2.0 | 代码生成器，仅开发环境 |
| Druid Starter (Boot 3) | `com.alibaba:druid-spring-boot-3-starter` | 1.2.23+ | **1.2.20**（SB3 兼容） | Apache 2.0 | **专用 boot-3-starter** |
| PostgreSQL Driver | `org.postgresql:postgresql` | 42.7.4+ | 42.6.0 | BSD-2-Clause | JDBC 驱动（推荐数据库） |
| MySQL Driver | `com.mysql:mysql-connector-j` | 9.0.0+ | **8.4.0**（旧 `mysql-connector-java` 已弃用） | GPL-2.0 ⚠️ | 见下方说明 |
| Redisson Starter | `org.redisson:redisson-spring-boot-starter` | 3.34.1+ | **3.20.0**（SB3 兼容） | Apache 2.0 | 分布式锁 / 集合 |
| Spring Data Redis | `org.springframework.boot:spring-boot-starter-data-redis` | 3.2.x | 3.2.0 | Apache 2.0 | RedisTemplate 基础 |
| Commons Pool2 | `org.apache.commons:commons-pool2` | 2.16.1+ | 2.12.0 | Apache 2.0 | Redis 连接池 |
| Spring Data ES | `org.springframework.boot:spring-boot-starter-data-elasticsearch` | 3.2.x | 3.2.0 | Apache 2.0 | ES 官方 Starter |
| Elasticsearch Client | `co.elastic.clients:elasticsearch-java` | 8.14.0+ | 8.11.0 | Apache 2.0 | ES 新版 Java 客户端 |

> ⚠️ **MySQL 驱动 License 警告**：`mysql-connector-j` 是 GPL-2.0（带 FOSS 例外条款）。仅在 MySQL 数据库场景下使用，且需法务确认 FOSS 例外适用范围。若使用 PostgreSQL（推荐），可绕开此问题。

> 💡 **JSONB TypeHandler**：v1.3 起，JSONB 字段统一用 MyBatis Plus 自带的 `com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler`（与全局 JSON 库 Jackson 一致）。详见 java-spec v1.2 §4.4.3。

---

## 5. 消息队列与中间件

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| RocketMQ Starter | `org.apache.rocketmq:rocketmq-spring-boot-starter` | 2.3.1+ | 2.3.0 | Apache 2.0 | SB3 兼容 |
| RocketMQ Client | `org.apache.rocketmq:rocketmq-client` | 5.3.1+ | 5.1.0 | Apache 2.0 | 通常由 starter 引入 |
| RocketMQ ACL | `org.apache.rocketmq:rocketmq-acl` | 5.3.1+ | 5.1.0 | Apache 2.0 | ACL 支持 |
| Activiti Starter | `org.activiti:activiti-spring-boot-starter` | 8.6.0+ | **8.0.0**（SB3 兼容） | Apache 2.0 | 工作流引擎 |
| Aviator | `com.googlecode.aviator:aviator` | 5.4.3+ | 5.4.0 | MPL 2.0 | 表达式引擎（弱传染，文件级，可用） |

---

## 6. 日志与可观测性

### 6.1 基础监控与日志

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Spring Boot Actuator | `org.springframework.boot:spring-boot-starter-actuator` | 3.2.x | 3.2.0 | Apache 2.0 | 健康检查、监控端点 |
| SLF4J API | `org.slf4j:slf4j-api` | 2.0.16+ | 2.0.0 | MIT | 日志门面（SB 已传递） |
| Logback Classic | `ch.qos.logback:logback-classic` | 1.5.8+ | 1.4.0 | EPL 1.0 / LGPL 2.1 ⚠️ | Spring Boot 默认实现 |
| Logback Core | `ch.qos.logback:logback-core` | 1.5.8+ | 1.4.0 | EPL 1.0 / LGPL 2.1 ⚠️ | Logback 核心 |
| Logstash Encoder | `net.logstash.logback:logstash-logback-encoder` | 8.0+ | 7.4 | Apache 2.0 / MIT | **基于 Jackson** 实现 JSON 结构化日志（v1.3 起不再依赖 fastjson2） |

> ⚠️ **Logback License**：EPL 1.0 / LGPL 2.1 双协议，使用者可任选。LGPL 通过动态链接使用不影响主程序，已通过法务审查。

### 6.2 指标度量

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Micrometer Core | `io.micrometer:micrometer-core` | 1.13.x | 1.12.0 | Apache 2.0 | 由 SB BOM 管理 |
| Prometheus Registry | `io.micrometer:micrometer-registry-prometheus` | 1.13.x | 1.12.0 | Apache 2.0 | Prometheus 格式指标 |

### 6.3 分布式追踪

#### 方案 A: OpenTelemetry（推荐）

*CNCF 标准，支持 Jaeger / Zipkin / Tempo / SkyWalking 等后端。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License |
|---|---|---|---|---|
| Micrometer Tracing | `io.micrometer:micrometer-tracing` | 1.3.x | 1.2.0 | Apache 2.0 |
| OTEL Bridge | `io.micrometer:micrometer-tracing-bridge-otel` | 1.3.x | 1.2.0 | Apache 2.0 |
| OTLP Exporter | `io.opentelemetry:opentelemetry-exporter-otlp` | 1.38.0+ | 1.30.0 | Apache 2.0 |

#### 方案 B: Brave（备选）

*成熟稳定，仅支持 Zipkin 后端。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License |
|---|---|---|---|---|
| Micrometer Tracing | `io.micrometer:micrometer-tracing` | 1.3.x | 1.2.0 | Apache 2.0 |
| Brave Bridge | `io.micrometer:micrometer-tracing-bridge-brave` | 1.3.x | 1.2.0 | Apache 2.0 |

#### 方案选型决策

| 公司基础设施 | 推荐方案 |
|---|---|
| SkyWalking / Jaeger / Grafana Tempo / 多后端 | **方案 A OpenTelemetry** |
| Zipkin 单一后端 + 追求低开销 | 方案 B Brave |
| 已部署 SkyWalking Agent | 方案 A + SkyWalking 后端（也可走 §11 Agent 方式，零侵入） |

---

## 7. 第三方平台集成

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Aliyun Core | `com.aliyun:aliyun-java-sdk-core` | 4.7.0+ | 4.6.0 | Apache 2.0 | 阿里云 OpenAPI 基础 |
| Aliyun OSS | `com.aliyun.oss:aliyun-sdk-oss` | 3.17.4+ | 3.17.0 | Apache 2.0 | OSS 官方 SDK |
| WxJava MiniApp | `com.github.binarywang:wx-java-miniapp-spring-boot-starter` | 4.6.5+ | 4.6.0 | Apache 2.0 | 微信小程序（**v1.3 推荐 GA 稳定版，不再用 `.B` 后缀**） |
| WxJava Pay | `com.github.binarywang:wx-java-pay-spring-boot-starter` | 4.6.5+ | 4.6.0 | Apache 2.0 | 微信支付 |
| WxJava CP | `com.github.binarywang:wx-java-cp-spring-boot-starter` | 4.6.5+ | 4.6.0 | Apache 2.0 | 企业微信 |
| DingTalk SDK | `com.aliyun:dingtalk` | 2.1.55+ | 2.0.0 | Apache 2.0 | 钉钉新版 SDK |
| Alipay SDK | `com.alipay.sdk:alipay-sdk-java` | 4.39.0+ | 4.38.0 | Apache 2.0 | 支付宝服务端 SDK |

> **抖音 / TikTok**：官方未发布 Maven 包，需基于 HTTP Client 自研封装。

---

## 8. 单元测试与质量保证

*必须 `<scope>test</scope>`，禁止进入生产包。*

| 组件名称 | Maven 坐标 | 推荐版本 | 最低兼容 | License | 说明 |
|---|---|---|---|---|---|
| Spring Boot Test | `org.springframework.boot:spring-boot-starter-test` | 3.2.x | 3.2.0 | Apache 2.0 | JUnit 5 + Mockito + AssertJ |
| JUnit 5 | `org.junit.jupiter:junit-jupiter` | 5.10.x | 5.9.0 | EPL 2.0 | JUnit 5 聚合包 |
| Mockito Core | `org.mockito:mockito-core` | 5.12.0+ | 5.4.0 | MIT | Mock 框架 |
| Mockito JUnit | `org.mockito:mockito-junit-jupiter` | 5.12.0+ | 5.4.0 | MIT | JUnit 5 扩展 |
| AssertJ | `org.assertj:assertj-core` | 3.26.0+ | 3.24.0 | Apache 2.0 | 流式断言（强制使用，见 java-spec §8.4） |
| JSON Assert | `org.skyscreamer:jsonassert` | 1.5.3+ | 1.5.0 | Apache 2.0 | JSON 断言 |
| H2 Database | `com.h2database:h2` | 2.3.232+ | 2.2.0 | MPL 2.0 / EPL 1.0 | **仅简单单元测试** |
| Testcontainers JUnit | `org.testcontainers:testcontainers` | 1.20.1+ | 1.19.0 | MIT | **DB 集成测试首选**（含 Jackson 依赖） |
| Testcontainers PG | `org.testcontainers:postgresql` | 1.20.1+ | 1.19.0 | MIT | PG 容器 |
| ArchUnit | `com.tngtech.archunit:archunit-junit5` | 1.3.0+ | 1.2.0 | Apache 2.0 | 架构约束测试（见 java-spec §8.5） |
| JaCoCo Maven Plugin | `org.jacoco:jacoco-maven-plugin` | 0.8.12+ | 0.8.10 | EPL 2.0 | 覆盖度检查（强制 80% 行覆盖，见 java-spec §8.2） |

---

## 9. 底层规范与 Spring 组件

*通常由父 POM / Spring Boot BOM 统一管理，业务 POM 无需显式声明。*

| 组件名称 | Maven 坐标 | License |
|---|---|---|
| Servlet API | `jakarta.servlet:jakarta.servlet-api` | EPL 2.0 / GPL-2.0-with-classpath-exception |
| Annotation API | `jakarta.annotation:jakarta.annotation-api` | EPL 2.0 / GPL-2.0 |
| Bean Validation | `jakarta.validation:jakarta.validation-api` | Apache 2.0 |
| JPA API | `jakarta.persistence:jakarta.persistence-api` | EPL 2.0 / GPL-2.0 |
| Transaction API | `jakarta.transaction:jakarta.transaction-api` | EPL 2.0 / GPL-2.0 |
| Spring Core | `org.springframework:spring-core` | Apache 2.0 |
| Spring Context | `org.springframework:spring-context` | Apache 2.0 |
| Spring Beans | `org.springframework:spring-beans` | Apache 2.0 |
| Spring AOP | `org.springframework:spring-aop` | Apache 2.0 |
| Spring TX | `org.springframework:spring-tx` | Apache 2.0 |
| Spring WebMVC | `org.springframework:spring-webmvc` | Apache 2.0 |

> Jakarta 系列协议为 EPL 2.0 + GPL-2.0（带 Classpath Exception），通过动态链接使用不影响业务代码开源义务，已通过法务审查。

---

## 10. 自研 SDK

*仅限公司内部私有仓库组件，需在此处备案。*

| 组件名称 | Maven 坐标 | 推荐版本 | License | 说明 |
|---|---|---|---|---|
| LDX2T Commons | `com.ldx2t:ldx2t-commons-all` | 项目内对齐 | Apache 2.0 | 统一响应、分布式 ID、多数据源、多 Redis、AccessToken。⚠️ **待迁移**：v1.x 内部仍依赖 fastjson2，下一版本（v2.x）将切 Jackson |

**新 SDK 备案模板**：

```markdown
| 组件名称 | （名称）|
| Maven 坐标 | （GroupId:ArtifactId）|
| 当前版本 | （版本号）|
| License | （协议）|
| 功能说明 | （一句话）|
| 维护人 | （负责人）|
| JSON 库 | （Jackson / fastjson2 / 其他）|
```

---

## 11. Java Agent（非 Maven 依赖）

*通过 `-javaagent` 启动参数注入，不在 `pom.xml` 管理。*

| Agent 名称 | 下载来源 | 推荐版本 | License | 说明 |
|---|---|---|---|---|
| SkyWalking Java Agent | `org.apache.skywalking:apm-agent`（仅下载路径用） | 9.4.0+ | Apache 2.0 | 无侵入式 APM；不通过 Maven 依赖引入 |
| SkyWalking Toolkit Trace | `org.apache.skywalking:apm-toolkit-trace` | 9.4.0+ | Apache 2.0 | （可选）手动埋点 API |
| SkyWalking Logback | `org.apache.skywalking:apm-toolkit-logback-1.x` | 9.4.0+ | Apache 2.0 | （可选）TraceId 注入日志 |

> `apm-toolkit-*` 是 Maven 依赖，但仅在使用 SkyWalking 时引入。**Agent 版本与 toolkit 版本必须严格对齐**，否则 TraceId 注入日志可能错乱。SkyWalking Agent 本体通过启动参数 `-javaagent:/path/to/skywalking-agent.jar` 注入。

---

## 附录 A：v1.3 变更详情

### 新增

1. **§3.1 JSON 处理独立小节**：Jackson 全套坐标（databind / annotations / core / jsr310 / parameter-names / jdk8）
2. **§8 ArchUnit、JaCoCo**：架构约束 + 覆盖度检查
3. **§10 备案模板增加 JSON 库字段**：标注 SDK 内部使用的 JSON 库
4. **§11 SkyWalking 版本对齐说明**：Agent 与 toolkit 严格对齐

### 移除（fastjson2 全面下线）

1. ~~`com.alibaba.fastjson2:fastjson2`~~
2. ~~`com.alibaba.fastjson2:fastjson2-extension-spring-boot-starter`~~
3. ~~`com.alibaba.fastjson2:fastjson2-extension-spring6`~~

**理由**：
- Jackson 是 Spring Boot 默认集成，零额外配置
- Jackson 原生支持全局 snake_case（一行 `PropertyNamingStrategies.SNAKE_CASE`）
- fastjson2 历史多次 autotype RCE 漏洞
- 生态支持：Jackson record 序列化、JSR-310 时间、国际化全面更优
- 详见 java-spec v1.2 §1.4 JSON 库选型

### 修正

1. **§4 MyBatis Plus 描述**：`JacksonTypeHandler` 是 MyBatis Plus 自带，无需额外依赖
2. **§6.1 Logstash Encoder 描述**：明确「基于 Jackson 实现 JSON 结构化日志」（不再依赖 fastjson2）
3. **§7 WxJava 版本**：去掉 `.B` 后缀，统一推荐 GA 稳定版（如 `4.6.5`）
4. **§10 LDX2T Commons**：标记「待迁移」（v1.x 内部仍依赖 fastjson2，v2.x 将切 Jackson）

---

## 附录 B：违规检测与申请流程

### B.1 违规检测

CI/CD 流水线自动扫描 `pom.xml`，发现以下情况**直接构建失败**：

1. 业务 `pom.xml` 中出现非白名单内的 `GroupId:ArtifactId`
2. 业务 `pom.xml` 中出现 `<version>` 标签（应由父 POM 管理）
3. 业务 `pom.xml` 中出现 `<repositories>` / `<pluginRepositories>` 标签
4. 引入禁止协议（GPL / AGPL / SSPL）的依赖
5. 生产包中包含 `<scope>test</scope>` 依赖
6. **v1.3 新增**：业务 `pom.xml` 显式引入 fastjson2 坐标（已下线，引入即失败）

### B.2 依赖冲突排查（v1.3 新增）

业务项目引入新 SDK 引起版本冲突时的标准排查流程：

```bash
# 1. 查看依赖树，定位冲突
mvn dependency:tree -Dincludes=com.fasterxml.jackson

# 2. 找出谁传递引入了某依赖
mvn dependency:tree -Dverbose -Dincludes=groupId:artifactId

# 3. 输出到文件分析
mvn dependency:tree -DoutputFile=deps.txt
```

**冲突解决优先级**：

1. **父 POM 统一**：在 `corporate-parent-pom.xml` 的 `<dependencyManagement>` 锁定版本（首选）
2. **业务排除**：排除传递依赖后显式引入正确版本（最后手段）

```xml
<dependency>
  <groupId>org.example</groupId>
  <artifactId>some-sdk</artifactId>
  <exclusions>
    <exclusion>
      <groupId>com.thoughtworks.xstream</groupId>  <!-- 排除冲突依赖 -->
      <artifactId>xstream</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

### B.3 申请引入新 SDK

详细申请流程与申请单模板见 **java-spec v1.2 §2.3 依赖报备流程**。

简要步骤：

1. 填写《第三方 SDK 引入申请单》（含 License、CVE 报告、技术优势说明）
2. 提交至架构组 + 安全团队 + 法务审查
3. 通过后由配置管理员在父 POM 添加版本管理
4. 更新本白名单（下一版本）

---

**文档版本**：v1.3
**发布日期**：2026-06-18
**维护团队**：架构组
**下次评审**：2026-12-18
