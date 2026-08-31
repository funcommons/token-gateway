<script setup lang="ts">
// 规范 · 五、表单 (Forms) — 表单元素的 padding / border / shadow / highlight 规范.
// 基线 = 当前项目实际值 (mchuan brand, light theme). 切 brand / theme 时 §5.3 演示实时跟随.
// §5.2 变量表数据全部从 src/styles/{_variables,_themes,_mixins,_ep-overrides}.scss
// 和 src/styles/brands/_mchuan.scss 抽取, 表格里的数字 = 真实值.
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'

const { t } = useI18n()

// ---- §5.3 演示用 form state ----
const formRef = ref()
const form = reactive({ name: '', email: '', phone: '', agree: false })
const formCompact = reactive({ a: '', b: '' })
const formError = reactive({ pwd: '' })
const rules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}
const radiusInput = ref('')
const radiusTextarea = ref('')
// 当前 brand 的 input 圆角 (从 --app-radius-input 读, 切 brand 时实时跟随)
const brandRadius = ref('--app-radius-input = 4px')
function refreshRadius() {
  const v = getComputedStyle(document.documentElement).getPropertyValue('--app-radius-input').trim()
  if (v) brandRadius.value = `--app-radius-input = ${v}`
}
onMounted(() => {
  refreshRadius()
  // 监听 html data-brand / data-theme 变化, 重新读
  const obs = new MutationObserver(refreshRadius)
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-brand', 'data-theme'] })
  onBeforeUnmount(() => obs.disconnect())
})
const switchVal = ref(true)
const checkboxVal = ref(true)
const radioVal = ref('a')
const selectVal = ref('option-1')

// ---- §5.5 集成演示 state ----
const showcaseInput = reactive({
  text: '',
  password: '',
  suffix: '',
  textarea: '',
  readonly: 'https://example.com/invite/abc123',
  disabled: '不可编辑',
})
const showcaseSelect = reactive({
  single: 'mchuan',
  multiple: ['vue', 'ep'],
  disabled: '',
})
const showcaseRadio = reactive({
  radio: 'a',
  view: 'grid',
  checkbox: ['email', 'push'],
  border: '2',
})
const showcaseSwitch = reactive({
  basic: true,
  text: true,
  disabled: true,
  slider: 30,
  range: [20, 70],
})
const showcaseDate = reactive({
  date: '',
  datetime: '',
  range: '',
  time: '',
})
// §5.5.6 input-number + cascader + tree-select
const showcaseNumber = reactive({ count: 1, percent: 50 })
const showcaseCascader = ref<string[]>(['guide', 'design', 'principles'])
const treeData = [
  {
    value: 'guide',
    label: '规范',
    children: [
      { value: 'design', label: '设计原则' },
      { value: 'layout', label: '布局' },
    ],
  },
  {
    value: 'components',
    label: '组件',
    children: [
      { value: 'form', label: '表单' },
      { value: 'data', label: '数据' },
    ],
  },
]
const showcaseTreeSelect = ref<string>('design')
// §5.5.7 color-picker + rate + segmented
const showcaseColor = ref('#2563EB')
const showcaseRate = ref(4)
const showcaseSegmented = ref('list')
// §5.5.8 upload + transfer
const showcaseFileList = ref<{ name: string; url?: string }[]>([
  { name: 'design-spec.md' },
  { name: 'demo.png' },
])
const showcaseTransfer = ref<string[]>(['1', '4'])
const transferData = Array.from({ length: 10 }, (_, i) => ({
  key: String(i + 1),
  label: `选项 ${i + 1}`,
  disabled: i === 1 || i === 5,
}))
// §5.5.9 button 全套 type + 状态 + 链接
const showcaseLoading = ref(false)
function toggleLoading() { showcaseLoading.value = true; setTimeout(() => (showcaseLoading.value = false), 1500) }
// §5.5.10 form 验证态 + 空态
const showcaseValidForm = reactive({ user: '', email: '', agree: false })
const showcaseValidRef = ref()
const showcaseValidRules = {
  user: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}
function onValidSubmit() {
  showcaseValidRef.value?.validate((ok: boolean) => {
    if (ok) ElMessage.success('验证通过')
  })
}

function submit() {
  formRef.value?.validate((ok: boolean) => {
    if (ok) ElMessage.success('提交成功')
  })
}
function reset() {
  formRef.value?.resetFields()
}

