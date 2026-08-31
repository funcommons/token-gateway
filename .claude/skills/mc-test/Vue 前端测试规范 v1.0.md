# Vue 前端测试规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-24
> 配套：`./SKILL.md`（精简入口）；本文件为详细规则
> 关联：mc-webui-spec（前端基础规范）、mc-api-spec v1.6（响应信封）、mc-web-security（Token 管理）

---

## 1. 概述

### 1.1 测试金字塔

```
        E2E（10%）              Playwright
      集成（20%）         Vitest + MSW + Pinia
     单元（70%）         Vitest（pure / composables）
```

### 1.2 技术栈

| 工具 | 用途 |
|---|---|
| **Vitest** | 测试运行器 + 断言 + mock（Vite 原生，比 Jest 快） |
| **@vue/test-utils** | Vue 组件挂载、props / events / slots 测试 |
| **happy-dom** | DOM 实现（比 jsdom 快 5x） |
| **MSW** | Mock Service Worker，拦截 fetch / axios |
| **@pinia/testing** | Pinia store 测试 |
| **@vitest/coverage-c8** | 覆盖度 |

### 1.3 命名规范

`<Component|Composable>_Should_<Behavior>_When_<Condition>`

```ts
describe('OrderList', () => {
  it('Should_RenderRows_When_OrdersProvided', () => {})
  it('Should_EmitDeleteEvent_When_DeleteButtonClicked', async () => {})
})
```

### 1.4 AAA 模式

```ts
it('Should_...', () => {
  // Arrange
  const wrapper = mount(OrderList, { props: {...} })

  // Act
  await wrapper.find('button').trigger('click')

  // Assert
  expect(wrapper.emitted('delete')).toEqual([['1']])
})
```

---

## 2. Vitest 配置

### 2.1 安装

```bash
npm i -D vitest @vue/test-utils happy-dom msw @pinia/testing \
  @vitest/coverage-c8 @testing-library/vue
```

### 2.2 vitest.config.ts

```ts
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue2'  // 或 @vitejs/plugin-vue
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') }
  },
  test: {
    environment: 'happy-dom',
    globals: false,                  // 显式 import，不污染全局
    setupFiles: ['./test/setup.ts'],
    coverage: {
      provider: 'c8',
      reporter: ['text', 'html', 'lcov'],
      thresholds: {
        lines: 80, branches: 70, functions: 75, statements: 80
      },
      exclude: [
        '**/*.d.ts',
        'src/main.ts',
        'src/router/index.ts',
        'src/**/*.types.ts',
        'test/**'
      ]
    }
  }
})
```

### 2.3 test/setup.ts

```ts
import { config } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

// 全局 i18n（避免每个测试都 setup）
const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { common: { ok: '确定', cancel: '取消' } } }
})

config.global.plugins = [i18n]
config.global.mocks = {
  $t: (key: string) => key
}

// 全局 mock localStorage / sessionStorage
const sessionStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (k: string) => store[k] ?? null,
    setItem: (k: string, v: string) => { store[k] = v },
    removeItem: (k: string) => { delete store[k] },
    clear: () => { store = {} }
  }
})()

Object.defineProperty(window, 'sessionStorage', { value: sessionStorageMock })
```

### 2.4 package.json 脚本

```json
{
  "scripts": {
    "test": "vitest",
    "test:ui": "vitest --ui",
    "test:run": "vitest run",
    "test:coverage": "vitest run --coverage"
  }
}
```

---

## 3. Vue 组件测试（@vue/test-utils）

### 3.1 基础挂载

```ts
import { mount } from '@vue/test-utils'
import OrderList from '@/views/orders/OrderList.vue'

describe('OrderList', () => {
  it('Should_RenderRows_When_OrdersProvided', () => {
    const wrapper = mount(OrderList, {
      props: {
        orders: [
          { id: '1', status: 'PAID', total_amount: '128.50' },
          { id: '2', status: 'PENDING', total_amount: '50.00' }
        ]
      }
    })

    expect(wrapper.findAll('[data-testid="order-row"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('128.50')
  })
})
```

### 3.2 测试 Props

```ts
it('Should_ReflectCount_When_PropChanges', async () => {
  const wrapper = mount(UserAvatar, { props: { count: 0 } })
  expect(wrapper.find('[data-testid="count"]').text()).toBe('0')

  await wrapper.setProps({ count: 5 })
  expect(wrapper.find('[data-testid="count"]').text()).toBe('5')
})
```

### 3.3 测试事件

