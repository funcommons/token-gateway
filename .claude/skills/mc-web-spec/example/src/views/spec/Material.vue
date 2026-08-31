<script setup lang="ts">
// 规范 · 素材 — 介绍项目级共用素材: 4.1 空态 + 4.2 加载.
// 原独立 /spec/loading 页面已合并到这里.
//
// 空态方案要点 (与 _empty_<brand>.svg 文件命名约定一致):
//   1. 一图通吃: 一个空态 SVG 应对所有场景, 通过 description 文本区分语义
//   2. 颜色单色: 灰轮廓 (currentColor → --app-text-tertiary), 不绑主题色也不绑品牌色, 让空态"沉"在页面里
//   3. brand fallback: useBrandEmpty 按 preference.brand 选 SVG, 缺失回退 _empty_default.svg
//   4. 无动画: 空态是静态状态指示
//
// 加载方案要点 (与 _loading_<brand>.svg 文件命名约定一致):
//   1. 每个 brand 一份 inner SVG, 放 src/assets/loadings/_loading_<brand>.svg
//   2. 缺失时回退 _loading_default.svg (EP 内置 spinner)
//   3. SVG 用 currentColor, 颜色随 --el-color-primary 自动适配 brand + theme
import { ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePreferenceStore } from '@/stores/preference'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'
import { useBrandEmpty } from '@/composables/useBrandEmpty'
import { useBrandLoading, brandLoadingService } from '@/composables/useBrandLoading'

const { t } = useI18n()
const { svg, viewBox } = useBrandEmpty()
const { svg: loadSvg, svgViewBox } = useBrandLoading()
const preference = usePreferenceStore()

// 服务式加载演示 — 按钮触发, 2s 后自动关闭
const serviceTarget = ref<HTMLElement | null>(null)
let serviceInstance: ReturnType<typeof brandLoadingService> | null = null
function triggerService() {
  if (!serviceTarget.value) return
  if (serviceInstance) serviceInstance.close()
  serviceInstance = brandLoadingService({
    target: serviceTarget.value,
    text: '加载中…',
    lock: true,
  })
  setTimeout(() => {
    serviceInstance?.close()
    serviceInstance = null
  }, 2000)
}
onUnmounted(() => serviceInstance?.close())

const snippetUsage = `<script setup>
import { useBrandEmpty } from '@/composables/useBrandEmpty'
const { svg } = useBrandEmpty()  // self-contained <svg> element
<\/script>

<template>
  <el-empty :description="t('common.empty.no-data')">
    <template #image>
      <div class="brand-empty-svg" v-html="svg" />
    </template>
  </el-empty>
</template>

<style scoped>
.brand-empty-svg { width: 120px; height: 120px; color: var(--app-text-tertiary); }
.brand-empty-svg :deep(svg) { width: 100%; height: 100%; }
</style>`

const snippetFiles = `src/assets/empties/
├── _empty_default.svg     # 通用空态 (EP 原 svg: 多页 paper 叠层, 跟 el-empty 默认一致)
└── _empty_mchuan.svg      # mchuan 品牌专属 (空纸袋 + 两根绳提手, 跟品牌"现代 B 端"调性)

# 未来加更多 brand: 复制 _empty_mchuan.svg → _empty_<brand>.svg,
# 改形状/造型. composable 按 preference.brand 自动选, 缺失回退 _empty_default.svg`

// 3 个空态场景, 同 1 SVG 配不同 i18n 描述
const cases = [
  { key: 'no-data',   icon: 'ri-database-2-line' },
  { key: 'no-record', icon: 'ri-history-line' },
  { key: 'no-found',  icon: 'ri-search-line' },
]

// 404 例: el-empty + useBrandEmpty + #default 槽放返回按钮
const snippetNotFound = `<script setup>
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBrandEmpty } from '@/composables/useBrandEmpty'

const router = useRouter()
const { t } = useI18n()
const { svg } = useBrandEmpty()
<\/script>

<template>
  <el-empty :image-size="160" :description="t('common.empty.notFound')">
    <template #image>
      <div class="not-found__svg" v-html="svg" />
    </template>
    <template #default>
      <el-button type="primary" @click="router.push('/home')">
        <i class="ri-arrow-left-line" />
        {{ t('common.empty.notFoundCta') }}
      </el-button>
    </template>
  </el-empty>
</template>`

// 加载代码片段
const snippetDirective = `<script setup>
import { useBrandLoading } from '@/composables/useBrandLoading'
const { svg: loadSvg, svgViewBox } = useBrandLoading()
<\/script>

<template>
  <div
    v-loading="loading"
    :element-loading-svg="loadSvg"
    :element-loading-svg-view-box="svgViewBox"
    element-loading-text="加载中…"
  >
    ...
  </div>
</template>`

const snippetService = `import { brandLoadingService } from '@/composables/useBrandLoading'

const instance = brandLoadingService({
  target: '.my-region',
  text: '加载中…',
  // brand: 'apple',  // 可选, 不传走当前 preference.brand
})
// ...异步完成后
instance.close()`

