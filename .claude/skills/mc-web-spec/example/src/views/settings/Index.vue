<script setup lang="ts">
// 设置页 — 7 个 tab (个人资料 / 通知 / 隐私 / 账户 / 集成 / 外观 / 关于).
// 容器封装在 @/components/common/AppSettingsTabs (视觉壳 + tabPosition 自适应 + 移动端紧凑布局),
// 这里只声明 tab 列表 + 各自内容, 重复的 el-tabs CSS / 标签壳样式都搬到了组件里.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { tOr } from '@/locales'
import { usePreferenceStore } from '@/stores/preference'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'
import AppSettingsTabs from '@/components/sdk/common/AppSettingsTabs.vue'

const { t } = useI18n()
const router = useRouter()
const preference = usePreferenceStore()

const activeTab = ref('profile')

const tabs = [
  { key: 'profile',      label: '个人资料',   icon: 'ri-user-line' },
  { key: 'notification', label: '通知设置',   icon: 'ri-notification-line' },
  { key: 'privacy',      label: '隐私与安全', icon: 'ri-shield-keyhole-line' },
  { key: 'account',      label: '账户管理',   icon: 'ri-bank-card-line' },
  { key: 'integration',  label: '第三方集成', icon: 'ri-plug-line' },
  { key: 'appearance',   label: '外观',       icon: 'ri-palette-line' },
  { key: 'about',        label: '关于',       icon: 'ri-information-line' },
]

function onSave() {
  ElMessage.success('设置已保存')
}

function openAppearance() {
  preference.setShowAppearance(true)
}
</script>