```ts
it('Should_EmitDeleteEvent_When_DeleteButtonClicked', async () => {
  const wrapper = mount(OrderList, {
    props: { orders: [{ id: '1', status: 'PAID' }] }
  })

  await wrapper.find('[data-testid="delete-btn"]').trigger('click')

  expect(wrapper.emitted('delete')).toHaveLength(1)
  expect(wrapper.emitted('delete')![0]).toEqual(['1'])
})

it('Should_NotEmit_When_Disabled', async () => {
  const wrapper = mount(SubmitButton, { props: { disabled: true } })
  await wrapper.find('button').trigger('click')
  expect(wrapper.emitted('submit')).toBeUndefined()
})
```

### 3.4 测试 Slots

```ts
it('Should_RenderSlotContent_When_Provided', () => {
  const wrapper = mount(Card, {
    slots: {
      header: '<h1>My Header</h1>',
      default: '<p>Body</p>'
    }
  })

  expect(wrapper.find('h1').text()).toBe('My Header')
  expect(wrapper.find('p').text()).toBe('Body')
})
```

### 3.5 测试 v-model

```ts
it('Should_EmitUpdateEvent_When_InputChanges', async () => {
  const wrapper = mount(MyInput, { props: { modelValue: 'foo' } })

  await wrapper.find('input').setValue('bar')

  expect(wrapper.emitted('update:modelValue')).toEqual([['bar']])
})
```

### 3.6 Stub 子组件

```ts
it('Should_PassOrderId_When_ChildEmits', async () => {
  const wrapper = mount(OrderPage, {
    global: {
      stubs: {
        OrderForm: {
          template: '<button @click="$emit("submit", 123)">OK</button>',
          emits: ['submit']
        }
      }
    }
  })

  await wrapper.find('button').trigger('click')

  expect(wrapper.emitted('order-submitted')).toEqual([[123]])
})
```

### 3.7 shallowMount vs mount

- `mount`：渲染所有子组件（推荐默认）
- `shallowMount`：所有子组件 stub（仅组件孤立测试时用）

```ts
// 当子组件有复杂依赖（如 store、API）时
const wrapper = shallowMount(ComplexPage, {
  global: { stubs: { HeavyChart: true } }
})
```

### 3.8 测试异步

```ts
it('Should_RenderData_When_ApiResolved', async () => {
  const wrapper = mount(UserProfile)

  // 等待所有异步
  await flushPromises()

  expect(wrapper.find('[data-testid="user-name"]').text()).toBe('Alice')
})

// 导入
import { flushPromises } from '@test-utils/helpers'
```

### 3.9 Testing Library Vue（推荐）

```ts
import { render, screen, fireEvent } from '@testing-library/vue'

it('Should_ShowValidation_When_EmailInvalid', async () => {
  render(LoginForm)

  await fireEvent.update(screen.getByLabelText('邮箱'), 'invalid')
  await fireEvent.click(screen.getByRole('button', { name: '提交' }))

  expect(await screen.findByText('邮箱格式不正确')).toBeInTheDocument()
})
```

> Testing Library 更贴近用户视角（按 label / role 查找），比 test-utils 的 selector 更健壮。

---

## 4. Composable / Pinia 测试

### 4.1 Composable 测试

```ts
import { useCounter } from '@/composables/useCounter'

describe('useCounter', () => {
  it('Should_Increment_When_Called', () => {
    const { count, increment } = useCounter()

    expect(count.value).toBe(0)
    increment()
    expect(count.value).toBe(1)
  })

  it('Should_BeIndependent_When_MultipleInstances', () => {
    const a = useCounter()
    const b = useCounter()

    a.increment()
    expect(a.count.value).toBe(1)
    expect(b.count.value).toBe(0)
  })
})
```

**带参数 / setup 上下文**：

```ts
import { withSetup } from '@test-utils/helpers'

it('Should_UseProvide_When_InComponent', () => {
  const [result, app] = withSetup(() => useSharedState())
  // ...
  app.unmount()
})
```

### 4.2 Pinia store 测试

```ts
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

describe('useAuthStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('Should_SetTokensAndUser_When_LoginSucceeds', async () => {
    vi.mock('@/api/auth', () => ({
      authApi: {
        login: vi.fn().mockResolvedValue({
          accessToken: 'access-xxx',
          refreshToken: 'refresh-xxx',
          user: { id: '1', name: 'Alice' },
          roles: ['USER'],
          permissions: ['order:read']
        })
      }
    }))

    const auth = useAuthStore()
    await auth.login({ username: 'alice', password: 'pwd' })

    expect(auth.isLoggedIn).toBe(true)
    expect(auth.user?.id).toBe('1')
    expect(auth.roles).toEqual(['USER'])
    expect(auth.hasPermission('order:read')).toBe(true)
    expect(sessionStorage.getItem('access_token')).toBe('access-xxx')
  })

  it('Should_ClearAll_When_Logout', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'xxx'
    sessionStorage.setItem('access_token', 'xxx')

    await auth.logout()

    expect(auth.accessToken).toBeNull()
    expect(sessionStorage.getItem('access_token')).toBeNull()
  })
})
```

