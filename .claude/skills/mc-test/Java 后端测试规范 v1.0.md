# Java 后端测试规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-24
> 配套：`./SKILL.md`（精简入口）；本文件为详细规则
> 关联：mc-java-spec v1.2 §8（基础测试规范）、mc-api-spec v1.6（响应信封）

---

## 1. 概述

### 1.1 测试金字塔

```
        E2E（10%）           Playwright
       集成（20%）     @SpringBootTest + Testcontainers
      单元（70%）     JUnit + Mockito（无 Spring）
```

**比例理由**：
- 单元测试快（< 1s）、稳定、覆盖率高
- 集成测试验证模块协作，慢（< 10s）但必要
- E2E 验证用户流程，最慢（< 60s），数量最少

### 1.2 命名规范

`<Method>_Should_<Behavior>_When_<Condition>`

```java
@Test
void createOrder_Should_ThrowBizException_When_StockInsufficient() { ... }

@Test
void getById_Should_ReturnOrder_When_OrderExists() { ... }
```

**禁用**：
- `testCreateOrder()`（语义不清）
- `test1()` / `test2()`（无意义）
- `shouldReturnOrder()`（缺方法名上下文）

### 1.3 AAA 模式

```java
@Test
void method_Should_Behavior_When_Condition() {
    // Arrange（given）
    ...准备数据、mock 依赖...

    // Act（when）
    ...调用被测方法...

    // Assert（then）
    ...断言结果...
}
```

---

## 2. JUnit 5

### 2.1 注解速查

| 注解 | 用途 |
|---|---|
| `@Test` | 标记测试方法（无参、无返回） |
| `@DisplayName` | 自定义显示名（一般不需要，方法名已自描述） |
| `@ParameterizedTest` | 参数化测试 |
| `@Nested` | 嵌套测试（按场景分组） |
| `@BeforeEach` / `@AfterEach` | 每个测试方法前后执行 |
| `@BeforeAll` / `@AfterAll` | 类级别，必须 `static` |
| `@Disabled` | 禁用测试（标注理由） |
| `@Tag` | 标签过滤（如 `@Tag("integration")`） |

### 2.2 参数化测试

```java
@ParameterizedTest
@CsvSource({
    "100, 50, INSUFFICIENT",
    "50, 100, OK",
    "0, 0, INVALID"
})
void createOrder_Should_ReturnExpected_When_VariousStock(int demand, int stock, String expected) {
    when(inventoryClient.getStock("SKU")).thenReturn(stock);
    // ...
}

@ParameterizedTest
@EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID"})
void cancelOrder_Should_Succeed_When_StatusCancellable(OrderStatus status) { ... }

@ParameterizedTest
@MethodSource("validOrderProvider")
void validate_Should_Pass_When_Valid(OrderCreateRequest req) { ... }

static Stream<OrderCreateRequest> validOrderProvider() {
    return Stream.of(
        new OrderCreateRequest("SKU001", 1),
        new OrderCreateRequest("SKU002", 100)
    );
}
```

### 2.3 嵌套测试

```java
class OrderServiceTest {

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {
        @Test
        void should_ReturnOrderId_When_Success() { ... }

        @Test
        void should_Throw_When_StockInsufficient() { ... }

        @Test
        void should_BeIdempotent_When_SameIdempotencyKey() { ... }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder { ... }
}
```

### 2.4 异常断言

```java
// 推荐：assertThatThrownBy（AssertJ）
assertThatThrownBy(() -> service.create(req, "k"))
    .isInstanceOf(BizException.class)
    .hasMessageContaining("库存不足")
    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(10402));

// JUnit 5 原生
BizException ex = assertThrows(BizException.class, () -> service.create(req, "k"));
assertThat(ex.getCode()).isEqualTo(10402);
```

---

## 3. Mockito

### 3.1 注解速查

