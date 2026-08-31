# E2E 测试规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-24
> 配套：`./SKILL.md`（精简入口）；本文件为详细规则
> 关联：mc-webui-spec（前端基础）、mc-api-spec v1.6（响应契约）

---

## 1. 概述

### 1.1 选型：Playwright

| 维度 | Playwright | Cypress | Selenium |
|---|---|---|---|
| 跨浏览器 | ✅ Chromium/Firefox/WebKit | ❌ 仅 Chromium | ✅ |
| 多标签页 | ✅ | ❌ | ✅ |
| 自动等待 | ✅ | ✅ | ❌（需显式） |
| Trace Viewer | ✅（最强大） | ✅ | ❌ |
| Codegen | ✅（录制） | ✅ | ❌ |
| 并行执行 | ✅（worker 级） | ⚠️（受限） | ✅ |
| 速度 | 快 | 快 | 慢 |
| API | 现代 async/await | chain | callback |

**结论**：选 Playwright（微软维护，更新快，跨浏览器一枝独秀）。

### 1.2 测试范围

E2E 只覆盖**关键用户流程**（10% 数量），不重复单元测试覆盖的业务逻辑。

典型场景：
- 登录 / 登出
- 核心业务流（如下单 / 支付 / 退款）
- 关键报表 / 导出
- 跨页面状态保持
- 浏览器兼容性（关键功能在多浏览器跑一遍）

### 1.3 命名规范

`Should_<Behavior>_When_<Condition>`

```ts
test('Should_LoginAndCreateOrder_When_ValidCredentials', async ({ page }) => {})
test('Should_BlockSubmit_When_FormInvalid', async ({ page }) => {})
```

---

## 2. 项目结构

```
project-root/
├── e2e/
│   ├── fixtures/              # 测试数据与共享 fixture
│   │   ├── users.ts
│   │   └── orders.ts
│   ├── pages/                 # Page Object
│   │   ├── LoginPage.ts
│   │   ├── OrderListPage.ts
│   │   └── OrderCreatePage.ts
│   ├── tests/                 # 测试用例
│   │   ├── auth/
│   │   │   ├── login.spec.ts
│   │   │   └── logout.spec.ts
│   │   ├── orders/
│   │   │   ├── create.spec.ts
│   │   │   ├── list.spec.ts
│   │   │   └── cancel.spec.ts
│   │   └── admin/
│   ├── support/               # 辅助函数
│   │   ├── api-helper.ts      # 直接调后端 API 准备数据
│   │   └── db-helper.ts
│   └── playwright.config.ts
├── package.json
└── ...
```

### 2.1 playwright.config.ts

```ts
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e/tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['html', { outputFolder: 'e2e-report' }],
    ['json', { outputFile: 'e2e-results.json' }],
    process.env.CI ? ['github'] : ['list']
  ],
  timeout: 30 * 1000,
  expect: { timeout: 5000 },

  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    storageState: 'e2e/.auth/user.json',  // 默认登录态
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 10_000,
    navigationTimeout: 15_000
  },

  projects: [
    // Setup：登录并保存 storageState
    { name: 'setup', testMatch: /.*\.setup\.ts/, timeout: 60_000 },

    // 主测试浏览器
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['setup']
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      dependencies: ['setup']
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      dependencies: ['setup'],
      testIgnore: ['**/performance/**']  // 性能只在 Chromium 跑
    },

    // 移动端视口
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 7'] },
      dependencies: ['setup']
    }
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000
  }
})
```

### 2.2 package.json

```json
{
  "scripts": {
    "e2e": "playwright test",
    "e2e:ui": "playwright test --ui",
    "e2e:debug": "playwright test --debug",
    "e2e:codegen": "playwright codegen http://localhost:5173",
    "e2e:report": "playwright show-report e2e-report"
  },
  "devDependencies": {
    "@playwright/test": "^1.45.0"
  }
}
```

---

## 3. Page Object 模式

### 3.1 LoginPage