### 4.3 @pinia/testing

```ts
import { createTestingPinia } from '@pinia/testing'

const wrapper = mount(MyComponent, {
  global: {
    plugins: [createTestingPinia({
      createSpy: vi.fn,          // 自动 mock 所有 action
      stubActions: false,         // 默认 false，action 实际执行
      initialState: {
        auth: { user: { id: '1' } }
      }
    })]
  }
})
```

---

## 5. MSW（Mock Service Worker）

### 5.1 安装与配置

```bash
npm i -D msw
npx msw init public/ --save      # 浏览器用
```

### 5.2 Handler 定义

```ts
// test/mocks/handlers.ts
import { http, HttpResponse } from 'msw'

export const handlers = [
  http.get('/api/v1/orders', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') || 1)
    return HttpResponse.json({
      code: 0,
      message: 'success',
      data: {
        list: [{ id: '1', status: 'PAID', total_amount: '128.50' }],
        total: 1,
        page,
        page_size: 20,
        has_more: false,
        summary: null
      },
      error: null,
      trace_id: 'test-trace',
      timestamp: 1718660400000
    })
  }),

  http.post('/api/v1/orders', async ({ request }) => {
    const body = await request.json() as any
    return HttpResponse.json({
      code: 0,
      message: 'success',
      data: { id: 'new-order-1', ...body },
      error: null,
      trace_id: 'test-trace',
      timestamp: 1718660400000
    })
  }),

  http.get('/api/v1/orders/:id', ({ params }) => {
    if (params.id === '999') {
      return HttpResponse.json({
        code: 10400,
        message: '请求资源不存在',
        data: null,
        error: null,
        trace_id: 'test-trace',
        timestamp: 1718660400000
      })
    }
    return HttpResponse.json({
      code: 0,
      data: { id: params.id, status: 'PAID' },
      // ...
    })
  })
]
```

### 5.3 服务端 setup（Node 测试）

```ts
// test/mocks/server.ts
import { setupServer } from 'msw/node'
import { handlers } from './handlers'

export const server = setupServer(...handlers)
```

```ts
// test/setup.ts（已在 §2.3）
import { server } from './mocks/server'

beforeAll(() => server.listen({ onUnhandledRequest: 'warn' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
```

### 5.4 单个测试覆盖 mock

```ts
import { server } from '@/test/mocks/server'
import { http, HttpResponse } from 'msw'

it('Should_ShowError_When_ApiFails', async () => {
  server.use(
    http.get('/api/v1/orders', () => {
      return HttpResponse.json({
        code: 10001, message: '系统繁忙', data: null,
        error: null, trace_id: 't', timestamp: 0
      })
    })
  )

  render(OrderList)
  expect(await screen.findByText('系统繁忙')).toBeInTheDocument()
})
```

### 5.5 模拟网络错误

```ts
it('Should_HandleNetworkError_When_ServerDown', async () => {
  server.use(http.get('/api/v1/orders', () => HttpResponse.error()))
  // ...
})
```

### 5.6 模拟延迟

```ts
server.use(
  http.get('/api/v1/orders', async () => {
    await delay(5000)
    return HttpResponse.json({ ... })
  })
)
```

---

## 6. Vue Router 测试

### 6.1 Mock Router

```ts
const mockRouter = { push: vi.fn(), replace: vi.fn() }

const wrapper = mount(MyComponent, {
  global: {
    mocks: { $router: mockRouter, $route: { path: '/test', query: {} } }
  }
})

await wrapper.find('button').trigger('click')
expect(mockRouter.push).toHaveBeenCalledWith('/expected-path')
```

### 6.2 真实 Router

```ts
import { createRouter, createMemoryHistory } from 'vue-router'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div>Home</div>' } },
    { path: '/orders', component: OrderList }
  ]
})

beforeEach(async () => { await router.push('/') })

it('Should_NavigateToOrders_When_Clicked', async () => {
  const wrapper = mount(NavBar, { global: { plugins: [router] } })
  await wrapper.find('[data-testid="orders-link"]').trigger('click')
  await router.isReady()
  expect(router.currentRoute.value.path).toBe('/orders')
})
```

### 6.3 路由守卫测试

```ts
describe('Auth Guard', () => {
  it('Should_AllowAccess_When_LoggedIn', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.accessToken = 'xxx'

    const next = vi.fn()
    await guard({ meta: { requiresAuth: true } } as any, next as any)

    expect(next).toHaveBeenCalled()
    expect(next).not.toHaveBeenCalledWith(expect.objectContaining({ name: 'login' }))
  })

  it('Should_RedirectToLogin_When_NotLoggedIn', async () => {
    setActivePinia(createPinia())
    const next = vi.fn()
    await guard({ meta: { requiresAuth: true }, fullPath: '/orders' } as any, next as any)
    expect(next).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/orders' } })
  })
})
```