| 注解 | 用途 |
|---|---|
| `@Mock` | 创建 mock 对象 |
| `@Spy` | 部分 mock（真实方法 + 覆盖个别方法） |
| `@InjectMocks` | 自动注入 `@Mock` / `@Spy` 到被测对象 |
| `@Captor` | 参数捕获器 |

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock OrderMapper orderMapper;
    @Mock InventoryClient inventoryClient;
    @Captor ArgumentCaptor<OrderDO> orderCaptor;
    @InjectMocks OrderService service;
}
```

### 3.2 stub 写法

```java
// 普通
when(mapper.selectById("1")).thenReturn(order);
when(mapper.selectById(endsWith("X"))).thenThrow(new RuntimeException());

// void 方法
doThrow(new BizException(...)).when(auditService).record(any());

// 多次调用不同返回
when(client.call()).thenReturn("a").thenReturn("b").thenThrow(...);

// 真实方法
when(spy.process(any())).thenCallRealMethod();

// 异步（CompletableFuture）
when(client.asyncCall()).thenReturn(CompletableFuture.completedFuture("ok"));
```

### 3.3 verify

```java
verify(mapper).insert(any());           // 至少调用一次
verify(mapper, times(2)).insert(any());
verify(mapper, never()).delete(any());
verify(mapper, atLeastOnce()).selectById(any());
verify(mapper, timeout(100)).notifyCalled();

// 参数捕获
verify(mapper).insert(orderCaptor.capture());
OrderDO captured = orderCaptor.getValue();
assertThat(captured.getStatus()).isEqualTo("PENDING");
assertThat(captured.getCreatedAt()).isNotNull();
```

### 3.4 进阶

** ArgumentMatcher**：

```java
when(mapper.selectById(argThat(id -> id.startsWith("order_")))).thenReturn(order);
verify(mapper).insert(argThat(o -> o.getStatus().equals("PENDING") && o.getTotalAmount().compareTo(BigDecimal.ZERO) > 0));
```

**Mock 静态方法**（Mockito 3.4+，谨慎使用）：

```java
try (MockedStatic<UUID> mocked = mockStatic(UUID.class)) {
    mocked.when(UUID::randomUUID).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    // test
}
```

> ⚠️ 静态 mock 是代码异味，优先重构。

**Mock final / 构造方法**：需要 `mockito-inline`（Mockito 5+ 默认开启）。

---

## 4. AssertJ

### 4.1 流式断言

```java
// 链式
assertThat(order)
    .isNotNull()
    .extracting(Order::getId, Order::getStatus, Order::getTotalAmount)
    .containsExactly("123", "PAID", new BigDecimal("128.50"));

// 集合
assertThat(orders)
    .hasSize(3)
    .extracting(OrderDO::getStatus)
    .containsExactly(OrderStatus.PAID, OrderStatus.PENDING, OrderStatus.CANCELLED);

// Map
assertThat(map)
    .containsEntry("color", "red")
    .containsKey("weight")
    .doesNotContainKey("deleted");

// 异常
assertThatThrownBy(() -> service.create(req))
    .isInstanceOf(BizException.class)
    .hasMessageContaining("库存不足");
```

### 4.2 时间断言

```java
assertThat(order.getCreatedAt())
    .isNotNull()
    .isCloseTo(Instant.now(), within(1, ChronoUnit.SECONDS));
```

### 4.3 自定义断言

```java
public class OrderAssert extends AbstractAssert<OrderAssert, Order> {
    public OrderAssert(Order actual) {
        super(actual, OrderAssert.class);
    }

    public static OrderAssert assertThat(Order order) {
        return new OrderAssert(order);
    }

    public OrderAssert isPaid() {
        isNotNull();
        if (!OrderStatus.PAID.equals(actual.getStatus())) {
            failWithMessage("Expected PAID but was <%s>", actual.getStatus());
        }
        return this;
    }

    public OrderAssert hasAmount(String amount) {
        isNotNull();
        if (!new BigDecimal(amount).equals(actual.getTotalAmount())) {
            failWithMessage("Expected amount <%s> but was <%s>", amount, actual.getTotalAmount());
        }
        return this;
    }
}