```ts
// e2e/pages/LoginPage.ts
import { Page, expect } from '@playwright/test'

export class LoginPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/login')
  }

  async fillCredentials(username: string, password: string) {
    await this.page.fill('[data-testid="username-input"]', username)
    await this.page.fill('[data-testid="password-input"]', password)
  }

  async submit() {
    await this.page.click('[data-testid="login-btn"]')
  }

  async expectErrorMessage(text: string) {
    await expect(this.page.locator('[data-testid="error-msg"]')).toHaveText(text)
  }

  async expectRedirectedTo(path: string) {
    await expect(this.page).toHaveURL(new RegExp(path))
  }

  async login(username: string, password: string) {
    await this.goto()
    await this.fillCredentials(username, password)
    await this.submit()
  }
}
```

### 3.2 OrderCreatePage

```ts
// e2e/pages/OrderCreatePage.ts
import { Page, expect } from '@playwright/test'

export class OrderCreatePage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/orders/new')
  }

  async fillOrder(sku: string, quantity: number) {
    await this.page.fill('[data-testid="sku-input"]', sku)
    await this.page.fill('[data-testid="quantity-input"]', String(quantity))
  }

  async submit() {
    await this.page.click('[data-testid="submit-btn"]')
  }

  async expectSuccess() {
    await expect(this.page.locator('[data-testid="success-msg"]')).toBeVisible()
    await expect(this.page.locator('[data-testid="order-id"]')).not.toBeEmpty()
  }

  async expectValidationError(field: string, message: string) {
    await expect(this.page.locator(`[data-testid="${field}-error"]`)).toHaveText(message)
  }

  async getCreatedOrderId(): Promise<string> {
    return await this.page.locator('[data-testid="order-id"]').textContent() || ''
  }
}
```

### 3.3 测试用例

```ts
// e2e/tests/orders/create.spec.ts
import { test, expect } from '@playwright/test'
import { LoginPage } from '../../pages/LoginPage'
import { OrderCreatePage } from '../../pages/OrderCreatePage'

test.describe('Order Creation', () => {
  test('Should_CreateOrderAndRedirect_When_ValidInput', async ({ page }) => {
    const login = new LoginPage(page)
    await login.login('admin@example.com', 'Admin@123')
    await login.expectRedirectedTo('/')

    const create = new OrderCreatePage(page)
    await create.goto()
    await create.fillOrder('SKU001', 10)
    await create.submit()
    await create.expectSuccess()
  })

  test('Should_BlockSubmit_When_QuantityZero', async ({ page }) => {
    // 已登录（依赖 setup project 注入 storageState）
    const create = new OrderCreatePage(page)
    await create.goto()
    await create.fillOrder('SKU001', 0)
    await create.submit()
    await create.expectValidationError('quantity', '数量必须大于 0')
  })
})
```

---

## 4. 认证状态管理

### 4.1 Setup Project（推荐）

```ts
// e2e/tests/auth/setup.setup.ts
import { test as setup, expect } from '@playwright/test'
import { LoginPage } from '../../pages/LoginPage'

const ADMIN_AUTH = 'e2e/.auth/admin.json'
const USER_AUTH = 'e2e/.auth/user.json'

setup('Authenticate as admin', async ({ page }) => {
  const login = new LoginPage(page)
  await login.login('admin@example.com', 'Admin@123')
  await login.expectRedirectedTo('/')
  await page.context().storageState({ path: ADMIN_AUTH })
})

setup('Authenticate as user', async ({ page }) => {
  const login = new LoginPage(page)
  await login.login('user@example.com', 'User@123')
  await login.expectRedirectedTo('/')
  await page.context().storageState({ path: USER_AUTH })
})
```

### 4.2 项目配置使用 storageState

```ts
// playwright.config.ts
projects: [
  { name: 'setup', testMatch: /.*\.setup\.ts/ },
  {
    name: 'chromium',
    use: {
      ...devices['Desktop Chrome'],
      storageState: 'e2e/.auth/admin.json'  // 默认 admin
    },
    dependencies: ['setup']
  },
  {
    name: 'user-chromium',
    testDir: './e2e/tests/user/**',
    use: {
      ...devices['Desktop Chrome'],
      storageState: 'e2e/.auth/user.json'
    },
    dependencies: ['setup']
  }
]
```

### 4.3 .gitignore

```
e2e/.auth/
e2e-report/
e2e-results.json
test-results/
playwright-report/
```

---

## 5. 测试数据管理

### 5.1 通过 API 准备数据（推荐）

E2E 不应通过 UI 创建依赖数据（慢 + 易碎），直接调后端 API：