const snippetLoadingFiles = `src/assets/loadings/
├── _loading_default.svg     # 等同 EP 内置 (回退用)
├── _loading_apple.svg
├── _loading_microsoft.svg
├── _loading_google.svg
├── _loading_ldx2.svg
├── _loading_vonnex.svg
└── _loading_mchuan.svg`
</script>

<template>
  <div class="app-page spec-material-page">
    <HeaderSection
      :title="t('nav.pages.spec-material')"
      :subtitle="t('spec.material.subtitle')"
    />

    <!-- §4.1 空态 -->
    <TitledSection
      :title="t('spec.material.empty.title')"
      :description="t('spec.material.empty.desc')"
    >
      <!-- 设计原则 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-palette-line" />
            <span>{{ t('spec.material.empty.principles.title') }}</span>
          </h4>
        </template>
        <ul class="rule-list">
          <li>{{ t('spec.material.empty.principles.color') }}</li>
          <li>{{ t('spec.material.empty.principles.shape') }}</li>
          <li>{{ t('spec.material.empty.principles.fallback') }}</li>
          <li>{{ t('spec.material.empty.principles.noAnim') }}</li>
        </ul>
      </RowCard>

      <!-- 现场演示 — 同 SVG 配 3 不同描述 -->
      <RowCard :title="t('spec.material.empty.demo.title')">
        <p class="card-desc">{{ t('spec.material.empty.demo.desc') }}</p>
        <div class="empty-grid">
          <div v-for="c in cases" :key="c.key" class="empty-cell">
            <el-empty :image-size="120" :description="t(`common.empty.${c.key}`)">
              <template #image>
                <div class="brand-empty-svg" v-html="svg" />
              </template>
              <template #default>
                <div class="empty-cell__scene">
                  <i :class="c.icon" />
                  <span>{{ t(`spec.material.empty.demo.scenes.${c.key}`) }}</span>
                </div>
              </template>
            </el-empty>
          </div>
        </div>
      </RowCard>

      <!-- 文件命名 -->
      <RowCard :title="t('spec.material.empty.naming.title')">
        <p class="card-desc">{{ t('spec.material.empty.naming.desc') }}</p>
        <pre class="snippet">{{ snippetFiles }}</pre>
      </RowCard>

      <!-- 用法 -->
      <RowCard :title="t('spec.material.empty.usage.title')">
        <p class="card-desc">{{ t('spec.material.empty.usage.desc') }}</p>
        <pre class="snippet">{{ snippetUsage }}</pre>
      </RowCard>

      <!-- 应用: 404 页面 — 同一空态方案, 加 #default 槽放返回按钮 -->
      <RowCard :title="t('spec.material.empty.notFound.title')">
        <p class="card-desc">{{ t('spec.material.empty.notFound.desc') }}</p>
        <pre class="snippet">{{ snippetNotFound }}</pre>
        <div class="not-found-preview">
          <el-empty :image-size="120" :description="t('common.empty.notFound')">
            <template #image>
              <div class="not-found-preview__svg" v-html="svg" />
            </template>
            <template #default>
              <el-button type="primary" size="small" @click="$router.push('/home')">
                <i class="ri-arrow-left-line" />
                {{ t('common.empty.notFoundCta') }}
              </el-button>
            </template>
          </el-empty>
        </div>
      </RowCard>
    </TitledSection>

    <!-- §4.2 加载 (从原独立 /spec/loading 页面合并进来) -->
    <TitledSection
      :title="t('spec.material.loading.title')"
      :description="t('spec.material.loading.desc')"
    >
      <!-- 设计原则: 3 张 RowCard (颜色 / 形状 / 缺失文件回退) -->
      <RowCard :title="t('spec.material.loading.principles.title')">
        <div class="principles-list">
          <div class="principle">
            <i class="ri-palette-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.material.loading.principles.color.title') }}</h5>
              <p class="principle__desc">{{ t('spec.material.loading.principles.color.desc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-shape-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.material.loading.principles.shape.title') }}</h5>
              <p class="principle__desc">{{ t('spec.material.loading.principles.shape.desc') }}</p>
            </div>
          </div>
          <div class="principle">
            <i class="ri-arrow-go-back-line principle__icon" />
            <div class="principle__body">
              <h5 class="principle__title">{{ t('spec.material.loading.principles.fallback.title') }}</h5>
              <p class="principle__desc">{{ t('spec.material.loading.principles.fallback.desc') }}</p>
            </div>
          </div>
        </div>
      </RowCard>

      <!-- 文件命名约定 -->
      <RowCard :title="t('spec.material.loading.naming.title')">
        <p class="card-desc">{{ t('spec.material.loading.naming.desc') }}</p>
        <pre class="snippet">{{ snippetLoadingFiles }}</pre>
      </RowCard>

      <!-- 当前 brand 演示 -->
      <RowCard :title="t('spec.material.loading.demo.title')">
        <p class="card-desc">{{ t('spec.material.loading.demo.desc') }}</p>
        <div class="loading-demo-row">
          <div class="loading-demo-info">
            <strong>{{ t('spec.material.loading.demo.currentBrand') }}</strong>
            <code>{{ preference.brand }}</code>
            <span class="loading-demo-file">_loading_{{ preference.brand }}.svg</span>
          </div>
          <div
            class="loading-demo-stage"
            v-loading="true"
            :element-loading-svg="loadSvg"
            :element-loading-svg-view-box="svgViewBox"
            element-loading-text="加载中…"
          />
        </div>
      </RowCard>

      <!-- 服务式调用 -->
      <RowCard :title="t('spec.material.loading.service.title')">
        <p class="card-desc">{{ t('spec.material.loading.service.desc') }}</p>
        <div class="service-row">
          <el-button type="primary" @click="triggerService">
            <i class="ri-play-circle-line" />
            {{ t('spec.material.loading.service.trigger') }}
          </el-button>
          <span class="service-hint">{{ t('spec.material.loading.service.hint') }}</span>
        </div>
        <div ref="serviceTarget" class="service-target">
          <p>{{ t('spec.material.loading.service.target') }}</p>
        </div>
      </RowCard>

      <!-- 用法: 指令式 + 服务式 -->
      <RowCard :title="t('spec.material.loading.usage.title')">
        <p class="card-desc">{{ t('spec.material.loading.usage.desc') }}</p>
        <h6 class="snippet-subtitle">{{ t('spec.material.loading.usage.directive') }}</h6>
        <pre class="snippet">{{ snippetDirective }}</pre>
        <h6 class="snippet-subtitle">{{ t('spec.material.loading.usage.service') }}</h6>
        <pre class="snippet">{{ snippetService }}</pre>
      </RowCard>
    </TitledSection>
  </div>
