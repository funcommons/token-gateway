<script setup lang="ts">
// 规范 · 三、样式 (Style) — 项目级视觉规范的"总纲".
// 4 个面: 边距 / 框线 / 阴影 / 高亮. 全部值从 src/styles/{_variables,_themes,
// _mixins,_ep-overrides}.scss + src/styles/brands/_*.scss 抽取, 是表单
// (Forms.vue §5.2/5.3) 的数据源. 切 brand/theme 实时跟随.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'

const { t } = useI18n()

// ---- §3.1 设计原则 ----
// (4 张 RowCard: 边距 / 框线 / 阴影 / 高亮, 各带 icon + 描述 + mini demo)

// ---- §3.2 变量与常量 (4 张大表) ----
// 数据从 src/styles 抽取, 跟 Forms.vue §5.2 共用同一份数据 (作为表单"复用 这些值"的源头)
const paddingRows = [
  ['--el-form-item-margin-bottom', '22px', '22px', 'form-item 垂直间距'],
  ['--el-form-item-label-padding-right', '12px', '12px', 'label 跟 input 间距'],
  ['--el-input__wrapper padding', '1px 11px', '1px 11px', 'input 内边距 (EP 默认)'],
  ['--el-textarea__inner padding', '5px 11px', '5px 11px', 'textarea 内边距'],
  ['--el-button padding', '8px 15px', '8px 15px', 'button 内边距'],
  ['--app-block-pad (容器)', '4 / 8 / 16px', '4 / 8 / 16px', '页面块内边距 (mobile / tablet / desktop)'],
  ['--app-card-padding (TitledSection)', '16px', '16px', '卡片块内边距'],
  ['--app-header-padding', '0 16px', '0 16px', 'header 横向内边距'],
  ['--app-header-gap', '16px', '16px', 'header 三段间距'],
] as const

const borderRows = [
  ['--el-border-color', '#dcdfe6', '#dcdfe6', '主边框色 (light theme)'],
  ['--el-border-color-light', 'color-mix(70% transparent)', '派生', '4 档边框 1: light'],
  ['--el-border-color-lighter', 'color-mix(40% transparent)', '派生', '4 档边框 2: lighter'],
  ['--el-border-color-extra-light', 'color-mix(15% transparent)', '派生', '4 档边框 3: input 闲置'],
  ['--brand-radius-input', '4px', '8px', 'input / textarea / select / date-picker (mchuan)'],
  ['--brand-radius-button', '4px', '8px', 'button (mchuan)'],
  ['--brand-radius-card', '8px', '8px', 'TitledSection 容器 (mchuan)'],
  ['--brand-radius-md', '6px', '6px', '次级卡片 / 段'],
  ['--brand-radius-chip', '4px', '4px', 'tag / chip'],
] as const

const shadowRows = [
  ['--brand-shadow-sm', '0 1px 2px rgba(0,0,0,0.03)', '同', '浮起 / hover 微浮'],
  ['--brand-shadow-md', '0 1px 3px rgba(0,0,0,0.05), 0 1px 2px rgba(0,0,0,0.03)', '同', '弹窗 / 抽屉'],
  ['--brand-shadow-lg', '0 4px 12px rgba(0,0,0,0.06)', '同', 'dialog / drawer 大阴影'],
  ['--brand-shadow-xl', '0 8px 24px rgba(0,0,0,0.08)', '同', '大浮层 (不常用)'],
  ['--el-box-shadow-light', '0 0 12px rgba(0,0,0,0.06)', '同', 'select / date-picker 下拉'],
  ['--el-overlay-color', 'rgba(0,0,0,0.5)', 'rgba(0,0,0,0.5)', '弹窗遮罩 (light)'],
] as const

const highlightRows = [
  ['.el-input__wrapper::before (focus ring)', '(无)', '1px 渐变描边 + mask', '项目特有: focus 1px 渐变 ring'],
  ['.el-checkbox.is-checked .el-checkbox__inner', 'EP 默认', '--brand-primary-gradient', 'checkbox 选中'],
  ['.el-radio.is-checked .el-radio__inner', 'EP 默认', '--brand-primary-gradient', 'radio 选中'],
  ['.el-radio__input.is-checked + .el-radio__label', '(无)', 'color: --brand-primary', 'radio 文字色'],
  ['.el-select-dropdown__item.is-selected', '(无)', 'primary + 12% bg + 600', 'select 选中'],
  ['.el-switch.is-checked .el-switch__core', '(无)', '--brand-primary-gradient', 'switch 打开'],
  ['.el-tag.el-tag--primary', '(无)', '--brand-primary-gradient + on-primary', 'primary tag (白字)'],
  ['.el-date-table td.current', '(无)', '--brand-primary-gradient', 'date-picker 当天'],
  ['.el-button--primary:hover', '(无)', 'filter: brightness(1.05)', 'primary 按钮悬浮'],
  ['--el-color-success', '#67c23a', '#67c23a', '成功态 (固定)'],
  ['--el-color-warning', '#e6a23c', '#e6a23c', '警告态 (固定)'],
  ['--el-color-danger', '#f56c6c', '#f56c6c', '错误态 (固定)'],
  ['--el-color-info', '--app-text-tertiary', '#9ca3af', '信息态'],
] as const

