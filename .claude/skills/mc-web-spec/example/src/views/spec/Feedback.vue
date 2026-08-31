<script setup lang="ts">
// 规范 · 反馈 — 项目级通知 (notification) 规范.
// 基于 Element Plus ElNotification, 位置 top-right, 10 秒自动关闭, 5 种类型 + 每种独立 icon.
//
// 主题对齐 WorkSection 的视觉壳 (白底 + 边框 + 阴影 + 圆角),
// 不沿用 EP 默认灰底. 颜色 token 走 EP --el-color-{primary,success,warning,info,danger},
// 切 theme/brand 时通知自动跟随.
//
// Dialog / Drawer 演示用 @/components/common/AppDialog + AppDrawer 组件,
// 它们封装了: 拖动 / 可调大小 / 视觉壳 / 移动端触摸. 这里只展示用法.
import { h, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElNotification } from 'element-plus'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'
import AppDialog from '@/components/sdk/common/AppDialog.vue'
import AppDrawer from '@/components/sdk/common/AppDrawer.vue'

const { t } = useI18n()

// 弹出框 + 抽屉演示状态
const dialogVisible = ref(false)
const drawerVisible = ref(false)

// 5 种通知类型, 对应 EP ElNotification 类型 + 统一 RemixIcon 图标 (覆盖 EP 默认小图标)
// elButtonType: 演示按钮 ElButton.type (不接受 'error', 需映射为 'danger')
const types = [
  { key: 'primary', epType: '' as const,         icon: 'ri-notification-3-line',        elButtonType: 'primary' },
  { key: 'success', epType: 'success' as const,  icon: 'ri-checkbox-circle-line',        elButtonType: 'success' },
  { key: 'warning', epType: 'warning' as const,  icon: 'ri-error-warning-line',         elButtonType: 'warning' },
  { key: 'info',    epType: 'info' as const,     icon: 'ri-information-line',           elButtonType: 'info' },
  { key: 'error',   epType: 'error' as const,    icon: 'ri-close-circle-line',          elButtonType: 'danger' },
] as const

function triggerNotify(key: typeof types[number]['key']) {
  const cfg = types.find((x) => x.key === key)!
  ElNotification({
    title: t(`spec.feedback.notify.demo.titles.${key}`),
    message: t(`spec.feedback.notify.demo.messages.${key}`),
    type: cfg.epType || undefined,
    position: 'top-right',
    duration: 10000,  // 10 秒自动关闭
    customClass: `app-notification app-notification--${key}`,
    icon: () => h('i', { class: `${cfg.icon} app-notification__icon` }),
  })
}

const snippetNotification = `import { ElNotification } from 'element-plus'

ElNotification({
  title: '操作成功',
  message: '数据已保存到服务器',
  type: 'success',                  // 'success' | 'warning' | 'info' | 'error' | ''
  position: 'top-right',
  duration: 10000,
  customClass: 'app-notification',  // 走项目主题 CSS, 对齐 WorkSection
  icon: () => h('i', { class: 'ri-checkbox-circle-line app-notification__icon' }),
})`

const snippetDialog = `<script setup>
import AppDialog from '@/components/sdk/common/AppDialog.vue'
const show = ref(false)
<\/script>

<template>
  <AppDialog
    v-model:visible="show"
    title="编辑资料"
    :resizable="true"          <!-- 右下角 12px 斜线手柄, 移动端 44px 触摸区 -->
    draggable                  <!-- 头部可拖动, 鼠标变 move 光标 -->
    @resize="(w, h) => console.log('resized', w, h)"
  >
    <p>内容...</p>
    <template #footer>
      <el-button @click="show = false">取消</el-button>
      <el-button type="primary" @click="show = false">确认</el-button>
    </template>
  </AppDialog>
</template>`

const snippetDrawer = `<script setup>
import AppDrawer from '@/components/sdk/common/AppDrawer.vue'
const show = ref(false)
<\/script>

<template>
  <AppDrawer
    v-model:visible="show"
    title="外观设置"
    direction="rtl"             <!-- 默认右侧滑出 -->
    :resizable="true"           <!-- 左侧 1px 拖拽条 (EP 内置) -->
    @resize="size => console.log('new width', size)"
  >
    <p>抽屉内容 (表单/设置等)...</p>
    <template #footer>
      <el-button @click="show = false">关闭</el-button>
    </template>
  </AppDrawer>
</template>`
</script>