---

## 7. 异步与定时器

### 7.1 vi.useFakeTimers

```ts
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

it('Should_RefreshToken_When_AlmostExpired', () => {
  const now = Date.now()
  vi.setSystemTime(now)

  const auth = useAuthStore()
  auth.accessToken = 'token-expiring-in-30s'  // 设 token 30s 后过期

  // 推进时间
  vi.advanceTimersByTime(31 * 1000)

  expect(refreshSpy).toHaveBeenCalled()
})
```

### 7.2 等待动画

```ts
import { nextTick } from 'vue'

it('Should_ShowModal_When_Clicked', async () => {
  const wrapper = mount(Demo)
  await wrapper.find('button').trigger('click')
  await nextTick()  // 等待 Vue 重新渲染
  expect(wrapper.find('.modal').exists()).toBe(true)
})
```

---

## 8. Snapshot 测试

### 8.1 谨慎使用

Snapshot 测试**容易产生噪音**（任何 UI 改动都 fail），仅在稳定组件用。

```ts
it('Should_MatchSnapshot_When_DefaultState', () => {
  const wrapper = mount(Header)
  expect(wrapper.html()).toMatchSnapshot()
})
```

### 8.2 推荐替代：属性断言

```ts
it('Should_HaveDefaultClass_When_NoProps', () => {
  const wrapper = mount(Header)
  expect(wrapper.classes()).toContain('header-default')
  expect(wrapper.attributes('role')).toBe('banner')
})
```

---

## 9. CI 集成

### 9.1 GitHub Actions

```yaml
jobs:
  frontend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
      - run: npm ci
      - name: Run tests with coverage
        run: npm run test:coverage
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: vitest-coverage
          path: coverage/
```

### 9.2 与 SonarQube 集成

```yaml
- name: SonarQube
  run: npx sonar-scanner
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

```properties
# sonar-project.properties
sonar.projectKey=com.example:frontend
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.coverage.exclusions=**/*.d.ts,src/main.ts
```

---

## 10. 反模式

### 10.1 ❌ 测试实现细节

```ts
// ❌ 测内部方法
expect(wrapper.vm.privateMethod()).toBe(true)

// ❌ 测内部状态
expect(wrapper.vm.internalFlag).toBe(true)

// ✅ 测可见行为
expect(wrapper.find('[data-testid="success-msg"]').exists()).toBe(true)
```

### 10.2 ❌ 使用 class 选择器

```ts
// ❌ 易因样式重构破坏
expect(wrapper.find('.btn-primary').exists()).toBe(true)

// ✅ 用 data-testid
expect(wrapper.find('[data-testid="submit-btn"]').exists()).toBe(true)
```

### 10.3 ❌ Mock 自身代码

```ts
// ❌ mock 了被测对象
vi.mock('@/stores/auth', () => ({ useAuthStore: () => fakeAuth }))

// 测组件时不应 mock 它自己
```

### 10.4 ❌ 不等待异步

```ts
// ❌
wrapper.find('button').trigger('click')
expect(wrapper.text()).toContain('Done')  // 异步未完成

// ✅
await wrapper.find('button').trigger('click')
await flushPromises()
expect(wrapper.text()).toContain('Done')
```

---

## 附录 A：检查清单

| # | 项 |
|---|---|
| 1 | 命名 `<Component>_Should_<Behavior>_When_<Condition>` |
| 2 | AAA 结构（given / when / then） |
| 3 | 用 `data-testid` 不用 class 选择器 |
| 4 | Mock 用 MSW（拦截 API），不在组件内 mock fetch |
| 5 | Pinia 测试用 `setActivePinia(createPinia())` 隔离 |
| 6 | 异步测试用 `await` + `flushPromises` |
| 7 | 时间相关测试用 `vi.useFakeTimers` |
| 8 | 覆盖度门槛配置（≥ 80% 行） |
| 9 | 不测实现细节，测可见行为 |
| 10 | Snapshot 谨慎使用（仅稳定组件） |

## 附录 B：常用 helper

```ts
// test-utils/helpers.ts
import { mount, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': {} }
})

export function mountWithPlugins<T>(component: any, options: any = {}): VueWrapper<T> {
  setActivePinia(createPinia())
  const router = createRouter({ history: createMemoryHistory(), routes: [] })

  return mount(component, {
    global: {
      plugins: [i18n, router],
      ...options.global
    },
    ...options
  })
}

export async function flushPromises() {
  await new Promise(resolve => setTimeout(resolve, 0))
}
```