```ts
// e2e/support/api-helper.ts
import { APIRequestContext, expect } from '@playwright/test'

export class ApiHelper {
  constructor(private readonly request: APIRequestContext, private readonly token: string) {}

  async createOrder(payload: any): Promise<string> {
    const res = await this.request.post('/api/v1/orders', {
      headers: {
        Authorization: `Bearer ${this.token}`,
        'Idempotency-Key': crypto.randomUUID()
      },
      data: payload
    })
    expect(res.ok()).toBeTruthy()
    const body = await res.json()
    expect(body.code).toBe(0)
    return body.data.id
  }

  async deleteOrder(id: string) {
    await this.request.delete(`/api/v1/orders/${id}`, {
      headers: { Authorization: `Bearer ${this.token}` }
    })
  }
}
```

### 5.2 Fixture 注入

```ts
// e2e/fixtures/api.ts
import { test as base, APIRequestContext } from '@playwright/test'
import { ApiHelper } from '../support/api-helper'

type Fixture = { api: ApiHelper; adminToken: string }

export const test = base.extend<Fixture>({
  adminToken: async ({ request }, use) => {
    const res = await request.post('/api/v1/auth/login', {
      data: { username: 'admin@example.com', password: 'Admin@123' }
    })
    const body = await res.json()
    await use(body.data.accessToken)
  },

  api: async ({ request, adminToken }, use) => {
    await use(new ApiHelper(request, adminToken))
  }
})
```

### 5.3 测试用

```ts
import { test, expect } from '../../fixtures/api'
import { OrderListPage } from '../../pages/OrderListPage'

test('Should_ShowCreatedOrder_When_NavigateToList', async ({ page, api }) => {
  // 通过 API 准备数据（秒级）
  const orderId = await api.createOrder({ sku: 'SKU001', quantity: 10 })

  // 通过 UI 验证
  const list = new OrderListPage(page)
  await list.goto()
  await list.expectOrderVisible(orderId)

  // 清理
  await api.deleteOrder(orderId)
})
```

### 5.4 数据隔离策略

| 策略 | 适用 |
|---|---|
| 每个测试独立账号 | 推荐，避免互相干扰 |
| 测试用独立 schema（多租户） | 推荐（如系统支持） |
| 每次运行前重置 DB | 慢但彻底 |
| 测试用 API 清理自己创建的数据 | 最常用 |

### 5.5 数据快照与回滚

```ts
test.beforeAll(async ({ request }) => {
  await request.post('/api/test/snapshot')  // 后端测试专用接口
})

test.afterAll(async ({ request }) => {
  await request.post('/api/test/restore')
})
```

> ⚠️ `/api/test/*` 仅在测试环境暴露，生产环境必须禁用。

---

## 6. 测试模式

### 6.1 自动等待（auto-waiting）

Playwright 默认自动等待元素可操作：

```ts
// ✅ 自动等待元素可见 + 可点击
await page.click('#submit')

// ❌ 不需要显式 sleep
await page.waitForTimeout(1000)
await page.click('#submit')
```

### 6.2 网络等待

```ts
// 等待特定请求完成
await Promise.all([
  page.waitForResponse(resp => resp.url().includes('/api/v1/orders') && resp.status() === 200),
  page.click('#submit')
])

// 等待所有网络空闲
await page.waitForLoadState('networkidle')
```

### 6.3 拦截网络请求

```ts
test('Should_ShowFallback_When_ApiFails', async ({ page }) => {
  await page.route('**/api/v1/orders', route => {
    route.fulfill({
      status: 200,
      json: { code: 10001, message: '系统繁忙', data: null, error: null, trace_id: 't', timestamp: 0 }
    })
  })

  await page.goto('/orders')
  await expect(page.locator('[data-testid="error-banner"]')).toBeVisible()
})
```

### 6.4 多标签页

```ts
test('Should_OpenDetailInNewTab_When_Click', async ({ page, context }) => {
  await page.goto('/orders')

  const [newTab] = await Promise.all([
    context.waitForEvent('page'),
    page.click('[data-testid="open-detail-new-tab"]')
  ])

  await newTab.waitForLoadState()
  await expect(newTab).toHaveURL(/orders\/\d+/)
})
```

### 6.5 文件上传