<template>
  <div class="app-page spec-feedback-page">
    <HeaderSection
      :title="t('spec.feedback.subtitle').split('·')[0].trim()"
      :subtitle="t('spec.feedback.subtitle')"
    />

    <!-- 通知规范 -->
    <TitledSection
      :title="t('spec.feedback.notify.title')"
      :description="t('spec.feedback.notify.desc')"
    >
      <!-- 设计原则 -->
      <RowCard :title="t('spec.feedback.notify.principles.title')">
        <div class="principles-list">
          <div class="principle">
            <i class="ri-arrow-up-right-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.notify.principles.position') }}</h5>
              <p class="principle__desc">默认 <code>top-right</code>, 不阻塞主操作. 跟现代 B 端 (Apple/Google/Microsoft) 一致. 不建议 bottom-right (易被操作栏遮挡).</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-time-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.notify.principles.autoclose') }}</h5>
              <p class="principle__desc">默认 3000ms 自动关闭, 错误类 5000ms 留更多阅读时间. 关键操作 (支付 / 删除) 用 <code>duration: 0</code> 强制手动关闭.</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-shape-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.notify.principles.types') }}</h5>
              <p class="principle__desc">5 种类型, 颜色 token 走 EP <code>--el-color-{primary,success,warning,info,danger}</code>, 切主题/品牌自动跟随. 不绑品牌色, 不绑主色硬编码.</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-palette-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.notify.principles.theme') }}</h5>
              <p class="principle__desc">视觉壳对齐 WorkSection: 白底 + 1px 边框 + 圆角 + 阴影 (同一组 <code>--el-bg-color / --el-border-color-extra-light / --app-radius-card / --app-shadow-md</code>), 不沿用 EP 默认灰底.</p>
            </div>
          </div>
        </div>
      </RowCard>

      <!-- 5 种类型演示 -->
      <RowCard :title="t('spec.feedback.notify.demo.title')">
        <p class="card-desc">{{ t('spec.feedback.notify.demo.desc') }}</p>
        <div class="notify-demo-row">
          <el-button
            v-for="t2 in types"
            :key="t2.key"
            :type="t2.elButtonType"
            @click="triggerNotify(t2.key)"
          >
            <i :class="t2.icon" />
            {{ t(`spec.feedback.notify.demo.labels.${t2.key}`) }}
          </el-button>
        </div>
      </RowCard>

      <!-- 主题 — 跟 WorkSection 视觉壳对比 -->
      <RowCard :title="t('spec.feedback.notify.theme.title')">
        <p class="card-desc">{{ t('spec.feedback.notify.theme.desc') }}</p>
        <div class="theme-compare">
          <!-- 视觉壳 1: WorkSection -->
          <div class="theme-compare__item">
            <div class="theme-compare__caption">{{ t('spec.feedback.notify.theme.worksection') }}</div>
            <div class="theme-compare__panel worksection-demo">
              <div class="worksection-demo__header">
                <i class="ri-palette-line" />
                <span>WorkSection 视觉</span>
              </div>
              <div class="worksection-demo__body">白底 + 边框 + 阴影 + 圆角</div>
            </div>
          </div>
          <!-- 视觉壳 2: 通知 (用我们主题) -->
          <div class="theme-compare__item">
            <div class="theme-compare__caption">{{ t('spec.feedback.notify.theme.notify') }}</div>
            <div class="theme-compare__panel app-notification app-notification--preview">
              <i class="ri-checkbox-circle-line app-notification__icon" />
              <div class="app-notification__body">
                <div class="app-notification__title">操作成功</div>
                <div class="app-notification__content">与 WorkSection 共享同一组 CSS 变量, 视觉一致</div>
              </div>
            </div>
          </div>
        </div>
      </RowCard>

      <!-- 用法 -->
      <RowCard :title="t('spec.feedback.notify.usage.title')">
        <p class="card-desc">{{ t('spec.feedback.notify.usage.desc') }}</p>
        <pre class="snippet">{{ snippetNotification }}</pre>
      </RowCard>
    </TitledSection>

    <!-- 弹出框规范 — 样式复用 WorkSection + 可拖动 + 背景高斯模糊 -->
    <TitledSection
      :title="t('spec.feedback.dialog.title')"
      :description="t('spec.feedback.dialog.desc')"
    >
      <!-- 设计原则 -->
      <RowCard :title="t('spec.feedback.dialog.principles.title')">
        <div class="principles-list">
          <div class="principle">
            <i class="ri-drag-move-2-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.dialog.principles.draggable') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.dialog.principles.draggableDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-blur-off-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.dialog.principles.blur') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.dialog.principles.blurDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-palette-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.dialog.principles.theme') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.dialog.principles.themeDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-resize principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.dialog.principles.resizable') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.dialog.principles.resizableDesc') }}</p>
            </div>
          </div>
        </div>
      </RowCard>

      <!-- 现场演示 -->
      <RowCard :title="t('spec.feedback.dialog.demo.title')">
        <p class="card-desc">{{ t('spec.feedback.dialog.demo.desc') }}</p>
        <el-button type="primary" @click="dialogVisible = true">
          <i class="ri-window-2-line" />
          {{ t('spec.feedback.dialog.demo.open') }}
        </el-button>

        <AppDialog
          v-model:visible="dialogVisible"
          :title="t('spec.feedback.dialog.demo.dialogTitle')"
        >
          <div class="app-dialog__body">
            <p class="app-dialog__lead">{{ t('spec.feedback.dialog.demo.lead') }}</p>
            <el-form label-width="100px" size="default">
              <el-form-item :label="t('spec.feedback.dialog.demo.form.name')">
                <el-input model-value="陈晓宇" />
              </el-form-item>
              <el-form-item :label="t('spec.feedback.dialog.demo.form.email')">
                <el-input model-value="chenxiaoyu@company.com" />
              </el-form-item>
              <el-form-item :label="t('spec.feedback.dialog.demo.form.note')">
                <el-input type="textarea" :rows="3" :model-value="t('spec.feedback.dialog.demo.form.noteVal')" />
              </el-form-item>
            </el-form>
            <p class="app-dialog__hint">
              <i class="ri-drag-move-2-line" />
              {{ t('spec.feedback.dialog.demo.hint') }}
            </p>
            <p class="app-dialog__hint app-dialog__hint--resize">
              <i class="ri-resize" />
              {{ t('spec.feedback.dialog.demo.hintResize') }}
            </p>
          </div>
          <template #footer>
            <el-button @click="dialogVisible = false">{{ t('spec.feedback.dialog.demo.cancel') }}</el-button>
            <el-button type="primary" @click="dialogVisible = false">{{ t('spec.feedback.dialog.demo.confirm') }}</el-button>
          </template>
        </AppDialog>
      </RowCard>

      <!-- 用法 -->
      <RowCard :title="t('spec.feedback.dialog.usage.title')">
        <p class="card-desc">{{ t('spec.feedback.dialog.usage.desc') }}</p>
        <pre class="snippet">{{ snippetDialog }}</pre>
      </RowCard>
    </TitledSection>

    <!-- 抽屉规范 — 从右侧滑出, 跟 dialog 同组视觉 -->
    <TitledSection
      :title="t('spec.feedback.drawer.title')"
      :description="t('spec.feedback.drawer.desc')"
    >
      <!-- 设计原则 -->
      <RowCard :title="t('spec.feedback.drawer.principles.title')">
        <div class="principles-list">
          <div class="principle">
            <i class="ri-sidebar-unfold-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.drawer.principles.position') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.drawer.principles.positionDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-blur-off-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.drawer.principles.blur') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.drawer.principles.blurDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-palette-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.drawer.principles.theme') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.drawer.principles.themeDesc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-resize principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.feedback.drawer.principles.resizable') }}</h5>
              <p class="principle__desc">{{ t('spec.feedback.drawer.principles.resizableDesc') }}</p>
            </div>
          </div>
        </div>
      </RowCard>

      <!-- 现场演示 -->
      <RowCard :title="t('spec.feedback.drawer.demo.title')">
        <p class="card-desc">{{ t('spec.feedback.drawer.demo.desc') }}</p>
        <el-button type="primary" @click="drawerVisible = true">
          <i class="ri-sidebar-unfold-line" />
          {{ t('spec.feedback.drawer.demo.open') }}
        </el-button>

        <AppDrawer
          v-model:visible="drawerVisible"
          :title="t('spec.feedback.drawer.demo.drawerTitle')"
        >
          <div class="app-drawer__body">
            <p class="app-drawer__lead">{{ t('spec.feedback.drawer.demo.lead') }}</p>
            <el-form label-width="100px" size="default">
              <el-form-item :label="t('spec.feedback.drawer.demo.form.theme')">
                <el-select :model-value="t('spec.feedback.drawer.demo.form.themeVal')" disabled>
                  <el-option :label="t('spec.feedback.drawer.demo.form.themeVal')" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('spec.feedback.drawer.demo.form.brand')">
                <el-select :model-value="t('spec.feedback.drawer.demo.form.brandVal')" disabled>
                  <el-option :label="t('spec.feedback.drawer.demo.form.brandVal')" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('spec.feedback.drawer.demo.form.lang')">
                <el-select :model-value="t('spec.feedback.drawer.demo.form.langVal')" disabled>
                  <el-option :label="t('spec.feedback.drawer.demo.form.langVal')" />
                </el-select>
              </el-form-item>
            </el-form>
          </div>
          <template #footer>
            <el-button @click="drawerVisible = false">{{ t('spec.feedback.drawer.demo.cancel') }}</el-button>
          </template>
        </AppDrawer>
      </RowCard>

      <!-- 用法 -->
      <RowCard :title="t('spec.feedback.drawer.usage.title')">
        <p class="card-desc">{{ t('spec.feedback.drawer.usage.desc') }}</p>
        <pre class="snippet">{{ snippetDrawer }}</pre>
      </RowCard>
    </TitledSection>
  </div>
