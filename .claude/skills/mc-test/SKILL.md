---
name: mc-test
description: 测试相关代码激活。覆盖 Java 后端测试（JUnit 5 / Mockito / AssertJ / Testcontainers / ArchUnit / JaCoCo）、Vue 前端测试（Vitest / Vue Test Utils / MSW / Pinia testing）、E2E 测试（Playwright）、覆盖度门槛、CI 集成。触发词：测试、单元测试、集成测试、E2E、端到端、JUnit、Mockito、Testcontainers、Vitest、Vue Test Utils、MSW、Playwright、JaCoCo、覆盖率、mock、stub、spy、TDD、测试金字塔、Page Object。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: tooling
  tags: [testing, junit, mockito, testcontainers, archunit, vitest, vue-test-utils, msw, playwright, jacoco, coverage, tdd, e2e, contract-test]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - Java 后端测试规范 v1.0.md
    - Vue 前端测试规范 v1.0.md
    - E2E 测试规范 v1.0.md
  related-skills: [mc-java-spec, mc-webui-spec, mc-api-spec, mc-java-security, mc-web-security]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "写 Service 层单元测试"
    - "用 Testcontainers 写 Mapper 集成测试"
    - "@WebMvcTest 测 Controller"
    - "Vue 组件怎么测试"
    - "Pinia store 怎么测试"
    - "前端 mock 后端 API（MSW）"
    - "Playwright 写登录流程 E2E"
    - "JaCoCo 覆盖度门槛配置"
    - "ArchUnit 检查包依赖"
    - "测试 CI 集成"
---

# 测试规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| Java 单元测试（Service / Util） | 场景一 |
| Java 集成测试（Mapper / Controller / 全链路） | 场景二 |
| Java 架构测试 | 场景三：ArchUnit |
| Vue 组件测试 | 场景四 |
| Vue Composable / Pinia 测试 | 场景五 |
| 前端 Mock 后端 API（MSW） | 场景六 |
| E2E 端到端（Playwright） | 场景七 |
| 覆盖度门槛配置 | 场景八 |
| CI 集成 | 场景九 |
| 退出本规范 | 「退出 mc-test」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 后端栈 | JUnit 5 + Mockito + AssertJ + Testcontainers + ArchUnit + JaCoCo |
| 前端栈 | Vitest + @vue/test-utils + happy-dom + MSW + @pinia/testing |
| E2E 栈 | Playwright |
| **适用** | 所有测试代码 + 覆盖度 + CI |
| **不适用** | 业务代码实现（→ mc-java-spec / mc-webui-spec）、API 契约（→ mc-api-spec） |
| 退出 | 「退出 mc-test」 |

## 2. 全局铁律

1. **测试金字塔**：单元 70% / 集成 20% / E2E 10%
2. **命名**：`<Method|Component>_Should_<Behavior>_When_<Condition>`
3. **测试独立**：无状态依赖、无执行顺序依赖、可并发
4. **覆盖度门槛**：Service ≥ 80% 行 / 70% 分支；Util ≥ 90%；Controller ≥ 50%
5. **断言用流式**：AssertJ（Java）/ vitest expect（前端），禁 `assertTrue` + 字符串拼接
6. **Mock 外部依赖**：RPC / HTTP / DB / 时间；**禁 mock 被测对象本身**
7. **DB 集成用 Testcontainers + 真实 PG**，禁 H2（行为差异）
8. **测试代码同样遵守生产代码规范**：命名、风格、阿里约规
9. **测试必须快**：单元 < 1s，集成 < 10s，E2E < 60s
10. **CI 强制门槛**：覆盖度不达标 / 测试失败 → 构建失败

## 3. 场景判定

```
当前任务？
├── Java 单元测试（无 Spring / DB）        → 场景一
├── Java 集成测试（Mapper / Controller / 全链路） → 场景二
├── 架构规则（包依赖 / 命名）              → 场景三：ArchUnit
├── Vue 组件测试                          → 场景四
├── Vue Composable / Pinia 测试           → 场景五
├── 前端 mock 后端 API                    → 场景六：MSW
├── E2E 端到端                            → 场景七：Playwright
├── 配置覆盖度门槛                        → 场景八
├── CI 集成                               → 场景九
└── 检查测试代码合规                      → 场景十：P0 必查
```

### 场景一：Java 单元测试（Service / Util）

**特点**：纯 Java，不启动 Spring，毫秒级。**工具**：JUnit 5 + Mockito（mock 依赖）+ AssertJ（断言）+ AAA 结构（given/when/then）。

