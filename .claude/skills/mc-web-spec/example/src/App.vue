<script setup lang="ts">
// App.vue — host 业务侧. 接入 SDK 组件 + 用 slot 填入所有"内容".
//   • AppHeader (4-slot 壳) → 塞 brand / search / actions / user
//   • AppSidebar (5-slot 壳 + 拖拽) → 塞 nav 内容 (<el-menu> + <el-sub-menu> / <el-menu-item>)
//     通过 AppNavGroup (薄壳包 <el-menu>)
//   • AppMain (壳 + 默认 <router-view>) → 无需填
//
// 行为处理: 搜索 / 用户菜单命令 / sidebar 折叠 (含 per-viewport override)
// 路由: select 后 router.push (避免重复 push), 移动端切换路由自动关 drawer.
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElConfigProvider } from 'element-plus'
import enUS from 'element-plus/es/locale/lang/en'
import zhCN from 'element-plus/es/locale/lang/zh-cn'
import { useI18n } from 'vue-i18n'
import { usePreferenceStore } from '@/stores/preference'
import { useUserStore } from '@/stores/user'
import { useResponsive, watchViewport } from '@/composables/useResponsive'
import AppHeader from '@/components/sdk/layout/AppHeader.vue'
import AppSidebar from '@/components/sdk/layout/AppSidebar.vue'
import AppMain from '@/components/sdk/layout/AppMain.vue'
import AppNavGroup from '@/components/sdk/layout/AppNavGroup.vue'
import AppearanceDrawer from '@/views/appearance/Index.vue'

const preference = usePreferenceStore()
const user = useUserStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { isMobile, viewport } = useResponsive()

const elLocale = computed(() => (preference.locale === 'en-US' ? enUS : zhCN))

// Per-viewport collapsed override: tablet 默认真塌 (icon mode), desktop 默认展开.
// 用户点 toggle 按钮 → 翻转当前 viewport 的 override, 跨 viewport 切换时清掉
// 旧 viewport 的 override (新 viewport 走默认).
const userToggleViewport = ref<'desktop' | 'tablet' | null>(null)
const tabletOverride = ref<boolean | null>(null)
const desktopOverride = ref<boolean | null>(null)

const sidebarCollapsedForCurrent = computed(() => {
  if (viewport.value === 'tablet') {
    return tabletOverride.value !== null ? tabletOverride.value : true
  }
  return desktopOverride.value !== null ? desktopOverride.value : false
})

const mobileDrawerOpen = ref(false)
const sidebarDrawerVisible = computed({
  get: () => isMobile.value && mobileDrawerOpen.value,
  set: (v: boolean) => {
    if (isMobile.value) mobileDrawerOpen.value = v
  },
})

function onToggleSidebar() {
  if (isMobile.value) {
    mobileDrawerOpen.value = !mobileDrawerOpen.value
    return
  }
  userToggleViewport.value = viewport.value
  const currentCollapsed = sidebarCollapsedForCurrent.value
  if (viewport.value === 'tablet') {
    tabletOverride.value = !currentCollapsed
  } else {
    desktopOverride.value = !currentCollapsed
  }
  preference.setSidebarCollapsed(!currentCollapsed)
}

// ---- Header 行为 ----
const search = ref('')
function onSearch() {
  if (!search.value) return
  router.push({ path: '/home', query: { q: search.value } })
}
function onHeaderCommand(command: string) {
  if (command === 'logout') {
    user.setCurrent(null)
    router.push('/home')
  } else if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'settings') {
    router.push('/settings')
  } else if (command === 'appearance') {
    preference.setShowAppearance(true)
  }
}

// ---- Sidebar 行为 ----
function onSelectSidebar(path: string) {
  if (route.path === path) return
  router.push(path)
}

// Sidebar 宽度 v-model 桥接到 preference store
const sidebarWidth = computed({
  get: () => preference.sidebarWidth,
  set: (v: number) => preference.setSidebarWidth(v),
})
const sidebarCollapsed = computed({
  get: () => preference.sidebarCollapsed,
  set: (v: boolean) => preference.setSidebarCollapsed(v),
})