</template>

<style scoped>
.rule-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: var(--app-font-size-large);
  font-weight: 600;
}
.rule-title i {
  color: var(--app-primary);
  font-size: 18px;
}
.rule-list {
  margin: 8px 0 0;
  padding-left: 20px;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-base);
  line-height: 1.8;
}
.card-desc {
  margin: 0 0 12px;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-base);
  line-height: 1.6;
}

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

.empty-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
@media (max-width: 1023px) {
  .empty-grid { grid-template-columns: 1fr; }
}
.empty-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px;
  border: 1px solid var(--app-border-light);
  border-radius: var(--app-radius-md);
  background: var(--app-bg-card);
}
.empty-cell__scene {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 4px 10px;
  background: var(--app-bg-muted);
  border-radius: 999px;
  font-size: 12px;
  color: var(--app-text-tertiary);
}
.empty-cell__scene i {
  font-size: 14px;
}

.brand-empty-svg {
  width: 120px;
  height: 120px;
  color: var(--app-text-tertiary);  /* outline 灰 */
}
.brand-empty-svg :deep(svg) {
  width: 100%;
  height: 100%;
}

/* 404 实时预览: 跟 NotFound.vue 同样的 el-empty + useBrandEmpty 组合, 这里缩成 120px 适配 spec 卡片 */
.not-found-preview {
  display: flex;
  justify-content: center;
  margin-top: 12px;
  padding: 16px;
  border: 1px dashed var(--app-border-light);
  border-radius: var(--app-radius-md);
  background: var(--app-bg-card);
}
.not-found-preview__svg {
  width: 120px;
  height: 120px;
  color: var(--app-text-tertiary);
}
.not-found-preview__svg :deep(svg) {
  width: 100%;
  height: 100%;
}

/* §4.2 加载 (从原 /spec/loading 页面搬过来) */
.principles-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.principle {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.principle__icon {
  font-size: 20px;
  color: var(--app-primary);
  flex-shrink: 0;
  margin-top: 2px;
}
.principle__body { flex: 1; min-width: 0; }
.principle__title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
}
.principle__desc {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.principle__desc code {
  background: var(--app-bg-muted);
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 0.9em;
}

.loading-demo-row {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.loading-demo-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--app-font-size-base);
}
.loading-demo-info strong { color: var(--app-text); }
.loading-demo-info code {
  background: var(--app-bg-muted);
  padding: 2px 8px;
  border-radius: 4px;
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-weight: 600;
  color: var(--app-primary);
  align-self: flex-start;
}
.loading-demo-file {
  color: var(--app-text-tertiary);
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
}
.loading-demo-stage {
  flex: 1;
  min-width: 240px;
  min-height: 140px;
  position: relative;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-md);
}

.service-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.service-hint {
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-base);
}
.service-target {
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-md);
  color: var(--app-text-tertiary);
  position: relative;
}
.service-target p {
  margin: 0;
  font-size: var(--app-font-size-base);
}

.snippet-subtitle {
  margin: 12px 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
}
.snippet-subtitle:first-of-type { margin-top: 0; }

.placeholder-note {
  margin: 0;
  color: var(--app-text-tertiary);
  font-size: var(--app-font-size-base);
  font-style: italic;
}
</style>