</template>

<style scoped>
/* 通知视觉壳 (跟 WorkSection 一致): 白底 + 边框 + 阴影 + 圆角.
   写成全局 :deep() 因为 ElNotification 渲染在 body 末尾, 不在 scoped 树内. */
:deep(.app-notification) {
  background: var(--el-bg-color) !important;
  border: 1px solid var(--el-border-color-extra-light) !important;
  border-radius: var(--app-radius-card) !important;
  box-shadow: var(--app-shadow-md) !important;
  padding: 14px 18px !important;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 280px;
  max-width: 360px;
}
/* EP 把 customClass 挂在 .el-notification 上, 而我们的 .app-notification__icon
   渲染在 .el-notification__icon 容器里 — 用 :deep 抓两层 */
:deep(.app-notification .el-notification__icon) {
  /* 隐藏 EP 默认小图标容器 (我们用 customClass modifier 设的自定义 i 替代) */
  display: none !important;
}
:deep(.app-notification .el-notification__title) {
  font-weight: 600;
  color: var(--app-text);
  font-size: 14px;
  margin-bottom: 4px;
}
:deep(.app-notification .el-notification__content) {
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
  padding: 0;
}
/* 在本组件预览 (theme-compare) 里手动渲染 icon, 因为没有 ::before 触发器 */
.app-notification__icon {
  font-size: 24px;
  flex-shrink: 0;
  margin-top: 2px;
}
.app-notification--primary  .app-notification__icon,
.app-notification--preview  .app-notification__icon { color: var(--el-color-primary); }
.app-notification--success  .app-notification__icon { color: var(--el-color-success); }
.app-notification--warning  .app-notification__icon { color: var(--el-color-warning); }
.app-notification--info     .app-notification__icon { color: var(--el-color-info); }
.app-notification--error    .app-notification__icon { color: var(--el-color-danger); }
.app-notification__body { flex: 1; min-width: 0; }
.app-notification__title {
  font-weight: 600;
  color: var(--app-text);
  font-size: 14px;
  margin-bottom: 4px;
}
.app-notification__content {
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

/* 设计原则列表 */
.principles-list { display: flex; flex-direction: column; gap: 16px; }
.principle { display: flex; gap: 12px; align-items: flex-start; }
.principle__icon {
  font-size: 20px;
  color: var(--app-primary);
  flex-shrink: 0;
  margin-top: 2px;
}
.principle__body { flex: 1; min-width: 0; }
.principle__title { margin: 0 0 4px; font-size: 13px; font-weight: 600; color: var(--app-text); }
.principle__desc { margin: 0; color: var(--app-text-secondary); font-size: 12px; line-height: 1.7; }
.principle__desc code {
  background: var(--app-bg-muted);
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 0.9em;
}

/* 5 个按钮一行 */
.notify-demo-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 主题对比 (WorkSection vs 通知) */
.theme-compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 768px) { .theme-compare { grid-template-columns: 1fr; } }
.theme-compare__item { display: flex; flex-direction: column; gap: 8px; }
.theme-compare__caption {
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.theme-compare__panel {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-card);
  box-shadow: var(--app-shadow-md);
  padding: 14px 18px;
}
.worksection-demo__header {
  display: flex; align-items: center; gap: 8px;
  font-weight: 600; color: var(--app-text); font-size: 14px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  padding-bottom: 10px;
  margin-bottom: 10px;
}
.worksection-demo__body { color: var(--app-text-secondary); font-size: 13px; }
.worksection-demo__header i { color: var(--app-primary); }

/* 代码片段 (复用 Material.vue 同款 .snippet) */
.snippet {
  margin: 0;
  padding: 12px 14px;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-md);
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text);
  overflow-x: auto;
  white-space: pre-wrap;
}

/* 弹出框 / 抽屉的视觉壳 / 触摸区 / 移动端 CSS 全部封装到 @/components/common/AppDialog + AppDrawer.
   这里只留 demo body 内的辅助样式. */

/* 弹出框 body 内容布局 */
.app-dialog__body { font-size: 13px; }
.app-dialog__lead {
  margin: 0 0 16px;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.app-dialog__hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 8px 0 0;
  padding: 6px 12px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border-radius: var(--app-radius-sm);
  font-size: 12px;
}
.app-dialog__hint i { font-size: 14px; }
.app-dialog__hint--resize { margin-top: 6px; background: var(--el-color-info-light-9); color: var(--el-color-info); }

/* 抽屉 demo body 内的辅助样式 */
.app-drawer__body { font-size: 13px; }
.app-drawer__lead {
  margin: 0 0 16px;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
</style>
