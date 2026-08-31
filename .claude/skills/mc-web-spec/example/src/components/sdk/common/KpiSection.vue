<script setup lang="ts">
// KpiSection — 单 KPI 数据块 (icon + value + unit + trend) (SDK).
// 直接继承 WorkSection (不 wrap TitledSection, 避免图标重复).
//
// 新增 theme prop (manyun 三色生态):
//   - 'green' (默认) — 主色 / 农作物
//   - 'gold'           — 收获 / 暖色对比
//   - 'brown'          — 土壤 / 育种
// 给 WorkSection 加 .kpi--{theme} modifier class, brand CSS 据此切换
// icon 底色 / 顶部色条 / trend pill 配色. 其它品牌 (单色) 不传 theme 时
// 走默认 --app-primary, 行为不变.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatNumber } from '@/utils/format'
import WorkSection from '@/components/sdk/common/WorkSection.vue'

interface Props {
  title: string
  icon: string
  value: number | string
  unit?: string
  trend?: number
  description?: string
  /** 卡片主题 (影响 icon 底色 + 顶部色条 + trend pill 配色). 默认 'green'. */
  theme?: 'green' | 'gold' | 'brown'
}
const props = withDefaults(defineProps<Props>(), {
  unit: '',
  trend: undefined,
  description: '',
  theme: 'green',
})

const { t } = useI18n()

const trendUp = computed(() => (props.trend ?? 0) > 0)

const autoDesc = computed(() => {
  if (props.description) return props.description
  if (props.trend === undefined || props.trend === null) return ''
  const sign = trendUp.value ? ' +' : ' '
  const pct = props.unit === '%' ? '%' : ''
  return `${t('common.vsLastWeek')}${sign}${props.trend}${pct}`
})

const themeClass = computed(() => `kpi--${props.theme}`)
</script>

<template>
  <WorkSection no-header-border :class="themeClass">
    <template #header>
      <div class="kpi-head">
        <span class="kpi-head__label">{{ title }}</span>
        <span v-if="autoDesc" class="kpi-head__trend" :class="{ 'is-up': trendUp }">
          <i :class="trendUp ? 'ri-arrow-up-line' : 'ri-arrow-down-line'" />
          {{ autoDesc }}
        </span>
      </div>
    </template>

    <div class="kpi-body">
      <div class="kpi-body__icon">
        <i :class="icon" />
      </div>
      <div class="kpi-body__main">
        <div class="kpi-body__value">
          <span class="kpi-body__number">{{ typeof value === 'number' ? formatNumber(value) : value }}</span>
          <small v-if="unit" class="kpi-body__unit">{{ unit }}</small>
        </div>
        <div v-if="trend !== undefined && trend !== null" class="kpi-body__delta" :class="{ 'is-up': trendUp }">
          <i :class="trendUp ? 'ri-arrow-up-line' : 'ri-arrow-down-line'" />
          <span>{{ trendUp ? '+' : '' }}{{ trend }}{{ unit === '%' ? '%' : '' }}</span>
        </div>
      </div>
    </div>
  </WorkSection>
</template>

<style scoped>
.kpi-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.kpi-head__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
}
.kpi-head__trend {
  font-size: 11px;
  color: var(--app-text-tertiary);
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.kpi-head__trend.is-up { color: var(--el-color-success); }

.kpi-body {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 4px 0;
}
.kpi-body__icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  border-radius: 50%;
  background: var(--kpi-icon-bg, var(--el-color-primary-light-9));
  color: var(--kpi-icon-color, var(--app-primary));
  flex-shrink: 0;
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.04);
}
.kpi-body__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kpi-body__value {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.kpi-body__number {
  font-size: 32px;
  font-weight: 700;
  color: var(--app-text);
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.kpi-body__unit {
  font-size: 14px;
  font-weight: 400;
  color: var(--app-text-secondary);
}
.kpi-body__delta {
  font-size: 11px;
  color: var(--kpi-delta-color, var(--app-text-tertiary));
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: var(--kpi-delta-bg, transparent);
  padding: 1px 6px;
  border-radius: 999px;
  width: fit-content;
  margin-top: 2px;
}
.kpi-body__delta.is-up { color: var(--kpi-delta-color, var(--el-color-success)); }

/* ============================================================
   Theme variants — 由 brand-manyun.css 定义 --kpi-icon-bg / --kpi-icon-color
   / --kpi-delta-bg / --kpi-delta-color 等 token. 这里只 wire 关系.
   单色品牌不传 theme 时, fallback 到默认 --app-primary 系列.
   ============================================================ */
:deep(.work-section) {
  /* 顶部色条 — 默认主色 (单色品牌 fallback) */
  border-top: 3px solid var(--kpi-border-top, var(--app-primary));
}
:deep(.work-section.kpi--gold) {
  --kpi-border-top: var(--brand-gold);
  --kpi-icon-bg: var(--brand-kpi-icon-bg-gold, #FEFAE0);
  --kpi-icon-color: var(--brand-kpi-icon-color-gold, #B0865A);
  --kpi-delta-bg: var(--brand-kpi-trend-bg-gold, #FEFAE0);
  --kpi-delta-color: var(--brand-kpi-trend-color-gold, #B0865A);
}
:deep(.work-section.kpi--brown) {
  --kpi-border-top: var(--brand-brown);
  --kpi-icon-bg: var(--brand-kpi-icon-bg-brown, #F5EBE6);
  --kpi-icon-color: var(--brand-kpi-icon-color-brown, #8C6239);
  --kpi-delta-bg: var(--brand-kpi-trend-bg-brown, #F5EBE6);
  --kpi-delta-color: var(--brand-kpi-trend-color-brown, #8C6239);
}
:deep(.work-section.kpi--green) {
  --kpi-border-top: var(--brand-primary);
  --kpi-icon-bg: var(--brand-kpi-icon-bg-green, #E8F5E9);
  --kpi-icon-color: var(--brand-kpi-icon-color-green, #2D6A4F);
  --kpi-delta-bg: var(--brand-kpi-trend-bg-green, #E8F5E9);
  --kpi-delta-color: var(--brand-kpi-trend-color-green, #2D6A4F);
}
</style>