<template>
  <div class="app-page">
    <HeaderSection :title="t('nav.pages.settings')" :subtitle="t('app.subtitle')" back @back="router.back()" />

    <AppSettingsTabs v-model:active="activeTab" :tabs="tabs">
      <!-- 个人资料 -->
      <template #profile>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.3', '基础资料')" :description="tOr('demo.index.description.0', '修改后需点击保存才能生效')">
            <RowCard :title="tOr('demo.index.title.4', '姓名 / 邮箱')">
              <el-form label-width="100px" style="max-width: 480px">
                <el-form-item :label="tOr('demo.index.label.22', '姓名')">
                  <el-input model-value="陈晓宇" />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.23', '邮箱')">
                  <el-input model-value="chenxiaoyu@company.com" />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.24', '手机')">
                  <el-input model-value="138****8888" />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.25', '工号')">
                  <el-input model-value="u001" disabled />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.26', '所属部门')">
                  <el-input model-value="技术中台 / 前端组" disabled />
                </el-form-item>
              </el-form>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.5', '个人简介')">
              <el-input type="textarea" :rows="4" model-value="高级前端工程师,负责内部学习系统与组件库建设。" />
            </RowCard>
            <el-button type="primary" @click="onSave">保存</el-button>
          </TitledSection>
        </div>
      </template>

      <!-- 通知设置 -->
      <template #notification>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.6', '通知偏好')" :description="tOr('demo.index.description.1', '选择您希望接收的通知类型')">
            <RowCard :title="tOr('demo.index.title.7', '邮件通知')">
              <el-checkbox-group :model-value="['exam', 'course']">
                <el-checkbox value="exam">考试通知</el-checkbox>
                <el-checkbox value="course">新课程上架</el-checkbox>
                <el-checkbox value="announcement">系统公告</el-checkbox>
                <el-checkbox value="digest">每周学习摘要</el-checkbox>
              </el-checkbox-group>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.8', '站内通知')">
              <el-checkbox-group :model-value="['mention', 'reply']">
                <el-checkbox value="mention">@ 提及我</el-checkbox>
                <el-checkbox value="reply">回复我的</el-checkbox>
                <el-checkbox value="like">收到点赞</el-checkbox>
              </el-checkbox-group>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.9', '免打扰时段')" :description="tOr('demo.index.description.2', '在此时间段内不发送推送')">
              <el-time-picker is-range range-separator="至" start-placeholder="tOr('demo.index.placeholder.36', '开始')" end-placeholder="tOr('demo.index.placeholder.37', '结束')" />
            </RowCard>
            <el-button type="primary" @click="onSave">保存</el-button>
          </TitledSection>
        </div>
      </template>

      <!-- 隐私与安全 -->
      <template #privacy>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.10', '隐私选项')">
            <RowCard :title="tOr('demo.index.title.11', '个人资料可见性')">
              <el-radio-group :model-value="'team'">
                <el-radio value="public">所有人</el-radio>
                <el-radio value="team">仅本部门</el-radio>
                <el-radio value="private">仅自己</el-radio>
              </el-radio-group>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.12', '登录安全')">
              <el-form label-width="160px">
                <el-form-item :label="tOr('demo.index.label.27', '两步验证')">
                  <el-switch :model-value="true" />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.28', '异地登录提醒')">
                  <el-switch :model-value="true" />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.29', '设备管理')">
                  <el-button>查看已登录设备 (3)</el-button>
                </el-form-item>
              </el-form>
            </RowCard>
            <el-button type="primary" @click="onSave">保存</el-button>
          </TitledSection>
        </div>
      </template>

      <!-- 账户管理 -->
      <template #account>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.13', '账户管理')">
            <RowCard :title="tOr('demo.index.title.14', '修改密码')">
              <el-form label-width="100px" style="max-width: 400px">
                <el-form-item :label="tOr('demo.index.label.30', '当前密码')">
                  <el-input type="password" show-password />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.31', '新密码')">
                  <el-input type="password" show-password />
                </el-form-item>
                <el-form-item :label="tOr('demo.index.label.32', '确认新密码')">
                  <el-input type="password" show-password />
                </el-form-item>
              </el-form>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.15', '数据导出与注销')">
              <el-button>导出我的学习数据</el-button>
              <el-button type="danger" plain>申请注销账户</el-button>
            </RowCard>
          </TitledSection>
        </div>
      </template>

      <!-- 第三方集成 -->
      <template #integration>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.16', '第三方服务')">
            <RowCard :title="tOr('demo.index.title.17', '已绑定应用')">
              <el-row :gutter="16">
                <el-col :span="6"><el-tag type="success" effect="dark" size="large">微信 已绑定</el-tag></el-col>
                <el-col :span="6"><el-tag type="success" effect="dark" size="large">钉钉 已绑定</el-tag></el-col>
                <el-col :span="6"><el-tag type="info" size="large">飞书 未绑定</el-tag></el-col>
                <el-col :span="6"><el-tag type="info" size="large">GitHub 未绑定</el-tag></el-col>
              </el-row>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.18', 'API 访问令牌')">
              <el-input model-value="lp_a1b2c3d4e5..." readonly>
                <template #append><el-button>复制</el-button></template>
              </el-input>
            </RowCard>
          </TitledSection>
        </div>
      </template>

      <!-- 外观 -->
      <template #appearance>
        <div class="settings-content">
          <TitledSection :title="tOr('settings.appearance.title', '外观设置')" :description="tOr('settings.appearance.desc', '主题 / 品牌 / 语言 / 侧栏宽度')">
            <RowCard :title="tOr('settings.appearance.cardTitle', '打开外观面板')" :description="tOr('settings.appearance.cardDesc', '主题、品牌、语言、侧栏宽度等设置已迁移到右侧浮层中')">
              <el-button type="primary" @click="openAppearance">
                <i class="ri-palette-line" style="margin-right: 6px" />
                {{ t('common.appearance') }}
              </el-button>
            </RowCard>
          </TitledSection>
        </div>
      </template>

      <!-- 关于 -->
      <template #about>
        <div class="settings-content">
          <TitledSection :title="tOr('demo.index.title.19', '系统信息')">
            <RowCard :title="tOr('demo.index.title.20', '版本')">
              <el-descriptions :column="1">
                <el-descriptions-item :label="tOr('demo.index.label.33', '应用名称')">{{ t('app.title') }}</el-descriptions-item>
                <el-descriptions-item :label="tOr('demo.index.label.34', '版本号')">v1.0.0</el-descriptions-item>
                <el-descriptions-item :label="tOr('demo.index.label.35', '构建日期')">2026-06-01</el-descriptions-item>
                <el-descriptions-item label="Vue">3.5.x</el-descriptions-item>
                <el-descriptions-item label="Element Plus">2.14.1</el-descriptions-item>
                <el-descriptions-item label="Vite">6.4.x</el-descriptions-item>
              </el-descriptions>
            </RowCard>
            <RowCard :title="tOr('demo.index.title.21', '技术栈')">
              <el-space wrap>
                <el-tag effect="plain">Vue 3</el-tag>
                <el-tag effect="plain" type="primary">TypeScript</el-tag>
                <el-tag effect="plain" type="success">Vite</el-tag>
                <el-tag effect="plain" type="warning">Element Plus 2.14.1</el-tag>
                <el-tag effect="plain" type="info">Pinia</el-tag>
                <el-tag effect="plain" type="info">Vue Router 4</el-tag>
                <el-tag effect="plain" type="info">vue-i18n 9</el-tag>
                <el-tag effect="plain" type="info">Remix Icon</el-tag>
                <el-tag effect="plain" type="info">Playwright</el-tag>
              </el-space>
            </RowCard>
          </TitledSection>
        </div>
      </template>
    </AppSettingsTabs>
  </div>
</template>

<style scoped>
.app-page {
  max-width: 1200px;
}
/* settings-content is a layout slot inside each tab pane — keep
   padding=0, margin=0 per workspace layout rules. The inner TitledSection
   blocks own their own padding. */
.settings-content {
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
}
</style>