```ts
test('Should_UploadCsv_When_FileSelected', async ({ page }) => {
  await page.goto('/orders/import')
  await page.setInputFiles('[data-testid="file-input"]', {
    name: 'orders.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('id,amount\n1,100.00')
  })

  await page.click('[data-testid="upload-btn"]')
  await expect(page.locator('[data-testid="success-msg"]')).toBeVisible()
})
```

### 6.6 文件下载

```ts
test('Should_DownloadOrders_When_ExportClicked', async ({ page }) => {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('[data-testid="export-btn"]')
  ])
  expect(download.suggestedFilename()).toMatch(/orders.*\.csv$/)
})
```

### 6.7 Dialog 处理

```ts
test('Should_AcceptConfirm_When_Delete', async ({ page }) => {
  page.on('dialog', async dialog => {
    expect(dialog.type()).toBe('confirm')
    expect(dialog.message()).toContain('确认删除')
    await dialog.accept()
  })

  await page.click('[data-testid="delete-btn"]')
  await expect(page.locator('[data-testid="deleted-msg"]')).toBeVisible()
})
```

---

## 7. 跨浏览器测试

### 7.1 项目级配置

见 §2.1，已配置 Chromium / Firefox / WebKit / Mobile Chrome。

### 7.2 特定测试跳过

```ts
test('Should_UseWebkitSpecificFeature_When_Safari', async ({ page, browserName }) => {
  test.skip(browserName !== 'webkit', 'Safari only')
  // ...
})

test.fixme('Should_WorkOnSafari_When_WebkitBugFixed', async ({ page, browserName }) => {
  test.skip(browserName !== 'webkit')
  // 已知 bug，待修复
})
```

---

## 8. 性能测试

### 8.1 基础性能指标

```ts
test('Should_LoadWithin2Seconds_When_HomePage', async ({ page }) => {
  const start = Date.now()
  await page.goto('/')
  const loadTime = Date.now() - start
  expect(loadTime).toBeLessThan(2000)
})
```

### 8.2 Web Vitals

```ts
test('Should_MeetVitalsThreshold_When_Dashboard', async ({ page }) => {
  await page.goto('/dashboard')

  const vitals = await page.evaluate(() => {
    return new Promise(resolve => {
      new PerformanceObserver(list => {
        const entries = list.getEntries()
        const data: any = {}
        for (const e of entries) {
          if (e.name === 'LCP') data.lcp = e.startTime
          if (e.name === 'FID') data.fid = e.processingStart - e.startTime
        }
        if (data.lcp !== undefined) resolve(data)
      }).observe({ type: 'largest-contentful-paint', buffered: true })
    })
  })

  expect(vitals.lcp).toBeLessThan(2500)
})
```

### 8.3 Bundle 大小检查

```ts
test('Should_LimitBundleSize_When_Production', async ({ page }) => {
  const jsSizes: number[] = []

  page.on('response', async resp => {
    if (resp.url().endsWith('.js')) {
      const buf = await resp.body()
      jsSizes.push(buf.length)
    }
  })

  await page.goto('/')

  const totalJs = jsSizes.reduce((a, b) => a + b, 0)
  expect(totalJs).toBeLessThan(500_000)  // < 500KB
})
```

---

## 9. CI 集成

### 9.1 GitHub Actions

```yaml
jobs:
  e2e:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci
      - name: Install Playwright browsers
        run: npx playwright install --with-deps chromium
      - name: Build backend (if needed)
        run: docker compose up -d backend db
      - name: Run E2E tests
        run: npx playwright test --project=chromium
        env:
          E2E_BASE_URL: http://localhost:8080
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-report
          path: playwright-report/
          retention-days: 7
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-trace
          path: test-results/
```

### 9.2 分流策略（省钱）

```yaml
strategy:
  fail-fast: false
  matrix:
    browser: [chromium, firefox, webkit]

jobs:
  e2e-shard:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        shardIndex: [1, 2, 3, 4]
        shardTotal: [4]
    steps:
      - run: npx playwright test --shard=${{ matrix.shardIndex }}/${{ matrix.shardTotal }}
```

### 9.3 定时跑全量

```yaml
on:
  schedule:
    - cron: '0 2 * * *'    # 每天凌晨 2 点
  push:
    branches: [main]
  pull_request:
    types: [opened, synchronize]

jobs:
  e2e-full:
    if: github.event_name == 'schedule' || github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - run: npx playwright test  # 跑全部浏览器
```

---