// ---- §3.3 现场演示 (4 张演示卡, 切 brand/theme 实时跟随) ----
// 1. 边距演示: --app-block-pad 三档 + form-item 22px gap
// 2. 框线演示: 4 档边框颜色 + 5 档圆角 (mchuan 实测值: 2/4/6/8/12, 跟 mchuan radius 阶梯一致)
// 3. 阴影演示: sm/md/lg/xl + overlay 5 档
// 4. 高亮演示: focus ring + 各激活态 + 错误态

// 当前 brand 圆角实时显示 (mchuan 8px / apple 6px / ldx2 8px)
const brandRadius = ref('--app-radius-input = 8px')
const brandRadiusButton = ref('--app-radius-button = 8px')
const brandRadiusCard = ref('--app-radius-card = 8px')
function refreshRadius() {
  const root = getComputedStyle(document.documentElement)
  const v = (varName: string) => root.getPropertyValue(varName).trim()
  brandRadius.value = `--app-radius-input = ${v('--app-radius-input')}`
  brandRadiusButton.value = `--app-radius-button = ${v('--app-radius-button')}`
  brandRadiusCard.value = `--app-radius-card = ${v('--app-radius-card')}`
}
onMounted(() => {
  refreshRadius()
  const obs = new MutationObserver(refreshRadius)
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-brand', 'data-theme'] })
  onBeforeUnmount(() => obs.disconnect())
})
</script>

