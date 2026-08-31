<script setup lang="ts">
// 规范 · 总则 — 项目级最高纲领, 仅包含 4 项核心原则:
//   1.1 路由模式 (Routing)
//   1.2 国际化多语言 (Internationalization)
//   1.3 主题动态更换 (Theme Switching)
//   1.4 响应式设计 (Responsive Design)
// 详细规则请看后续子页 (原则 / 技术栈 / 布局 / 表单 / 风格 / 配色 / 字体 / 图标 / 素材).
import { useI18n } from 'vue-i18n'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'

const { t } = useI18n()
</script>

<template>
  <div class="app-page spec-page">
    <HeaderSection
      :title="t('spec.general.title')"
      :subtitle="t('spec.general.subtitle')"
    />

    <!-- §1.1 路由模式 (Routing) -->
    <TitledSection
      :title="t('spec.general.routing.title')"
      :description="t('spec.general.routing.desc')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-link" />
            <span>{{ t('spec.general.routing.historyMode.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.routing.historyMode.desc') }}</p>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-server-line" />
            <span>{{ t('spec.general.routing.server.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.routing.server.desc') }}</p>
      </RowCard>
    </TitledSection>

    <!-- §1.2 国际化多语言 (Internationalization) -->
    <TitledSection
      :title="t('spec.general.i18n.title')"
      :description="t('spec.general.i18n.desc')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-translate" />
            <span>{{ t('spec.general.i18n.default.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.i18n.default.desc') }}</p>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-text" />
            <span>{{ t('spec.general.i18n.static.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.i18n.static.desc') }}</p>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-refresh-line" />
            <span>{{ t('spec.general.i18n.switching.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.i18n.switching.desc') }}</p>
      </RowCard>
    </TitledSection>

    <!-- §1.3 主题动态更换 (Theme Switching) -->
    <TitledSection
      :title="t('spec.general.theme.title')"
      :description="t('spec.general.theme.desc')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-palette-line" />
            <span>{{ t('spec.general.theme.dynamic.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.theme.dynamic.desc') }}</p>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shape-line" />
            <span>{{ t('spec.general.theme.dimensions.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">
          {{ t('spec.general.theme.dimensions.desc') }}
        </p>
        <div class="dim-tags">
          <el-tag type="primary" effect="plain" round>
            <i class="ri-layout-grid-line" /> 布局
          </el-tag>
          <el-tag type="success" effect="plain" round>
            <i class="ri-magic-line" /> 风格
          </el-tag>
          <el-tag type="warning" effect="plain" round>
            <i class="ri-paint-line" /> 配色
          </el-tag>
        </div>
      </RowCard>
    </TitledSection>

    <!-- §1.4 响应式设计 (Responsive Design) -->
    <TitledSection
      :title="t('spec.general.responsive.title')"
      :description="t('spec.general.responsive.desc')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-device-line" />
            <span>{{ t('spec.general.responsive.devices.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.general.responsive.devices.desc') }}</p>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-ruler-2-line" />
            <span>{{ t('spec.general.responsive.breakpoints.title') }}</span>
          </h4>
        </template>
        <ul class="bp-list">
          <li>
            <i class="ri-smartphone-line" />
            <strong>{{ t('spec.general.responsive.breakpoints.mobile') }}</strong>
          </li>
          <li>
            <i class="ri-tablet-line" />
            <strong>{{ t('spec.general.responsive.breakpoints.tablet') }}</strong>
          </li>
          <li>
            <i class="ri-computer-line" />
            <strong>{{ t('spec.general.responsive.breakpoints.desktop') }}</strong>
          </li>
        </ul>
      </RowCard>
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-cursor-line" />
            <span>{{ t('spec.general.responsive.interaction.title') }}</span>
          </h4>
        </template>
        <p class="rule-desc">
          {{ t('spec.general.responsive.interaction.desc') }}
        </p>
        <el-tag type="primary" effect="dark" size="large" round>
          44px × 44px
        </el-tag>
      </RowCard>
    </TitledSection>
  </div>
</template>

<style scoped>
.spec-page {
  max-width: 1200px;
}
.anchor-section {
  scroll-margin-top: 72px;
}
.rule-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
}
.rule-title i {
  font-size: 18px;
  color: var(--el-color-primary);
}
.rule-desc {
  margin: 0;
  font-size: 13px;
  color: var(--app-text);
  line-height: 1.7;
}

.dim-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}
.dim-tags .el-tag i {
  margin-right: 4px;
}

.bp-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  color: var(--app-text);
}
.bp-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-sm);
}
.bp-list i {
  font-size: 16px;
  color: var(--el-color-primary);
}
</style>