**关键规则**：用 `@ExtendWith(MockitoExtension.class)` + `@Mock` 依赖 + `@InjectMocks` 被测；断言用 `assertThatThrownBy(...).isInstanceOf(BizException.class)`；`verify(mapper, never()).insert(any())`。

**进阶**：`@ParameterizedTest` + `@CsvSource` / `@EnumSource` / `@MethodSource` / `@Nested` 嵌套分组 / `ArgumentCaptor` 捕获参数。

**详细代码模板**：见 `./Java 后端测试规范 v1.0.md` §2、§3、§4。

### 场景二：Java 集成测试

**特点**：启动 Spring Context + 真实 DB（Testcontainers），秒级。

| 子类型 | 注解 | 适用 |
|---|---|---|
| Controller 切片 | `@WebMvcTest(OrderController.class)` | Controller + Filter + Advice，Mock Service |
| Mapper 切片 | `@MybatisPlusTest` | Mapper + 真实 DB |
| 全链路 | `@SpringBootTest + @AutoConfigureMockMvc` | HTTP → DB → 响应 |

**Testcontainers PG 单例模板**（每测试类启动一次容器，多测试共享）：

```java
public abstract class IntegrationTestBase {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);
    static { PG.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}
```

> ⚠️ **禁 H2**：与 PG 行为差异（JSONB / 部分索引 / 序列），仅纯单元用。

**详细规则**（MockMvc / @JsonTest / Redis Testcontainers / 数据初始化）：见主规范 §5、§6、§7。

### 场景三：ArchUnit 架构测试

```java
// 包依赖规则
noClasses().that().resideInAPackage("..service..")
    .should().dependOnClassesThat().resideInAPackage("..controller..").check(classes);

// 禁用注解（Entity 禁 @Data）
noClasses().that().areAnnotatedWith(TableName.class)
    .should().beAnnotatedWith(Data.class).check(classes);

// 禁用依赖（mc-java-spec v1.2 禁 fastjson2）
noClasses().should().dependOnClassesThat().resideInAPackage("com.alibaba.fastjson2..").check(classes);
```

**详细规则**（分层 / 命名 / 循环依赖 / 禁用注解）：见主规范 §8。

### 场景四：Vue 组件测试

**工具**：Vitest + @vue/test-utils。

```ts
it('Should_EmitDelete_When_DeleteClicked', async () => {
  const wrapper = mount(OrderList, { props: { orders: [{ id: '1' }] } })
  await wrapper.find('[data-testid="delete-btn"]').trigger('click')
  expect(wrapper.emitted('delete')).toEqual([['1']])
})
```

**关键规则**：用 `data-testid` 选择器（不用 class）；`await` + `flushPromises` 等异步；`shallowMount` 仅组件孤立测试；推荐 `@testing-library/vue`（按 role / label 查找）。

**详细规则**（props / slots / v-model / stubs / Testing Library）：见 `./Vue 前端测试规范 v1.0.md` §3。

### 场景五：Composable / Pinia 测试

```ts
import { setActivePinia, createPinia } from 'pinia'

beforeEach(() => setActivePinia(createPinia()))  // 每个测试独立 store

it('Should_SetTokens_When_LoginSucceeds', async () => {
  vi.mocked(authApi.login).mockResolvedValue({ accessToken: 'xxx' })
  const auth = useAuthStore()
  await auth.login({ username: 'u', password: 'p' })
  expect(auth.isLoggedIn).toBe(true)
})
```

**详细规则**（Composition API / @pinia/testing / 状态隔离）：见主规范 §4。

### 场景六：MSW（前端 mock 后端 API）

```ts
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'

export const server = setupServer(
  http.get('/api/v1/orders', () => HttpResponse.json({
    code: 0, message: 'success',
    data: { list: [{ id: '1', status: 'PAID' }], total: 1, page: 1, page_size: 20, has_more: false, summary: null },
    error: null, trace_id: 't', timestamp: 0
  }))
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())   // 重置单测试覆盖
afterAll(() => server.close())
```

> 必须按 mc-api-spec v1.6 §4 的 6 字段信封返回。

**详细规则**（handler / 错误模拟 / 延迟 / 单测试覆盖）：见主规范 §5。

### 场景七：E2E（Playwright）

**特点**：真实浏览器 + 真实后端，分钟级，覆盖完整用户流程（仅 10% 数量）。