// Spec sub-menu 自动展开: 路由在 /spec/* 时展开 'spec' 分组
const openeds = computed<string[]>(() =>
  route.path.startsWith('/spec/') ? ['spec'] : [],
)

// ---- Lifecycle ----
onMounted(() => {
  preference.init()
})

watch(
  () => preference.locale,
  (next) => {
    document.documentElement.setAttribute('lang', next)
  },
  { immediate: true },
)

watch(
  () => route.fullPath,
  () => {
    if (isMobile.value) mobileDrawerOpen.value = false
  },
)

watchViewport((v) => {
  if (!isMobile.value) mobileDrawerOpen.value = false
  if (v === 'tablet') desktopOverride.value = null
  else if (v === 'desktop') tabletOverride.value = null
  else {
    tabletOverride.value = null
    desktopOverride.value = null
  }
})
</script>

<template>
  <el-config-provider :locale="elLocale">
    <div class="app-shell">
      <AppHeader @toggle-sidebar="onToggleSidebar">
        <template #brand>
          <div class="app-logo" @click="router.push('/home')">
            <i class="ri-book-open-line" />
            <div class="app-logo__text">
              <strong>{{ t('app.title') }}</strong>
              <small>{{ t('app.subtitle') }}</small>
            </div>
          </div>
        </template>
        <template #search>
          <el-input
            v-model="search"
            :placeholder="t('header.search')"
            clearable
            @keyup.enter="onSearch"
          >
            <template #prefix>
              <i class="ri-search-line" />
            </template>
          </el-input>
        </template>
        <template #actions>
          <el-badge is-dot class="app-header__badge">
            <i class="ri-notification-3-line app-header__notif-icon" />
          </el-badge>
        </template>
        <template #user>
          <el-dropdown trigger="click" @command="onHeaderCommand">
            <div class="app-header__user">
              <el-avatar :size="32" src="/images/avatars/1.png" />
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <i class="ri-user-line" />
                  <span>{{ t('header.user') }}</span>
                </el-dropdown-item>
                <el-dropdown-item command="settings">
                  <el-icon><i class="ri-settings-3-line" /></el-icon>
                  <span>{{ t('header.settings') }}</span>
                </el-dropdown-item>
                <el-dropdown-item command="appearance">
                  <i class="ri-palette-line" />
                  <span>{{ t('common.appearance') }}</span>
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <i class="ri-logout-box-r-line" />
                  <span>{{ t('header.logout') }}</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </AppHeader>

      <div class="app-body">
        <AppSidebar
          v-if="!isMobile"
          :force-collapsed="sidebarCollapsedForCurrent"
          :collapsed="sidebarCollapsed"
          :width="sidebarWidth"
          @update:collapsed="(v: boolean) => (sidebarCollapsed = v)"
          @update:width="(v: number) => (sidebarWidth = v)"
          @toggle-sidebar="onToggleSidebar"
          @select="onSelectSidebar"
        >
          <AppNavGroup
            :active-path="route.path"
            :openeds="openeds"
            :collapse="sidebarCollapsedForCurrent"
            @update:openeds="(v: string[]) => (openeds = v)"
            @select="onSelectSidebar"
          >
            <!-- 首页 (standalone, 加 el-tooltip 让折叠态 hover 时显示 label) -->
            <el-tooltip
              :content="t('nav.pages.home')"
              placement="right"
              :disabled="!sidebarCollapsedForCurrent"
            >
              <el-menu-item index="/home" class="nav-item--green">
                <el-icon><i class="ri-home-2-line" /></el-icon>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.home') }}</span>
                </template>
              </el-menu-item>
            </el-tooltip>

            <!-- 规范 (6 个 spec 页面: 一/二/三/四/五/六)
                 popper-class 让折叠态弹层应用项目样式 (圆角/阴影/边框) -->
            <el-sub-menu
              index="spec"
              :title="t('nav.group.spec', '规范')"
              popper-class="app-sidebar-popper"
            >
              <template #title>
                <el-icon><i class="ri-flag-line" /></el-icon>
                <span class="app-menu-item__label">{{ t('nav.group.spec', '规范') }}</span>
              </template>
              <el-menu-item index="/spec/general">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-general', '一、总则') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/spec/layout">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-layout', '二、布局') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/spec/style">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-style', '三、样式') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/spec/forms">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-forms', '四、表单') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/spec/feedback">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-feedback', '五、反馈') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/spec/material">
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-material', '六、素材') }}</span>
                </template>
              </el-menu-item>
            </el-sub-menu>

            <!-- 演示 3 级嵌套菜单: 演示 · 一级 → 演示 · 二级 → (三级1 | 三级2)
                 展示 el-menu 三层结构的正确写法 (子菜单里再嵌 el-sub-menu) -->
            <el-sub-menu
              index="demo-l1"
              :title="t('nav.pages.spec-demo-l1', '演示 · 一级')"
              popper-class="app-sidebar-popper"
            >
              <template #title>
                <el-icon><i class="ri-stack-line" /></el-icon>
                <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l1', '演示 · 一级') }}</span>
              </template>
              <el-sub-menu
                index="demo-l2"
                :title="t('nav.pages.spec-demo-l2', '演示 · 二级')"
                popper-class="app-sidebar-popper"
              >
                <template #title>
                  <el-icon><i class="ri-folder-2-line" /></el-icon>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l2', '演示 · 二级') }}</span>
                </template>
                <el-sub-menu index="demo-l3-1" :title="t('nav.pages.spec-demo-l3-1', '演示 · 三级1')" popper-class="app-sidebar-popper">
                  <template #title>
                    <el-icon><i class="ri-file-list-line" /></el-icon>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l3-1', '演示 · 三级1') }}</span>
                  </template>
                  <el-menu-item index="/demo-l4-1">
                    <el-icon><i class="ri-checkbox-blank-line" /></el-icon>
                    <template #title>
                      <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l4-1', '演示 · 四级1') }}</span>
                    </template>
                  </el-menu-item>
                  <el-menu-item index="/demo-l4-2">
                    <el-icon><i class="ri-checkbox-blank-line" /></el-icon>
                    <template #title>
                      <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l4-2', '演示 · 四级2') }}</span>
                    </template>
                  </el-menu-item>
                </el-sub-menu>
                <el-menu-item index="/demo-l3-2">
                  <el-icon><i class="ri-file-list-2-line" /></el-icon>
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l3-2', '演示 · 三级2') }}</span>
                  </template>
                </el-menu-item>
              </el-sub-menu>
            </el-sub-menu>

            <!-- 系统分组: 小灰字 "系统" 标签 (展开态显示, 折叠态隐藏) + 设置 (红点) + 个人中心 (99 角标) -->
            <li
              v-show="!sidebarCollapsedForCurrent"
              class="app-sidebar__divider"
              aria-hidden="true"
            >
              <span class="app-sidebar__divider-label">{{ t('nav.group.system') }}</span>
            </li>

            <!-- 系统分组: 设置 (红点) + 个人中心 (99 角标), 加 el-tooltip 支持折叠态 -->
            <el-tooltip
              :content="t('nav.group.settings')"
              placement="right"
              :disabled="!sidebarCollapsedForCurrent"
            >
              <el-menu-item index="/settings" class="is-group-divider nav-item--gold">
                <el-badge is-dot class="app-menu-item__icon-badge">
                  <el-icon><i class="ri-settings-3-line" /></el-icon>
                </el-badge>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.group.settings') }}</span>
                </template>
              </el-menu-item>
            </el-tooltip>
            <el-tooltip
              :content="t('nav.group.profile')"
              placement="right"
              :disabled="!sidebarCollapsedForCurrent"
            >
              <el-menu-item index="/profile" class="nav-item--brown">
                <el-badge :value="99" class="app-menu-item__icon-badge">
                  <el-icon><i class="ri-user-3-line" /></el-icon>
                </el-badge>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.group.profile') }}</span>
                </template>
              </el-menu-item>
            </el-tooltip>
          </AppNavGroup>
        </AppSidebar>

        <el-drawer
          v-else
          v-model="sidebarDrawerVisible"
          direction="ltr"
          :with-header="false"
          size="240px"
          modal-class="app-sidebar-drawer-modal"
        >
          <AppSidebar
            :force-collapsed="false"
            :collapsed="sidebarCollapsed"
            :width="sidebarWidth"
            :enable-drag="false"
            @update:collapsed="(v: boolean) => (sidebarCollapsed = v)"
            @update:width="(v: number) => (sidebarWidth = v)"
            @select="onSelectSidebar"
          >
            <AppNavGroup
              :active-path="route.path"
              :openeds="openeds"
              :collapse="false"
              @update:openeds="(v: string[]) => (openeds = v)"
              @select="onSelectSidebar"
            >
              <el-menu-item index="/home">
                <el-icon><i class="ri-home-2-line" /></el-icon>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.pages.home') }}</span>
                </template>
              </el-menu-item>
              <el-sub-menu index="spec" :title="t('nav.group.spec', '规范')">
                <template #title>
                  <el-icon><i class="ri-flag-line" /></el-icon>
                  <span class="app-menu-item__label">{{ t('nav.group.spec', '规范') }}</span>
                </template>
                <el-menu-item index="/spec/general">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-general', '一、总则') }}</span>
                  </template>
                </el-menu-item>
                <el-menu-item index="/spec/layout">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-layout', '二、布局') }}</span>
                  </template>
                </el-menu-item>
                <el-menu-item index="/spec/style">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-style', '三、样式') }}</span>
                  </template>
                </el-menu-item>
                <el-menu-item index="/spec/forms">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-forms', '四、表单') }}</span>
                  </template>
                </el-menu-item>
                <el-menu-item index="/spec/feedback">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-feedback', '五、反馈') }}</span>
                  </template>
                </el-menu-item>
                <el-menu-item index="/spec/material">
                  <template #title>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-material', '六、素材') }}</span>
                  </template>
                </el-menu-item>
              </el-sub-menu>
              <el-sub-menu index="demo-l1" :title="t('nav.pages.spec-demo-l1', '演示 · 一级')">
                <template #title>
                  <el-icon><i class="ri-stack-line" /></el-icon>
                  <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l1', '演示 · 一级') }}</span>
                </template>
                <el-sub-menu index="demo-l2" :title="t('nav.pages.spec-demo-l2', '演示 · 二级')">
                  <template #title>
                    <el-icon><i class="ri-folder-2-line" /></el-icon>
                    <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l2', '演示 · 二级') }}</span>
                  </template>
                  <el-sub-menu index="demo-l3-1" :title="t('nav.pages.spec-demo-l3-1', '演示 · 三级1')">
                    <template #title>
                      <el-icon><i class="ri-file-list-line" /></el-icon>
                      <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l3-1', '演示 · 三级1') }}</span>
                    </template>
                    <el-menu-item index="/demo-l4-1">
                      <el-icon><i class="ri-checkbox-blank-line" /></el-icon>
                      <template #title>
                        <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l4-1', '演示 · 四级1') }}</span>
                      </template>
                    </el-menu-item>
                    <el-menu-item index="/demo-l4-2">
                      <el-icon><i class="ri-checkbox-blank-line" /></el-icon>
                      <template #title>
                        <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l4-2', '演示 · 四级2') }}</span>
                      </template>
                    </el-menu-item>
                  </el-sub-menu>
                  <el-menu-item index="/demo-l3-2">
                    <el-icon><i class="ri-file-list-2-line" /></el-icon>
                    <template #title>
                      <span class="app-menu-item__label">{{ t('nav.pages.spec-demo-l3-2', '演示 · 三级2') }}</span>
                    </template>
                  </el-menu-item>
                </el-sub-menu>
              </el-sub-menu>
              <li class="app-sidebar__divider" aria-hidden="true">
                <span class="app-sidebar__divider-label">{{ t('nav.group.system') }}</span>
              </li>
              <el-menu-item index="/settings">
                <el-badge is-dot class="app-menu-item__icon-badge">
                  <el-icon><i class="ri-settings-3-line" /></el-icon>
                </el-badge>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.group.settings') }}</span>
                </template>
              </el-menu-item>
              <el-menu-item index="/profile">
                <el-badge :value="99" class="app-menu-item__icon-badge">
                  <el-icon><i class="ri-user-3-line" /></el-icon>
                </el-badge>
                <template #title>
                  <span class="app-menu-item__label">{{ t('nav.group.profile') }}</span>
                </template>
              </el-menu-item>
            </AppNavGroup>
          </AppSidebar>
        </el-drawer>

        <AppMain />
      </div>
    </div>

    <AppearanceDrawer v-model="preference.showAppearance" />
  </el-config-provider>