## 10. 调试

### 10.1 Trace Viewer（最强大）

```bash
npx playwright test --trace on
npx playwright show-trace test-results/.../trace.zip
```

Trace 包含：
- 每个 action 的 DOM 快照
- 网络 / 控制台日志
- 源码高亮

### 10.2 UI Mode

```bash
npx playwright test --ui
```

实时 watch 模式，改代码自动重跑，可视化时间轴。

### 10.3 Codegen（录制）

```bash
npx playwright codegen http://localhost:5173
```

操作页面自动生成代码，复制到测试。

### 10.4 Inspector

```bash
npx playwright test --debug
```

逐 action 暂停，可检查 selector。

---

## 11. 反模式

### 11.1 ❌ 用 sleep / waitForTimeout

```ts
// ❌
await page.waitForTimeout(3000)  // 写死等待
await page.click('#loaded-btn')

// ✅
await page.waitForSelector('#loaded-btn', { state: 'visible' })
await page.click('#loaded-btn')

// 或自动等待
await page.click('#loaded-btn')  // Playwright 自动等
```

### 11.2 ❌ 用 class / CSS selector

```ts
// ❌
await page.click('.btn-primary')

// ✅
await page.click('[data-testid="submit-btn"]')
await page.getByRole('button', { name: '提交' })
```

### 11.3 ❌ 测试逻辑过细

```ts
// ❌ 测每个状态变化
test('click', async () => {
  await page.click('btn')
  expect(await page.locator('.icon').getAttribute('class')).toContain('loading')
  await expect(page.locator('.icon')).toHaveClass(/success/)
})

// ✅ 测用户可见结果
test('Should_ShowSuccess_When_Clicked', async () => {
  await page.click('[data-testid="submit"]')
  await expect(page.locator('[data-testid="success"]')).toBeVisible()
})
```

### 11.4 ❌ 测试间状态共享

```ts
// ❌ 一个测试创建的订单被另一个测试断言
test('create', async () => { await api.createOrder() })
test('query', async () => { await list.expectOrderVisible() })  // 依赖前者

// ✅ 每个测试自己 setup
test('Should_ShowCreatedOrder', async ({ page, api }) => {
  const id = await api.createOrder()
  await page.goto('/orders')
  await expect(page.locator(`[data-testid="order-${id}"]`)).toBeVisible()
  await api.deleteOrder(id)
})
```

### 11.5 ❌ UI 创建依赖数据

```ts
// ❌ 通过 UI 创建用户、分类、订单（很慢）
test('Should_FilterOrders', async ({ page }) => {
  await page.goto('/users/new')
  await page.fill(...); await page.click(...)
  await page.goto('/categories/new')
  // ... 5 步后才开始测真正目标
})

// ✅ API 创建，UI 验证
test('Should_FilterOrders', async ({ page, api }) => {
  const user = await api.createUser()
  const cat = await api.createCategory()
  const order = await api.createOrder({ user, category: cat })
  await page.goto('/orders')
  await page.fill('[data-testid="filter"]', cat)
  await expect(page.locator(`[data-testid="order-${order}"]`)).toBeVisible()
})
```

---

## 附录 A：检查清单

| # | 项 |
|---|---|
| 1 | 命名 `Should_<Behavior>_When_<Condition>` |
| 2 | 用 Page Object 模式 |
| 3 | selector 用 `data-testid` 不用 class |
| 4 | 自动等待，禁 `waitForTimeout` |
| 5 | 认证用 Setup Project + storageState |
| 6 | 测试数据用 API 准备（不 UI） |
| 7 | 测试间无状态依赖 |
| 8 | 关键流程覆盖 Chromium + Firefox + WebKit |
| 9 | CI 配置 trace + screenshot 失败时上传 |
| 10 | 性能测试包含 LCP / bundle 大小 |

## 附录 B：常用 selector 策略

```ts
// 优先级从高到低
page.getByRole('button', { name: '提交' })     // role + name
page.getByLabel('邮箱')                        // label
page.getByPlaceholder('请输入邮箱')            // placeholder
page.getByAltText('Logo')                      // alt
page.getByTitle('提交表单')                    // title
page.getByTestId('submit-btn')                 // data-testid（最稳定）
page.locator('.class')                         // ❌ 不推荐
page.locator('#id')                            // 仅当 ID 稳定
```