```ts
test('Should_LoginAndCreateOrder_When_ValidCredentials', async ({ page }) => {
  const login = new LoginPage(page)         // Page Object 模式
  await login.login('admin', 'Admin@123')

  await page.goto('/orders/new')
  await page.fill('[data-testid="sku-input"]', 'SKU001')
  await page.click('[data-testid="submit-btn"]')   // 自动等待
  await expect(page.locator('[data-testid="success-msg"]')).toBeVisible()
})
```

**关键模式**：Page Object / Setup Project（storageState 复用登录态）/ 通过 API 准备数据（不 UI，秒级 vs 分钟级）/ 自动等待（禁 `waitForTimeout`）/ Trace Viewer 调试。

**详细规则**（跨浏览器 / 性能测试 / Codegen / CI sharding）：见 `./E2E 测试规范 v1.0.md`。

### 场景八：覆盖度门槛

**后端 JaCoCo**（`pom.xml`）：`<element>BUNDLE</element>` + Service `LINE ≥ 0.80 / BRANCH ≥ 0.70`、Util `LINE ≥ 0.90`；排除 `entity.**` / `config.**` / `Application`。

**前端 Vitest + c8**（`vitest.config.ts`）：`provider: 'c8'` + `thresholds: { lines: 80, branches: 70, functions: 75, statements: 80 }`；排除 `**/*.d.ts` / `main.ts`。

**详细规则**（分层门槛 / SonarQube 集成 / 完整配置）：见 Java 后端测试规范 §9。

### 场景九：CI 集成

**GitHub Actions** 三 jobs：
- `backend-test`：services `postgres:16` + `./mvnw verify`（含 jacoco check）+ 上传 `target/site/jacoco/`
- `frontend-test`：`npm ci` + `npm run test:coverage`
- `e2e`：`npx playwright install --with-deps` + `npx playwright test` + 失败上传 `test-results/`（trace）

**优化**：测试分组（`@Tag("unit")` / `@Tag("integration")`）；Testcontainers `reuse.enable=true`；CI 中用 Service Container 代替 Testcontainers；缓存（`cache: 'maven'` / `cache: 'npm'`）。

**详细规则**：见各主规范 §10。

### 场景十：规范检查（P0 必查 5 项）

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | 命名 `<Method>_Should_<Behavior>_When_<Condition>` | grep `@Test` 方法名 |
| 2 | 断言用 AssertJ（Java）/ expect 流式（前端） | grep `assertTrue` / `assertEquals` |
| 3 | DB 集成测试用 Testcontainers（非 H2） | grep `@Testcontainers` |
| 4 | JaCoCo / Vitest 覆盖度门槛配置 | 检查 `pom.xml` / `vitest.config.ts` |
| 5 | 测试代码无状态共享（无 static mutable） | code review |

**P1/P2/P3**：测试金字塔比例 / Mock 外部不 mock 自身 / 测试速度（单测 < 1s）/ ArchUnit 规则齐全 / MSW 模拟真实信封 / Playwright Page Object / CI 强制门槛。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./Java 后端测试规范 v1.0.md` | JUnit + Mockito + AssertJ + Testcontainers + ArchUnit + JaCoCo | v1.0 |
| `./Vue 前端测试规范 v1.0.md` | Vitest + Vue Test Utils + MSW + Pinia testing | v1.0 |
| `./E2E 测试规范 v1.0.md` | Playwright + Page Object + CI 集成 | v1.0 |
| `../mc-java-spec/Java SpringBoot 后端开发规范 v1.2.md` §8 | 后端测试基础（命名、覆盖度、Testcontainers） | v1.2 |
| `../mc-webui-spec/SKILL.md` | 前端基础规范 | v1.1 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 后端被测代码（命名 / 架构 / ApiResponse） | mc-java-spec |
| 前端被测代码（axios 封装 / TS 类型） | mc-webui-spec |
| Mock 的 API 响应必须符合契约 | mc-api-spec v1.6 §4 |
| 安全测试（权限 / 注入） | mc-java-security + mc-web-security |
| DB 测试数据 / 审计日志 | mc-database-spec |

**测试责任划分**：

| 层 | 主要工具 | 覆盖目标 |
|---|---|---|
| **单元测试** | JUnit + Mockito / Vitest | 业务逻辑（70%） |
| **集成测试** | Testcontainers / Vitest + MSW | 模块协作（20%） |
| **E2E** | Playwright | 关键用户流程（10%） |
| **架构测试** | ArchUnit | 包依赖与命名规则 |
| **契约测试** | MSW + OpenAPI 校验 | 前后端契约一致性 |