// ---- §5.4 代码片段 ----
const snippet = `<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

const formRef = ref()
const form = reactive({ name: '', email: '', agree: false })
const rules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}
function submit() {
  formRef.value?.validate((ok) => {
    if (ok) ElMessage.success('提交成功')
  })
}
<\/script>

<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
    <el-form-item label="姓名" prop="name">
      <el-input v-model="form.name" placeholder="请输入姓名" />
    </el-form-item>
    <el-form-item label="邮箱" prop="email">
      <el-input v-model="form.email" placeholder="请输入邮箱" />
    </el-form-item>
    <el-form-item label=" ">
      <el-checkbox v-model="form.agree">我已阅读并同意服务条款</el-checkbox>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="submit">提交</el-button>
      <el-button @click="formRef?.resetFields()">重置</el-button>
    </el-form-item>
  </el-form>
</template>`

// ---- §5.2 对照表数据 (变量 / 默认 / mchuan / 用途) ----
const paddingRows = [
  ['--el-form-item-margin-bottom', '22px', '22px', 'form-item 垂直间距'],
  ['--el-form-item-label-padding-right', '12px', '12px', 'label 跟 input 间距'],
  ['.el-input__wrapper padding', '1px 11px', '1px 11px', 'input 内边距 (EP 默认)'],
  ['.el-textarea__inner padding', '5px 11px', '5px 11px', 'textarea 内边距'],
  ['.el-button padding', '8px 15px', '8px 15px', 'button 内边距'],
  ['--app-card-padding (TitledSection 容器)', '16px', '16px', 'TitledSection 内部留白'],
] as const

const borderRows = [
  ['--el-border-color', '#dcdfe6', '#dcdfe6', '主边框色 (light theme)'],
  ['--el-border-color-light', 'color-mix(70% transparent)', '派生', '4 档边框 1: light'],
  ['--el-border-color-lighter', 'color-mix(40% transparent)', '派生', '4 档边框 2: lighter'],
  ['--el-border-color-extra-light', 'color-mix(15% transparent)', '派生', '4 档边框 3: input 闲置'],
  ['--brand-radius-input', '4px', '8px', 'input / textarea / select / date-picker 圆角 (mchuan 统一)'],
  ['--brand-radius-button', '4px', '4px', 'button 圆角 (mchuan)'],
  ['--brand-radius-card', '8px', '8px', 'TitledSection 容器圆角 (mchuan)'],
  ['--brand-radius-md', '6px', '6px', '次级卡片 / 段'],
] as const

const shadowRows = [
  ['--brand-shadow-sm', '0 1px 2px rgba(0,0,0,0.03)', '同', '浮起 / hover 微浮'],
  ['--brand-shadow-md', '0 1px 3px rgba(0,0,0,0.05), 0 1px 2px rgba(0,0,0,0.03)', '同', '弹窗 / 抽屉'],
  ['--brand-shadow-lg', '0 4px 12px rgba(0,0,0,0.06)', '同', 'dialog / drawer 大阴影'],
  ['--el-box-shadow-light', '0 0 12px rgba(0,0,0,0.06)', '同', 'select / date-picker 下拉'],
  ['.el-input__wrapper.is-focus', 'box-shadow: none', '同', 'focus 不加 box-shadow, 改 1px ring'],
  ['.el-button--primary:hover', '(无)', 'filter: brightness(1.05)', 'primary 按钮悬浮微亮'],
] as const

const highlightRows = [
  ['.el-input__wrapper::before (focus ring)', '(无)', '1px 渐变描边 + mask', '项目特有: focus 1px 渐变 ring'],
  ['.el-checkbox.is-checked .el-checkbox__inner', 'EP 默认', '--brand-primary-gradient', 'checkbox 选中'],
  ['.el-radio.is-checked .el-radio__inner', 'EP 默认', '--brand-primary-gradient', 'radio 选中'],
  ['.el-radio__input.is-checked + .el-radio__label', '(无)', 'color: --brand-primary', 'radio 文字色'],
  ['.el-select-dropdown__item.is-selected', '(无)', 'primary + 12% bg + 600', 'select 选中'],
  ['.el-switch.is-checked .el-switch__core', '(无)', '--brand-primary-gradient', 'switch 打开'],
  ['.el-tag.el-tag--primary', '(无)', '--brand-primary-gradient', 'primary tag'],
  ['.el-date-table td.current', '(无)', '--brand-primary-gradient', 'date-picker 当天'],
  ['--el-color-success', '#67c23a', '#67c23a', '成功态 (固定)'],
  ['--el-color-warning', '#e6a23c', '#e6a23c', '警告态 (固定)'],
  ['--el-color-danger', '#f56c6c', '#f56c6c', '错误态 (固定)'],
  ['--el-color-info', '--app-text-tertiary', '#9ca3af', '信息态'],
] as const
</script>