<template>
  <div class="app-page spec-page">
    <HeaderSection
      :title="t('spec.style.title', '三、样式 (Style)')"
      :subtitle="t('spec.style.subtitle', '项目级视觉规范的总纲 — 边距 / 框线 / 阴影 / 高亮 4 大面的变量、常量与多主题方案. 表单规范 (四、表单 §5.2/5.3) 复用本表的值.')"
    />

    <!-- §3.1 设计原则 -->
    <TitledSection
      :title="t('spec.style.principles.title', '3.1 设计原则')"
      :description="t('spec.style.principles.desc', '样式由 4 个面共同决定, 缺一不可. 每个面都有 变量 / 默认值 / 当前项目取值 / 用途 4 列对照, 切 brand/theme 时所有面跟随.')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-space" />
            <span>{{ t('spec.style.principles.padding.title', '3.1.1 边距 (padding / spacing)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.principles.padding.desc', '容器 / label / item / input / button 各自的 padding 走 EP CSS 变量, 切 theme 时 spacing 不变 (spacing 不绑主题, 跟 font 一样稳定). 页面块内边距走 --app-block-pad 三档响应式 (mobile 4 / tablet 8 / desktop 16).') }}</p>
        <div class="style-demo-pad">
          <div class="pad-block pad-block--mobile"><span>mobile<br />4px</span></div>
          <div class="pad-block pad-block--tablet"><span>tablet<br />8px</span></div>
          <div class="pad-block pad-block--desktop"><span>desktop<br />16px</span></div>
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-rectangle-line" />
            <span>{{ t('spec.style.principles.border.title', '3.1.2 框线 (border + radius)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.principles.border.desc', '边框颜色走 4 档派生 (主 / light / lighter / extra-light), 闲置用 extra-light, focus 用 1px 渐变 ring. 圆角只用一种 (mchuan 8px), 由当前 brand 决定, 切 brand 全部跟随. 禁止业务侧硬编码.') }}</p>
        <div class="style-demo-border">
          <div class="border-block" style="border: 1px solid var(--el-border-color)">主边框<br />--el-border-color</div>
          <div class="border-block" style="border: 1px solid var(--el-border-color-light)">light</div>
          <div class="border-block" style="border: 1px solid var(--el-border-color-lighter)">lighter</div>
          <div class="border-block" style="border: 1px solid var(--el-border-color-extra-light)">extra-light</div>
        </div>
        <p class="forms-radius-marker">当前圆角: <code>{{ brandRadius }}</code> · <code>{{ brandRadiusButton }}</code> · <code>{{ brandRadiusCard }}</code></p>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shadow-line" />
            <span>{{ t('spec.style.principles.shadow.title', '3.1.3 阴影 (shadow)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.principles.shadow.desc', 'mchuan 走近扁平 (0.03-0.06 透明度), 4 档: sm / md / lg / xl. form 容器通常无 shadow; select/dropdown 用 --el-box-shadow-light; focus 不加 box-shadow, 改 1px 渐变 ring.') }}</p>
        <div class="style-demo-shadow">
          <div class="shadow-block shadow-block--sm">sm</div>
          <div class="shadow-block shadow-block--md">md</div>
          <div class="shadow-block shadow-block--lg">lg</div>
          <div class="shadow-block shadow-block--xl">xl</div>
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-focus-3-line" />
            <span>{{ t('spec.style.principles.highlight.title', '3.1.4 高亮 (focus / active / error / success)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.principles.highlight.desc', 'focus = 1px --brand-primary-gradient ring (mask trick); active = 品牌色 12% bg + 600 weight; error = --el-color-danger 1px + 红字; success/warning/error 色固定, 不绑品牌.') }}</p>
        <div class="style-demo-highlight">
          <div class="hl-block hl-block--focus">focus</div>
          <div class="hl-block hl-block--active">active</div>
          <div class="hl-block hl-block--hover">hover</div>
          <div class="hl-block hl-block--error">error</div>
          <div class="hl-block hl-block--success">success</div>
        </div>
      </RowCard>
    </TitledSection>

    <!-- §3.2 变量与常量 (4 张大表) -->
    <TitledSection
      :title="t('spec.style.variables.title', '3.2 变量与常量 — 当前项目实际值 (mchuan 基线)')"
      :description="t('spec.style.variables.desc', '所有变量从项目 SCSS 源文件抽取. 派生值 = 同一变量在不同 theme 下的衍生. 表单 (四、表单) 直接复用这些值.')"
    >
      <RowCard :title="t('spec.style.variables.paddingTable', '3.2.1 边距 (padding / spacing)')">
        <table class="var-table">
          <thead>
            <tr><th>{{ t('spec.style.table.var', '变量') }}</th><th>{{ t('spec.style.table.default', '默认') }}</th><th>{{ t('spec.style.table.mchuan', 'mchuan') }}</th><th>{{ t('spec.style.table.use', '用途') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in paddingRows" :key="row[0]">
              <td><code>{{ row[0] }}</code></td>
              <td>{{ row[1] }}</td>
              <td><strong>{{ row[2] }}</strong></td>
              <td>{{ row[3] }}</td>
            </tr>
          </tbody>
        </table>
      </RowCard>

      <RowCard :title="t('spec.style.variables.borderTable', '3.2.2 框线 (border + radius)')">
        <table class="var-table">
          <thead>
            <tr><th>{{ t('spec.style.table.var', '变量') }}</th><th>{{ t('spec.style.table.default', '默认') }}</th><th>{{ t('spec.style.table.mchuan', 'mchuan') }}</th><th>{{ t('spec.style.table.use', '用途') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in borderRows" :key="row[0]">
              <td><code>{{ row[0] }}</code></td>
              <td>{{ row[1] }}</td>
              <td><strong>{{ row[2] }}</strong></td>
              <td>{{ row[3] }}</td>
            </tr>
          </tbody>
        </table>
      </RowCard>

      <RowCard :title="t('spec.style.variables.shadowTable', '3.2.3 阴影 (shadow)')">
        <table class="var-table">
          <thead>
            <tr><th>{{ t('spec.style.table.var', '变量') }}</th><th>{{ t('spec.style.table.default', '默认') }}</th><th>{{ t('spec.style.table.mchuan', 'mchuan') }}</th><th>{{ t('spec.style.table.use', '用途') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in shadowRows" :key="row[0]">
              <td><code>{{ row[0] }}</code></td>
              <td>{{ row[1] }}</td>
              <td><strong>{{ row[2] }}</strong></td>
              <td>{{ row[3] }}</td>
            </tr>
          </tbody>
        </table>
      </RowCard>

      <RowCard :title="t('spec.style.variables.highlightTable', '3.2.4 高亮 (focus / active / error / success)')">
        <table class="var-table">
          <thead>
            <tr><th>{{ t('spec.style.table.var', '变量') }}</th><th>{{ t('spec.style.table.default', '默认') }}</th><th>{{ t('spec.style.table.mchuan', 'mchuan') }}</th><th>{{ t('spec.style.table.use', '用途') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in highlightRows" :key="row[0]">
              <td><code>{{ row[0] }}</code></td>
              <td>{{ row[1] }}</td>
              <td><strong>{{ row[2] }}</strong></td>
              <td>{{ row[3] }}</td>
            </tr>
          </tbody>
        </table>
      </RowCard>
    </TitledSection>

    <!-- §3.3 现场演示 (4 张) -->
    <TitledSection
      :title="t('spec.style.demo.title', '3.3 现场演示 — 切 brand / theme 实时跟随')"
      :description="t('spec.style.demo.desc', '所有 4 个面都有现场演示. 切 brand (mchuan / apple / ldx2) → 圆角 + 阴影 + focus ring 渐变实时变化. 切 theme (light / dark / orange-black) → 边框 / 文字 / 阴影跟随.')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-space" />
            <span>{{ t('spec.style.demo.padding.title', '3.3.1 边距演示 — 响应式 3 档 (4 / 8 / 16px) + form-item 22px') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.demo.padding.desc', '左: 4 个 form-item 默认 22px 间距 (--el-form-item-margin-bottom). 右: 页面块在 mobile / tablet / desktop 三档下 --app-block-pad 分别是 4 / 8 / 16px, 随视口自动切换.') }}</p>
        <el-form label-width="100px" style="max-width: 480px">
          <el-form-item label="姓名"><el-input model-value="默认 22px" /></el-form-item>
          <el-form-item label="邮箱"><el-input model-value="默认 22px" /></el-form-item>
          <el-form-item label="手机"><el-input model-value="默认 22px" /></el-form-item>
          <el-form-item>
            <el-button type="primary">提交</el-button>
            <el-button>重置</el-button>
          </el-form-item>
        </el-form>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-rectangle-line" />
            <span>{{ t('spec.style.demo.border.title', '3.3.2 框线演示 — 4 档边框色 + 5 档圆角阶梯') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.demo.border.desc', '上排: 4 档边框色 (主 / light / lighter / extra-light), 颜色越浅越适合闲置/分割线. 下排: 5 档圆角阶梯 (2/4/6/8/12), mchuan 取 8px 档, 跟其他 brand 形成视觉对比.') }}</p>
        <div class="forms-demo-stack">
          <div class="forms-demo-row">
            <div class="border-color-block" style="border-color: var(--el-border-color)">主</div>
            <div class="border-color-block" style="border-color: var(--el-border-color-light)">light</div>
            <div class="border-color-block" style="border-color: var(--el-border-color-lighter)">lighter</div>
            <div class="border-color-block" style="border-color: var(--el-border-color-extra-light)">extra-light</div>
          </div>
          <div class="forms-demo-row">
            <div class="radius-block" style="border-radius: 2px">2</div>
            <div class="radius-block" style="border-radius: 4px">4</div>
            <div class="radius-block" style="border-radius: 6px">6</div>
            <div class="radius-block" style="border-radius: 8px">8</div>
            <div class="radius-block" style="border-radius: 12px">12</div>
          </div>
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shadow-line" />
            <span>{{ t('spec.style.demo.shadow.title', '3.3.3 阴影演示 — 4 档 (sm / md / lg / xl) + overlay 遮罩') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.demo.shadow.desc', '4 档阴影 (sm/md/lg/xl) 用于不同高度的浮层. mchuan 走近扁平 (0.03-0.08 透明度). 弹窗背景用 overlay (rgba 0,0,0,0.5 light / 0.7 dark).') }}</p>
        <div class="forms-demo-row">
          <div class="shadow-card shadow-card--sm">--brand-shadow-sm</div>
          <div class="shadow-card shadow-card--md">--brand-shadow-md</div>
          <div class="shadow-card shadow-card--lg">--brand-shadow-lg</div>
          <div class="shadow-card shadow-card--xl">--brand-shadow-xl</div>
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-focus-3-line" />
            <span>{{ t('spec.style.demo.highlight.title', '3.3.4 高亮演示 — focus / active / hover / error / success 5 种') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.style.demo.highlight.desc', '演示 5 种典型高亮态: focus 1px 品牌渐变 ring; active 12% 品牌色 bg + 600 weight; hover 微亮; error 1px 红 + 红字; success 1px 绿 + 绿字.') }}</p>
        <div class="style-demo-stack">
          <el-input placeholder="focus 我 (1px 渐变 ring)" style="max-width: 320px" />
          <el-button type="primary" :class="{ 'is-active-demo': true }">active</el-button>
          <el-button>hover</el-button>
          <el-input model-value="error" status="error" placeholder="error 态" style="max-width: 320px" />
          <el-input model-value="success" status="success" placeholder="success 态" style="max-width: 320px" />
        </div>
      </RowCard>
    </TitledSection>
  </div>
</template>

<style scoped>
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
  color: var(--app-primary);
  font-size: 16px;
}
.rule-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--app-text-secondary);
  line-height: 1.6;
}

/* §3.1 demos */
.style-demo-pad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.pad-block {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--app-text-secondary);
  height: 80px;
  text-align: center;
  line-height: 1.4;
}
.pad-block--mobile { padding: 4px; }
.pad-block--tablet { padding: 8px; }
.pad-block--desktop { padding: 16px; }

.style-demo-border {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.border-block {
  flex: 1;
  min-width: 120px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--app-text-secondary);
  background: var(--el-bg-color);
  border-radius: var(--app-radius-sm);
  text-align: center;
  line-height: 1.4;
}

.style-demo-shadow {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.shadow-block {
  background: var(--el-bg-color);
  border-radius: var(--app-radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text);
  height: 80px;
}
.shadow-block--sm { box-shadow: var(--brand-shadow-sm, var(--app-shadow-sm)); }
.shadow-block--md { box-shadow: var(--brand-shadow-md, var(--app-shadow-md)); }
.shadow-block--lg { box-shadow: var(--brand-shadow-lg, var(--app-shadow-lg)); }
.shadow-block--xl { box-shadow: 0 8px 24px rgba(0,0,0,0.08); }

.style-demo-highlight {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}
.hl-block {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  border-radius: var(--app-radius-sm);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  color: var(--app-text);
}
.hl-block--focus { box-shadow: 0 0 0 1px var(--app-primary); }
.hl-block--active { background: color-mix(in srgb, var(--app-primary) 12%, transparent); color: var(--app-primary); font-weight: 600; }
.hl-block--hover { background: var(--el-fill-color-light); }
.hl-block--error { box-shadow: 0 0 0 1px var(--el-color-danger); color: var(--el-color-danger); }
.hl-block--success { box-shadow: 0 0 0 1px var(--el-color-success); color: var(--el-color-success); }

.forms-radius-marker {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}
.forms-radius-marker code {
  background: var(--el-fill-color-lighter);
  color: var(--app-text);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: 'Fira Code', 'Menlo', monospace;
  font-size: 11px;
  margin: 0 4px;
}

/* §3.3 demos */
.forms-demo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.forms-demo-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.border-color-block {
  flex: 1;
  min-width: 80px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  color: var(--app-text-secondary);
  background: var(--el-bg-color);
  border: 2px solid;
  border-radius: var(--app-radius-md);
}
.radius-block {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
}
.shadow-card {
  flex: 1;
  min-width: 120px;
  height: 80px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--app-text-secondary);
}
.shadow-card--sm { box-shadow: var(--app-shadow-sm); }
.shadow-card--md { box-shadow: var(--app-shadow-md); }
.shadow-card--lg { box-shadow: var(--app-shadow-lg); }
.shadow-card--xl { box-shadow: 0 8px 24px rgba(0,0,0,0.08); }
.style-demo-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

/* §3.2 var-table */
.var-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  background: var(--el-bg-color);
  border-radius: var(--app-radius-sm);
  overflow: hidden;
}
.var-table th,
.var-table td {
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.var-table th {
  background: var(--el-fill-color-light);
  color: var(--app-text);
  font-weight: 600;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.var-table td {
  color: var(--app-text-secondary);
  vertical-align: top;
}
.var-table td code {
  background: var(--el-fill-color-lighter);
  color: var(--app-text);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: 'Fira Code', 'Menlo', monospace;
  font-size: 11px;
}
.var-table td strong {
  color: var(--app-primary);
  font-weight: 600;
}
.var-table tr:last-child td { border-bottom: none; }
.var-table tr:hover td { background: var(--el-fill-color-light); }

@media (max-width: 768px) {
  .style-demo-pad,
  .style-demo-shadow,
  .style-demo-highlight {
    grid-template-columns: 1fr;
  }
  .var-table { font-size: 11px; }
  .var-table th, .var-table td { padding: 6px 8px; }
}
</style>