// 使用
OrderAssert.assertThat(order).isPaid().hasAmount("128.50");
```

---

## 5. Testcontainers（DB 集成测试）

### 5.1 配置

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

### 5.2 单例容器（性能优化）

每个测试类启动一次容器，多测试共享：

```java
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);  // 多次运行复用容器

    static {
        PG.start();  // 手动启动（不用 @Container 注解，避免每次启动）
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}

class OrderMapperIT extends IntegrationTestBase {
    @Autowired OrderMapper mapper;
    // ...
}
```

> `~/.testcontainers.properties` 加 `testcontainers.reuse.enable=true` 启用复用。

### 5.3 Redis Testcontainers

```java
static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379);

@DynamicPropertySource
static void redisProps(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
}
```

### 5.4 数据初始化

```java
@BeforeEach
void clean(@Autowired JdbcTemplate jdbc) {
    jdbc.execute("TRUNCATE TABLE orders, order_items RESTART IDENTITY CASCADE");
}

@Test
void test() {
    // 用 Flyway / Liquibase 自动迁移
    // 用 @Sql 加载测试数据
}

@Test
@Sql(scripts = "/testdata/orders.sql")
void getById_Should_ReturnOrder_When_DataExists() { ... }
```

---

## 6. Spring 切片测试

### 6.1 @WebMvcTest（Controller 切片）

```java
@WebMvcTest(OrderController.class)
@Import({GlobalExceptionHandler.class, TraceContext.class})
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean OrderService orderService;

    @Test
    void getOrder_Should_Return200_When_OrderExists() throws Exception {
        when(orderService.getOrderById("1")).thenReturn(new OrderDetailVO("1", "PAID"));

        mockMvc.perform(get("/v1/orders/1"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.id").value("1"))
            .andExpect(jsonPath("$.data.status").value("PAID"))
            .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void getOrder_Should_ReturnBusinessError_When_NotFound() throws Exception {
        when(orderService.getOrderById("999"))
            .thenThrow(new BizException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/v1/orders/999"))
            .andExpect(status().isOk())  // 业务异常仍是 200
            .andExpect(jsonPath("$.code").value(10400))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(header().exists("X-Trace-Id"));
    }
}
```

### 6.2 @MybatisPlusTest（Mapper 切片）

```java
@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IntegrationTestBase.class})  // 引入 Testcontainers
class OrderMapperTest {
    @Autowired OrderMapper mapper;
    // ...
}
```

### 6.3 @JsonTest（序列化切片）

```java
@JsonTest
class OrderSerializationTest {
    @Autowired JacksonTester<OrderDetailVO> tester;

    @Test
    void should_SerializeWithSnakeCase_When_Serialize() throws IOException {
        var vo = new OrderDetailVO("1", "128.50");
        var json = tester.write(vo);
        json.assertThatJson().hasFieldOrPropertyWithValue("total_amount", "128.50");
    }
}
```

---

## 7. 全链路集成测试

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OrderFlowIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void should_CreateQueryUpdate_FullFlow() throws Exception {
        // 1. 创建订单
        var req = new OrderCreateRequest("SKU001", 10);
        var result = mvc.perform(post("/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");

        // 2. 查询
        mvc.perform(get("/v1/orders/" + orderId))
            .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 3. 取消
        mvc.perform(post("/v1/orders/" + orderId + "/cancel"))
            .andExpect(jsonPath("$.code").value(0));

        // 4. 验证状态
        mvc.perform(get("/v1/orders/" + orderId))
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
```

---

## 8. ArchUnit（架构测试）

### 8.1 分层规则

```java
class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
        .importPackages("com.example");

    @Test
    void controllers_should_only_call_services() {
        classes().that().resideInAPackage("..controller..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("..controller..", "..service..", "..dto..", "..vo..",
                                "java..", "javax..", "jakarta..", "org.springframework..",
                                "io.swagger..", "lombok..")
            .check(classes);
    }

    @Test
    void services_should_not_depend_on_controllers() {
        noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .check(classes);
    }

    @Test
    void mappers_should_not_be_used_in_controllers() {
        noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..mapper..")
            .check(classes);
    }
}
```

### 8.2 命名规则

```java
@Test
void controllers_must_be_suffixed_with_Controller() {
    classes().that().resideInAPackage("..controller..")
        .and().areAnnotatedWith(RestController.class)
        .should().haveSimpleNameEndingWith("Controller")
        .check(classes);
}

@Test
void service_implementations_must_be_suffixed_with_ServiceImpl() {
    classes().that().resideInAPackage("..service.impl..")
        .should().haveSimpleNameEndingWith("ServiceImpl")
        .check(classes);
}

@Test
void entities_should_be_suffixed_with_DO() {
    classes().that().resideInAPackage("..entity..")
        .should().haveSimpleNameEndingWith("DO")
        .check(classes);
}
```

### 8.3 禁用注解

```java
@Test
void entities_should_not_use_Data() {
    noClasses().that().areAnnotatedWith(TableName.class)
        .should().beAnnotatedWith(Data.class)
        .check(classes);
}

@Test
void should_not_use_fastjson2() {
    noClasses().should().dependOnClassesThat().resideInAPackage("com.alibaba.fastjson2..")
        .because("Should use Jackson instead of fastjson2 (mc-java-spec v1.2 §1.4)")
        .check(classes);
}

@Test
void should_not_use_javax_namespace() {
    noClasses().should().dependOnClassesThat().resideInAnyPackage("javax.persistence..",
            "javax.servlet..", "javax.validation..")
        .because("Should use jakarta.* (Spring Boot 3)")
        .check(classes);
}
```

### 8.4 循环依赖

```java
@Test
void no_cycles_between_packages() {
    SlicesRuleDefinition.slices()
        .matching("com.example.(*)..")
        .should().beFreeOfCycles()
        .check(classes);
}
```

---

## 9. JaCoCo 覆盖度

### 9.1 Maven 配置

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <!-- Service 层：80% 行 / 70% 分支 -->
                    <rule>
                        <element>BUNDLE</element>
                        <includes>
                            <include>com.example.service.*</include>
                            <include>com.example.service.impl.*</include>
                        </includes>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>
                            </limit>
                        </limits>
                    </rule>
                    <!-- Util / Helper：90% -->
                    <rule>
                        <element>BUNDLE</element>
                        <includes>
                            <include>com.example.util.*</include>
                            <include>com.example.common.*</include>
                        </includes>
                        <limits>
                            <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.90</minimum></limit>
                        </limits>
                    </rule>
                </rules>
                <excludes>
                    <exclude>com.example.entity.**</exclude>     <!-- DO 不测 -->
                    <exclude>com.example.config.**</exclude>      <!-- 配置类不测 -->
                    <exclude>com.example.Application</exclude>
                    <exclude>**/mapper/generated/**</exclude>     <!-- MyBatis Plus 生成 -->
                </excludes>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 9.2 报告

```bash
./mvnw verify
# 报告位置：target/site/jacoco/index.html
```

### 9.3 SonarQube 集成

```bash
./mvnw sonar:sonar \
  -Dsonar.projectKey=com.example:my-app \
  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
```

---

## 10. CI 集成

### 10.1 GitHub Actions

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin', cache: 'maven' }
      - name: Run tests with coverage
        run: ./mvnw verify
      - name: Upload coverage to SonarQube
        run: ./mvnw sonar:sonar
        env: { SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }} }
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-report
          path: |
            target/site/jacoco/
            target/surefire-reports/
```

### 10.2 测试分组

`pom.xml`：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <groups>unit</groups>           <!-- 默认只跑单元 -->
    </configuration>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <groups>integration</groups>    <!-- IT 跑集成 -->
    </configuration>
</plugin>
```

测试标注：

```java
@Tag("unit")
class OrderServiceTest { ... }

@Tag("integration")
class OrderFlowIT { ... }
```

### 10.3 Testcontainers CI 优化

```yaml
services:
  postgres:
    image: postgres:16
    env: { POSTGRES_PASSWORD: postgres }
    ports: ['5432:5432']
    options: >-
      --health-cmd pg_isready
      --health-interval 10s
      --health-timeout 5s
      --health-retries 5
```

```java
// CI 中优先用 Service Container，本机用 Testcontainers
@Container
static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
    .withReuse(true);
```

---

## 11. 测试数据管理

### 11.1 Builder 模式

```java
public class OrderBuilder {
    private Long id = 1L;
    private String orderNo = "ORD001";
    private String status = "PENDING";
    private BigDecimal totalAmount = new BigDecimal("100.00");
    // ...

    public OrderBuilder withStatus(String status) { this.status = status; return this; }
    public OrderBuilder withAmount(String amount) { this.totalAmount = new BigDecimal(amount); return this; }
    public OrderDO build() { return new OrderDO(id, orderNo, status, totalAmount); }
}

// 使用
var order = new OrderBuilder().withStatus("PAID").withAmount("128.50").build();
```

### 11.2 Object Mother

```java
public class OrderMother {
    public static OrderDO paid() { return new OrderBuilder().withStatus("PAID").build(); }
    public static OrderDO pending() { return new OrderBuilder().withStatus("PENDING").build(); }
    public static OrderDO cancelled() { return new OrderBuilder().withStatus("CANCELLED").build(); }
    public static OrderDO withAmount(String amount) {
        return new OrderBuilder().withAmount(amount).build();
    }
}

// 使用
var order = OrderMother.paid();
```

### 11.3 @TestConfiguration

```java
@TestConfiguration
public class TestConfig {
    @Bean
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    @Primary
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder(4);  // 测试用低 cost 加速
    }
}

@SpringBootTest
@Import(TestConfig.class)
class MyTest { ... }
```

---

## 12. 反模式

### 12.1 ❌ 测试依赖执行顺序

```java
// 错误：testCreate 创建数据，testQuery 查它
class OrderTest {
    static String createdId;

    @Test
    void testCreate() { createdId = service.create(...); }

    @Test
    void testQuery() { service.get(createdId); }  // ❌ 依赖 testCreate
}
```

**修正**：每个测试自己 setup 数据。

### 12.2 ❌ Mock 被测对象本身

```java
// 错误：mock 了 service 又测 service
@Mock OrderService service;

@Test
void test() {
    when(service.create(any())).thenReturn("1");
    assertThat(service.create(req)).isEqualTo("1");  // ❌ 没意义
}
```

**修正**：Mock 依赖，不 Mock 被测对象。

### 12.3 ❌ 多个 assert 不分组

```java
// 错误：失败时不知道哪个 assert 挂了
@Test
void test() {
    assertThat(order.getId()).isEqualTo("1");
    assertThat(order.getStatus()).isEqualTo("PAID");
    assertThat(order.getAmount()).isEqualTo("100");
    // 若 status 挂了，amount 永远测不到
}
```

**修正**：用 AssertJ 链式或 soft assertions：

```java
SoftAssertions.assertSoftly(soft -> {
    soft.assertThat(order.getId()).isEqualTo("1");
    soft.assertThat(order.getStatus()).isEqualTo("PAID");
    soft.assertThat(order.getAmount()).isEqualTo("100");
});
```

### 12.4 ❌ 测试里有逻辑（if / for）

测试应当线性、声明式：

```java
// ❌ 错误
for (OrderStatus s : OrderStatus.values()) {
    if (s.isCancellable()) {
        assertThat(service.canCancel(s)).isTrue();
    }
}

// ✅ 正确：参数化
@ParameterizedTest
@EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.INCLUDE)
void should_beCancellable(OrderStatus s) {
    assertThat(s.isCancellable()).isTrue();
}
```

---

## 附录 A：检查清单

| # | 项 |
|---|---|
| 1 | 命名规范：`<Method>_Should_<Behavior>_When_<Condition>` |
| 2 | AAA 结构清晰（given/when/then） |
| 3 | Mock 依赖、不 Mock 被测 |
| 4 | 断言用 AssertJ 流式 |
| 5 | DB 集成用 Testcontainers |
| 6 | 测试独立可并发 |
| 7 | 覆盖度门槛配置 |
| 8 | ArchUnit 规则齐全 |
| 9 | 无 static mutable 共享状态 |
| 10 | 测试方法 < 50 行 |