<template>
  <div class="app-page spec-page">
    <HeaderSection
      :title="t('spec.forms.title', '五、表单 (Forms)')"
      :subtitle="t('spec.forms.subtitle', '表单元素的 padding / border / shadow / highlight 4 大视觉面 — 当前项目实际值 (mchuan 基线) + 现场演示')"
    />

    <!-- §5.1 设计原则 -->
    <TitledSection
      :title="t('spec.forms.principles.title', '5.1 设计原则')"
      :description="t('spec.forms.principles.desc', '表单的视觉由 4 个面共同决定, 缺一不可. 每张卡对应一个面, 描述取值来源 + 切 brand/theme 时的跟随策略')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-space" />
            <span>{{ t('spec.forms.principles.padding.title', '5.1.1 边距 (padding)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.principles.padding.desc', 'input 内 1px 11px · form-item 间距 22px · label 右 padding 12px. 全部走 EP CSS 变量, 切 theme 时 spacing 不变 (spacing 不绑主题, 跟 font 一样稳定).') }}</p>
        <div class="forms-demo-row">
          <el-input v-model="form.name" placeholder="input padding: 1px 11px" style="width: 220px" />
          <span class="forms-demo-label">↔ 22px ↕</span>
          <el-input v-model="form.email" placeholder="下一个 form-item" style="width: 220px" />
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-rectangle-line" />
            <span>{{ t('spec.forms.principles.border.title', '5.1.2 框线 (border + radius)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.principles.border.desc', '闲置 input: 1px solid --el-border-color-extra-light; focus: 1px 渐变 ring. 圆角走品牌: mchuan 4px / apple 12px / ldx2 16px.') }}</p>
        <div class="forms-demo-row">
          <el-input v-model="form.phone" placeholder="闲置 1px" style="width: 160px" />
          <el-input v-model="form.name" placeholder="聚焦中" style="width: 160px" />
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shadow-line" />
            <span>{{ t('spec.forms.principles.shadow.title', '5.1.3 阴影 (shadow)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.principles.shadow.desc', 'mchuan 走近扁平 (0.03-0.06 透明度). form 容器通常无 shadow; select/dropdown 用 --el-box-shadow-light; focus 不加 box-shadow, 改 1px 渐变 ring.') }}</p>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-focus-3-line" />
            <span>{{ t('spec.forms.principles.highlight.title', '5.1.4 高亮 (focus / active / error)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.principles.highlight.desc', 'focus = 1px --brand-primary-gradient ring (mask trick); active = 品牌色 12% bg + 600 weight; error = --el-color-danger 1px + 红字. 成功/警告/错误色固定, 不绑品牌.') }}</p>
        <div class="forms-demo-row">
          <el-switch v-model="switchVal" />
          <el-checkbox v-model="checkboxVal">checkbox</el-checkbox>
          <el-radio-group v-model="radioVal">
            <el-radio value="a">A</el-radio>
            <el-radio value="b">B</el-radio>
          </el-radio-group>
        </div>
      </RowCard>
    </TitledSection>

    <!-- §5.2 变量与常量对照表 (数据来源: 三、样式 §3.2, 本节为 form-specific 视图) -->
    <TitledSection
      :title="t('spec.forms.variables.title', '5.2 变量与常量 — 当前项目实际值 (mchuan 基线)')"
      :description="t('spec.forms.variables.desc', '所有变量从项目 SCSS 源文件抽取. 派生值 = 同一变量在不同 theme 下的衍生. 本节为表单内的摘要视图, 完整规范见 [三、样式](/spec/style).')"
    >
      <RowCard :title="t('spec.forms.variables.paddingTable', '5.2.1 边距 (padding / spacing)')">
        <table class="var-table">
          <thead>
            <tr><th>变量</th><th>默认</th><th>mchuan</th><th>用途</th></tr>
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

      <RowCard :title="t('spec.forms.variables.borderTable', '5.2.2 框线 (border)')">
        <table class="var-table">
          <thead>
            <tr><th>变量</th><th>默认</th><th>mchuan</th><th>用途</th></tr>
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

      <RowCard :title="t('spec.forms.variables.shadowTable', '5.2.3 阴影 (shadow)')">
        <table class="var-table">
          <thead>
            <tr><th>变量 / 规则</th><th>默认</th><th>mchuan</th><th>用途</th></tr>
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

      <RowCard :title="t('spec.forms.variables.highlightTable', '5.2.4 高亮 (focus / active / error)')">
        <table class="var-table">
          <thead>
            <tr><th>变量 / 规则</th><th>默认</th><th>mchuan</th><th>用途</th></tr>
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

    <!-- §5.3 现场演示 -->
    <TitledSection
      :title="t('spec.forms.demo.title', '5.3 现场演示 — 切 brand / theme 实时跟随')"
      :description="t('spec.forms.demo.desc', '右上角外观抽屉切 brand (mchuan / apple / ldx2) → 圆角 + 阴影 + focus ring 渐变实时变化. 切 theme (light / dark / orange-black) → 边框 / 文字 / 阴影跟随.')"
    >
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-space" />
            <span>{{ t('spec.forms.demo.padding.title', '5.3.1 标准 form 间距 (22px) vs 紧凑 (8px)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.demo.padding.desc', '左: 4 个 form-item 默认 22px 间距. 右: 自定义 .forms-compact 强制 8px. 同一变量 --el-form-item-margin-bottom, 业务层用 utility class 覆盖.') }}</p>
        <div class="forms-demo-grid">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 420px">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="默认 22px" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="form.email" placeholder="默认 22px" />
            </el-form-item>
            <el-form-item label="手机">
              <el-input v-model="form.phone" placeholder="默认 22px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="submit">提交</el-button>
              <el-button @click="reset">重置</el-button>
            </el-form-item>
          </el-form>
          <el-form :model="formCompact" label-width="100px" class="forms-compact" style="max-width: 420px">
            <el-form-item label="字段 A">
              <el-input v-model="formCompact.a" placeholder="紧凑 8px" />
            </el-form-item>
            <el-form-item label="字段 B">
              <el-input v-model="formCompact.b" placeholder="紧凑 8px" />
            </el-form-item>
            <el-form-item>
              <el-button size="small" type="primary">提交</el-button>
              <el-button size="small">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-rectangle-line" />
            <span>{{ t('spec.forms.demo.radius.title', '5.3.2 圆角规范 — 只用一种圆角 (mchuan 4px)') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.demo.radius.desc', '项目只用一种圆角, 由当前 brand 决定 (mchuan 4px / apple 12px / ldx2 16px). 所有 input / button / tag / card / drawer / dialog 共享同一档, 切 brand 时全部跟随. 禁止业务侧硬编码圆角值.') }}</p>
        <div class="forms-demo-row">
          <el-input v-model="radiusInput" placeholder="input" style="width: 160px" />
          <el-input v-model="radiusTextarea" type="textarea" :rows="2" placeholder="textarea" style="width: 220px" />
          <el-button type="primary">主按钮</el-button>
          <el-button>次按钮</el-button>
          <el-tag>tag</el-tag>
          <el-tag type="primary">primary</el-tag>
        </div>
        <p class="forms-radius-marker">当前圆角 = <code>--app-radius-input</code> = <strong>{{ brandRadius }}</strong></p>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shadow-line" />
            <span>{{ t('spec.forms.demo.shadow.title', '5.3.3 阴影 3 档 (sm / md / lg) + input focus 改 1px ring') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.demo.shadow.desc', 'sm/md/lg 三档演示. input focus 时 box-shadow 强制为 none, 改 1px 渐变 ring (项目特色, 区别于 EP 默认 box-shadow focus).') }}</p>
        <div class="forms-demo-row">
          <div class="shadow-card shadow-card--sm">
            <i class="ri-checkbox-blank-line" />
            <span>--brand-shadow-sm</span>
          </div>
          <div class="shadow-card shadow-card--md">
            <i class="ri-checkbox-blank-line" />
            <span>--brand-shadow-md</span>
          </div>
          <div class="shadow-card shadow-card--lg">
            <i class="ri-checkbox-blank-line" />
            <span>--brand-shadow-lg</span>
          </div>
        </div>
        <div class="forms-demo-row" style="margin-top: 16px">
          <el-input placeholder="点我聚焦 — 1px 渐变 ring" style="width: 220px" />
          <el-input placeholder="EP 默认 box-shadow focus" style="width: 220px" class="forms-shadow-focus" />
        </div>
      </RowCard>

      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-focus-3-line" />
            <span>{{ t('spec.forms.demo.highlight.title', '5.3.4 高亮 — focus / active / error 5 种状态') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.demo.highlight.desc', 'switch 打开 (渐变) / checkbox 选中 (渐变) / radio 选中 (渐变 + 文字色) / select 选中 (12% bg + 600) / input 错误态 (1px 红 + 红字).') }}</p>
        <div class="forms-demo-stack">
          <div class="forms-demo-row">
            <el-switch v-model="switchVal" active-text="开" inactive-text="关" />
            <el-checkbox v-model="checkboxVal">checkbox</el-checkbox>
            <el-radio-group v-model="radioVal">
              <el-radio value="a">Apple</el-radio>
              <el-radio value="b">Mchuan</el-radio>
            </el-radio-group>
            <el-tag type="primary">primary tag</el-tag>
          </div>
          <div class="forms-demo-row">
            <el-select v-model="selectVal" placeholder="select 演示" style="width: 180px">
              <el-option label="Option 1" value="option-1" />
              <el-option label="Option 2" value="option-2" />
              <el-option label="Option 3" value="option-3" />
            </el-select>
            <el-input v-model="formError.pwd" placeholder="错误态" style="width: 220px" class="forms-error-state" />
            <span class="forms-error-hint">邮箱格式不正确</span>
          </div>
        </div>
      </RowCard>
    </TitledSection>

    <!-- §5.4 集成用法 -->
    <TitledSection
      :title="t('spec.forms.usage.title', '5.4 集成用法 — 标准 form 模板')"
      :description="t('spec.forms.usage.desc', '项目里所有 form 的标准模板: reactive state + formRef + rules + validate callback. 直接复制到任何 view, 提交按钮触发 ElMessage.')"
    >
      <RowCard
        :title="t('spec.forms.usage.codeTitle', '标准 form · 含 validation + reset + submit')"
        :code="snippet"
      />
    </TitledSection>

    <!-- §5.5 集成演示 — 覆盖所有主要输入类 el 组件 -->
    <TitledSection
      :title="t('spec.forms.showcase.title', '5.5 集成演示 — 所有主要输入类 el 组件')"
      :description="t('spec.forms.showcase.desc', '把项目里会用到的所有输入类 Element Plus 组件集中演示一遍. 每个组件展示典型用法 + 状态 (idle / focus / disabled / readonly / error / success), 切 brand/theme 样式实时跟随.')"
    >
      <!-- 5.5.1 input 系列: text / password / textarea / 前后缀 / clearable / disabled / readonly / 带 icon -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-input-cursor-move" />
            <span>{{ t('spec.forms.showcase.input.title', '5.5.1 el-input 系列 — text / password / textarea / 前后缀 / clearable') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.input.desc', 'el-input 是最常用的输入控件. 演示 6 种典型用法: 文本 / 密码 (可切换) / 多行文本 (rows) / 前后缀 icon / 清除按钮 / 禁用态 / 只读态.') }}</p>
        <el-form :model="showcaseInput" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.input.text', '文本输入')">
            <el-input v-model="showcaseInput.text" :placeholder="t('spec.forms.showcase.input.textPh', '请输入')" clearable>
              <template #prefix><i class="ri-user-line" /></template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.input.password', '密码')">
            <el-input v-model="showcaseInput.password" type="password" show-password :placeholder="t('spec.forms.showcase.input.passwordPh', '请输入密码')" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.input.suffix', '前后缀')">
            <el-input v-model="showcaseInput.suffix" placeholder="搜索">
              <template #prefix><i class="ri-search-line" /></template>
              <template #suffix><i class="ri-information-line" /></template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.input.textarea', '多行文本')">
            <el-input v-model="showcaseInput.textarea" type="textarea" :rows="3" placeholder="请输入备注" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.input.readonly', '只读')">
            <el-input v-model="showcaseInput.readonly" readonly>
              <template #append><el-button>复制</el-button></template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.input.disabled', '禁用')">
            <el-input v-model="showcaseInput.disabled" disabled placeholder="禁用态" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.2 select + cascader -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-list-check" />
            <span>{{ t('spec.forms.showcase.select.title', '5.5.2 el-select + el-cascader — 单选 / 多选 / 搜索 / 级联') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.select.desc', 'el-select 支持单选 / 多选 / 搜索 / 禁用 / 分组; el-cascader 用于省市区 / 分类等级联数据. 选中项高亮用品牌色 + 12% 背景.') }}</p>
        <el-form :model="showcaseSelect" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.select.single', '单选')">
            <el-select v-model="showcaseSelect.single" :placeholder="t('spec.forms.showcase.select.singlePh', '请选择')" style="width: 100%">
              <el-option label="Apple HIG" value="apple" />
              <el-option label="Mchuan" value="mchuan" />
              <el-option label="Google Material" value="google" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.select.multiple', '多选')">
            <el-select v-model="showcaseSelect.multiple" multiple collapse-tags collapse-tags-tooltip :placeholder="t('spec.forms.showcase.select.multiplePh', '请选择 (可多选)')" style="width: 100%">
              <el-option label="Vue 3" value="vue" />
              <el-option label="TypeScript" value="ts" />
              <el-option label="Vite" value="vite" />
              <el-option label="Element Plus" value="ep" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.select.disabled', '禁用')">
            <el-select v-model="showcaseSelect.disabled" disabled placeholder="禁用态" style="width: 100%">
              <el-option label="Disabled" value="x" />
            </el-select>
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.3 radio + checkbox + radio-group + checkbox-group -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-checkbox-multiple-line" />
            <span>{{ t('spec.forms.showcase.radio.title', '5.5.3 el-radio + el-checkbox — 单选 / 多选 / 按钮组 / 边框样式') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.radio.desc', 'radio 用于互斥单选 (性别 / 状态), checkbox 用于多选 (通知偏好). button 样式 (el-radio-button / el-checkbox-button) 用在标签页式选择, border 样式强调视觉.') }}</p>
        <el-form :model="showcaseRadio" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.radio.group', '单选组')">
            <el-radio-group v-model="showcaseRadio.radio">
              <el-radio value="a">男</el-radio>
              <el-radio value="b">女</el-radio>
              <el-radio value="c">保密</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.radio.button', '按钮组')">
            <el-radio-group v-model="showcaseRadio.view">
              <el-radio-button value="list">列表</el-radio-button>
              <el-radio-button value="grid">网格</el-radio-button>
              <el-radio-button value="table">表格</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.radio.checkbox', '多选')">
            <el-checkbox-group v-model="showcaseRadio.checkbox">
              <el-checkbox value="email">邮件</el-checkbox>
              <el-checkbox value="sms">短信</el-checkbox>
              <el-checkbox value="push">推送</el-checkbox>
              <el-checkbox value="inapp">站内</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.radio.border', '边框样式')">
            <el-radio-group v-model="showcaseRadio.border">
              <el-radio value="1" border>白天</el-radio>
              <el-radio value="2" border>夜间</el-radio>
              <el-radio value="3" border>自动</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.4 switch + slider -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-toggle-line" />
            <span>{{ t('spec.forms.showcase.toggle.title', '5.5.4 el-switch + el-slider — 二元开关 + 数值范围') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.toggle.desc', 'switch 用于"开/关"二元状态 (通知 / 免打扰), slider 用于数值范围 (音量 / 宽度 / 价格). 打开态走品牌渐变, thumb 是白色圆点.') }}</p>
        <el-form :model="showcaseSwitch" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.toggle.basic', '基础开关')">
            <el-switch v-model="showcaseSwitch.basic" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.toggle.text', '带文案')">
            <el-switch v-model="showcaseSwitch.text" active-text="开" inactive-text="关" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.toggle.disabled', '禁用')">
            <el-switch v-model="showcaseSwitch.disabled" disabled />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.toggle.slider', '数值滑块')">
            <el-slider v-model="showcaseSwitch.slider" :min="0" :max="100" show-input style="max-width: 360px" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.toggle.range', '范围')">
            <el-slider v-model="showcaseSwitch.range" range :min="0" :max="100" style="max-width: 360px" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.5 date-picker + time-picker + time-select -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-calendar-line" />
            <span>{{ t('spec.forms.showcase.date.title', '5.5.5 el-date-picker + el-time-picker — 日期 / 时间 / 日期时间') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.date.desc', 'date-picker 支持 date / datetime / daterange 3 种模式; time-picker 用于时刻选择 (会议提醒). 当天单元格用品牌渐变高亮, 跟其他组件保持视觉一致.') }}</p>
        <el-form :model="showcaseDate" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.date.date', '日期')">
            <el-date-picker v-model="showcaseDate.date" type="date" :placeholder="t('spec.forms.showcase.date.datePh', '请选择日期')" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.date.datetime', '日期时间')">
            <el-date-picker v-model="showcaseDate.datetime" type="datetime" :placeholder="t('spec.forms.showcase.date.datetimePh', '请选择日期时间')" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.date.range', '日期范围')">
            <el-date-picker v-model="showcaseDate.range" type="daterange" :start-placeholder="t('spec.forms.showcase.date.start', '开始')" :end-placeholder="t('spec.forms.showcase.date.end', '结束')" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.date.time', '时间')">
            <el-time-picker v-model="showcaseDate.time" :placeholder="t('spec.forms.showcase.date.timePh', '请选择时间')" style="width: 100%" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.6 el-input-number + el-cascader + el-tree-select — 数值 / 级联 / 树下拉 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-add-circle-line" />
            <span>{{ t('spec.forms.showcase.number.title', '5.5.6 el-input-number + el-cascader + el-tree-select — 数值 / 级联 / 树形下拉') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.number.desc', 'el-input-number 是带步进器的数字输入 (库存 / 数量 / 排序权重); el-cascader 用于省市区 / 分类等级联; el-tree-select 把 el-tree 嵌入 select, 用于组织架构 / 文件路径.') }}</p>
        <el-form label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.number.basic', '基础数值')">
            <el-input-number v-model="showcaseNumber.count" :min="1" :max="99" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.number.percent', '百分比')">
            <el-input-number v-model="showcaseNumber.percent" :min="0" :max="100" :step="5" />
            <span class="forms-demo-label">%</span>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.number.cascader', '级联')">
            <el-cascader v-model="showcaseCascader" :options="treeData" :placeholder="t('spec.forms.showcase.number.cascaderPh', '请选择分类')" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.number.tree', '树下拉')">
            <el-tree-select v-model="showcaseTreeSelect" :data="treeData" :props="{ label: 'label', value: 'value' }" :placeholder="t('spec.forms.showcase.number.treePh', '请选择节点')" check-strictly style="width: 100%" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.7 el-color-picker + el-rate + el-segmented — 颜色 / 评分 / 分段 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-palette-line" />
            <span>{{ t('spec.forms.showcase.color.title', '5.5.7 el-color-picker + el-rate + el-segmented — 颜色 / 评分 / 分段控件') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.color.desc', 'el-color-picker 选色 (主题定制); el-rate 5 星评分 (评价 / 满意度); el-segmented 分段控件 (视图切换 / 排序方式), 选中态走品牌色.') }}</p>
        <el-form label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.color.basic', '基础颜色')">
            <el-color-picker v-model="showcaseColor" />
            <span class="forms-demo-label">{{ showcaseColor }}</span>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.color.rate', '评分')">
            <el-rate v-model="showcaseRate" />
            <span class="forms-demo-label">{{ showcaseRate }} / 5</span>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.color.segmented', '视图切换')">
            <el-segmented v-model="showcaseSegmented" :options="[
              { label: '列表', value: 'list' },
              { label: '网格', value: 'grid' },
              { label: '看板', value: 'kanban' },
            ]" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.8 el-upload + el-transfer — 上传 / 穿梭框 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-upload-cloud-line" />
            <span>{{ t('spec.forms.showcase.upload.title', '5.5.8 el-upload + el-transfer — 文件上传 / 穿梭框') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.upload.desc', 'el-upload 拖拽 / 点击上传, 显示文件列表; el-transfer 用于左右选择 (角色 / 标签 / 成员分配). 都支持 disabled / 自定义文案.') }}</p>
        <el-form label-width="100px" style="max-width: 720px">
          <el-form-item :label="t('spec.forms.showcase.upload.file', '文件上传')">
            <el-upload :file-list="showcaseFileList" :auto-upload="false" :on-change="(f: any) => showcaseFileList.push({ name: f.name })" multiple action="#">
              <el-button type="primary">{{ t('spec.forms.showcase.upload.pickFile', '选择文件') }}</el-button>
              <template #tip>
                <div class="el-upload__tip">{{ t('spec.forms.showcase.upload.tip', '支持拖拽 / 点击上传, 仅展示用, 不实际上传') }}</div>
              </template>
            </el-upload>
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.upload.transfer', '角色分配')">
            <el-transfer v-model="showcaseTransfer" :data="transferData" :titles="[t('spec.forms.showcase.upload.unassigned', '未分配'), t('spec.forms.showcase.upload.assigned', '已分配')]" />
          </el-form-item>
        </el-form>
      </RowCard>

      <!-- 5.5.9 el-button 全套 type + 状态 + el-link — 按钮与链接 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-mouse-line" />
            <span>{{ t('spec.forms.showcase.button.title', '5.5.9 el-button 全套 type + 状态 + el-link — 按钮与链接') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.button.desc', 'button 5 种 type (primary / success / warning / danger / info) + 3 种变体 (plain / text / link) + loading / disabled / icon. el-link 3 种 type + underline / disabled. 整行 hover / 焦点环用品牌色.') }}</p>
        <div class="forms-demo-stack">
          <div class="forms-demo-row">
            <el-button type="primary">Primary</el-button>
            <el-button type="success">Success</el-button>
            <el-button type="warning">Warning</el-button>
            <el-button type="danger">Danger</el-button>
            <el-button type="info">Info</el-button>
          </div>
          <div class="forms-demo-row">
            <el-button type="primary" plain>Plain</el-button>
            <el-button type="primary" text>Text</el-button>
            <el-button type="primary" link>Link</el-button>
            <el-button type="primary" disabled>Disabled</el-button>
            <el-button type="primary" :loading="showcaseLoading" @click="toggleLoading">Loading</el-button>
          </div>
          <div class="forms-demo-row">
            <el-button type="primary" circle><i class="ri-search-line" /></el-button>
            <el-button type="primary" round>Round</el-button>
            <el-button :icon="undefined" type="primary"><i class="ri-edit-line" style="margin-right: 4px" />Icon + text</el-button>
          </div>
          <el-divider />
          <div class="forms-demo-row">
            <el-link type="primary" href="#">Primary link</el-link>
            <el-link type="success" href="#">Success link</el-link>
            <el-link type="warning" href="#">Warning link</el-link>
            <el-link type="danger" href="#">Danger link</el-link>
            <el-link type="info" :underline="false">No underline</el-link>
            <el-link type="primary" disabled>Disabled</el-link>
          </div>
        </div>
      </RowCard>

      <!-- 5.5.10 el-form 验证态 + el-empty 表单空态 — 验证 + 空态 -->
      <RowCard>
        <template #header>
          <h4 class="rule-title">
            <i class="ri-shield-check-line" />
            <span>{{ t('spec.forms.showcase.valid.title', '5.5.10 el-form 验证态 + el-empty 表单内空态 — 验证反馈与空态') }}</span>
          </h4>
        </template>
        <p class="rule-desc">{{ t('spec.forms.showcase.valid.desc', '完整 form 验证示例: required + email 校验, 错误时输入框 1px 红 + 红字提示. 验证通过后 ElMessage 成功. 下方 el-empty 用于表单内"无数据"场景 (搜索结果空 / 列表空).') }}</p>
        <el-form ref="showcaseValidRef" :model="showcaseValidForm" :rules="showcaseValidRules" label-width="100px" style="max-width: 560px">
          <el-form-item :label="t('spec.forms.showcase.valid.user', '用户名')" prop="user">
            <el-input v-model="showcaseValidForm.user" :placeholder="t('spec.forms.showcase.valid.userPh', 'blur 触发 required 校验')" />
          </el-form-item>
          <el-form-item :label="t('spec.forms.showcase.valid.email', '邮箱')" prop="email">
            <el-input v-model="showcaseValidForm.email" :placeholder="t('spec.forms.showcase.valid.emailPh', 'blur 触发 email 格式校验')" />
          </el-form-item>
          <el-form-item label=" ">
            <el-checkbox v-model="showcaseValidForm.agree">{{ t('spec.forms.showcase.valid.agree', '我已阅读并同意服务条款') }}</el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="onValidSubmit">{{ t('spec.forms.showcase.valid.submit', '提交') }}</el-button>
            <el-button @click="showcaseValidRef?.resetFields()">{{ t('spec.forms.showcase.valid.reset', '重置') }}</el-button>
          </el-form-item>
        </el-form>
        <el-divider />
        <el-empty :description="t('spec.forms.showcase.valid.empty', '搜索结果为空')" />
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

/* Demo row: flex 容器, gap 16px, 自动换行 */
.forms-demo-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.forms-demo-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.forms-demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px;
}
.forms-demo-label {
  font-size: 12px;
  color: var(--app-text-tertiary);
  font-family: 'Fira Code', 'Menlo', monospace;
}

/* §5.3.1 紧凑 form: 强制覆盖 --el-form-item-margin-bottom */
.forms-compact :deep(.el-form-item) {
  margin-bottom: 8px;
}

/* §5.3.2 radius marker: 显示当前 brand 圆角值 */
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
}
.forms-radius-marker strong {
  color: var(--app-primary);
  font-weight: 600;
  font-family: 'Fira Code', 'Menlo', monospace;
}

/* §5.3.3 shadow demo: 3 张卡片 */
.shadow-card {
  width: 140px;
  height: 80px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-md);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  font-size: 11px;
  color: var(--app-text-secondary);
}
.shadow-card i { font-size: 20px; color: var(--app-primary); }
.shadow-card--sm { box-shadow: var(--app-shadow-sm); }
.shadow-card--md { box-shadow: var(--app-shadow-md); }
.shadow-card--lg { box-shadow: var(--app-shadow-lg); }

/* §5.3.3 对照: EP 默认 box-shadow focus (项目不用, 仅对比) */
.forms-shadow-focus :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset !important;
}

/* §5.3.4 错误态: 1px 红 + 红字 */
.forms-error-state :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) !important;
}
.forms-error-hint {
  font-size: 12px;
  color: var(--el-color-danger);
  line-height: 1;
}

/* §5.2 变量对照表 */
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
  .forms-demo-grid { grid-template-columns: 1fr; gap: 16px; }
  .var-table { font-size: 11px; }
  .var-table th, .var-table td { padding: 6px 8px; }
}
</style>