</template>

<style>
@import 'remixicon/fonts/remixicon.css';
@import 'element-plus/dist/index.css';
@import '@/styles/index.scss';

.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.app-body {
  display: grid;
  grid-template-columns: auto 1fr;
  flex: 1;
  min-height: 0;
  min-width: 0;
}

.app-body > .app-sidebar {
  grid-column: 1;
}

.app-body > main.app-main {
  grid-column: 2;
  min-width: 0;
  width: 100%;
}

/* ============================================================
   Host 业务侧样式 (SDK 不该有): 品牌 / 头像 / 红点 / 角标 颜色
   ============================================================ */
.app-logo {
  display: flex;
  align-items: center;
  gap: var(--app-header-gap-logo, 10px);
  cursor: pointer;
  user-select: none;
  color: var(--app-text);
}
.app-logo i {
  font-size: var(--app-header-logo-size, 24px);
  color: var(--app-primary);
}
.app-logo__text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.app-logo__text small {
  font-size: 11px;
  color: var(--app-text-secondary);
}

.app-header__badge {
  display: inline-flex;
  align-items: center;
  padding: 8px;
  cursor: pointer;
  border-radius: var(--app-radius-md, 8px);
  transition: background 0.15s;
}
.app-header__badge:hover { background: var(--el-fill-color-light); }
.app-header__notif-icon { font-size: 20px; color: var(--app-text); }
.app-header__badge :deep(.el-badge__content.is-dot) {
  background: var(--el-color-danger) !important;
  margin-right: 10px;
  margin-top: 10px;
}

.app-header__user {
  display: flex;
  align-items: center;
  padding: 4px;
  cursor: pointer;
  border-radius: var(--app-radius-md, 8px);
  transition: background 0.15s;
}
.app-header__user:hover { background: var(--el-fill-color-light); }

.app-menu-item__icon-badge {
  display: inline-flex;
  vertical-align: middle;
}
.app-menu-item__icon-badge :deep(.el-badge__content.is-dot) {
  right: 8px;
  top: 0;
}
.app-menu-item__icon-badge :deep(.el-badge__content:not(.is-dot)) {
  right: 16px;
  top: -4px;
  font-size: 10px;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  padding: 0 5px;
}

/* Group-title separator between content groups and the system zone. */
.app-sidebar__divider {
  list-style: none;
  margin: 10px 16px 4px;
  padding: 0;
  height: auto;
}
.app-sidebar__divider-label {
  display: block;
  font-size: 11px;
  font-weight: 900;
  color: var(--app-text-tertiary);
  letter-spacing: 0.5px;
  text-transform: uppercase;
  padding: 0;
}

@media (max-width: 768px) {
  .app-logo__text { display: none; }
}
</style>
