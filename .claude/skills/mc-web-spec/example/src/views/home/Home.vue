<script setup lang="ts">
// 首页 — 5 大功能块 (Welcome / Stats / Recommend+Recent / Account / Courses+Todos)
// 顶部: HeaderSection (含 welcome 横幅); 其余: TitledSection / KpiLayout.
import { onMounted, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { userApi } from '@/api/user'
import { courseApi } from '@/api/course'
import { learningApi } from '@/api/learning'
import { useUserStore } from '@/stores/user'
import type { Course, LearningStat, TimelineItem } from '@/types/api'
import { formatNumber, formatDateTime } from '@/utils/format'
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import KpiLayout from '@/components/sdk/common/KpiLayout.vue'
import KpiSection from '@/components/sdk/common/KpiSection.vue'

const { t } = useI18n()
const userStore = useUserStore()

const stats = ref<LearningStat[]>([])
const recommend = ref<Course[]>([])
const timeline = ref<TimelineItem[]>([])
const loading = ref(true)

// KPI 卡 theme 序列 (manyun 三色生态): 1=green 2=brown 3=gold 4=green.
// 其它品牌 (单色) 不传 → KpiSection 默认 green, 行为不变.
const kpiThemes = ['green', 'brown', 'gold', 'green'] as const

interface Todo {
  id: number
  type: 'exam' | 'homework' | 'review'
  title: string
  due: string
}
const todos = ref<Todo[]>([
  { id: 1, type: 'exam', title: 'Vue 3 进阶开发实战', due: '明天 14:00' },
  { id: 2, type: 'homework', title: 'TypeScript 高级类型系统 第 4 章作业', due: '本周五' },
  { id: 3, type: 'review', title: '微前端架构实战', due: '本周末' },
  { id: 4, type: 'exam', title: 'Node.js 实战认证', due: '下周一' },
  { id: 5, type: 'homework', title: 'MySQL 性能调优 实验报告', due: '下周三' },
])
const myCoursesProgress = ref<{ id: number; title: string; cover: string; progress: number; lastView: string }[]>([
  { id: 1, title: 'Web 性能优化全攻略', cover: '/images/courses/1.png', progress: 78, lastView: '2 天前' },
  { id: 2, title: '大语言模型应用开发', cover: '/images/courses/2.png', progress: 45, lastView: '昨天' },
  { id: 3, title: 'TypeScript 高级类型系统', cover: '/images/courses/3.png', progress: 100, lastView: '上周' },
  { id: 4, title: '微前端架构实战', cover: '/images/courses/4.png', progress: 23, lastView: '3 天前' },
  { id: 5, title: 'Node.js 性能调优', cover: '/images/courses/5.png', progress: 67, lastView: '今天' },
])

const todoIcon = (type: Todo['type']) => {
  if (type === 'exam') return 'ri-file-shield-2-line'
  if (type === 'homework') return 'ri-edit-box-line'
  return 'ri-book-read-line'
}
const todoTypeLabel = (type: Todo['type']) => {
  if (type === 'exam') return t('common.exam')
  if (type === 'homework') return t('common.homework')
  return t('common.review')
}

const user = computed(() => userStore.current)

onMounted(async () => [
  userApi.getCurrent().then((u) => userStore.setCurrent(u)),
  learningApi.getStats().then((d) => (stats.value = d)),
  courseApi.getRecommend().then((d) => (recommend.value = d)),
  learningApi.getTimeline().then((d) => (timeline.value = d)),
])

// simulated minimal delay for skeleton showcase
onMounted(() => {
  setTimeout(() => (loading.value = false), 500)
})

const welcomeMsg = computed(() => {
  const name = userStore.current?.name ?? t('common.peer')
  return t('home.welcome', { name })
})
</script>

<template>
  <div class="app-page home-page">
    <HeaderSection :title="t('nav.pages.home')" :subtitle="welcomeMsg">
      <template #welcome>
        <div class="welcome">
          <p class="welcome__sub">{{ t('app.subtitle') }} · {{ t('app.welcome') }}</p>
          <i class="ri-book-open-line welcome__brand" />
        </div>
      </template>
    </HeaderSection>

    <!-- §1 学习数据: 4 个 KPI 用 <KpiLayout> + <KpiSection> (移动 2 列 / 桌面 4 列)
         manyun 三色生态: 给每张卡绑 theme (green/gold/brown) 让 icon 底色 +
         顶部色条 + trend pill 跟着切色. 其它品牌不传 → 默认 green (主色). -->
    <KpiLayout :columns="4" :mobile-columns="2">
      <KpiSection
        v-for="(s, idx) in stats"
        :key="s.label"
        :title="s.label"
        :icon="s.icon"
        :value="s.value"
        :unit="s.unit"
        :trend="s.trend"
        :theme="kpiThemes[idx] || 'green'"
      />
    </KpiLayout>

    <!-- §2 账户信息 (从 Profile 移过来) -->
    <TitledSection title="账户信息" description="基础资料 · 头像 · 联系方式">
      <div class="home-account__inner">
        <el-avatar v-if="user" :size="72" :src="user.avatar" />
        <div v-if="user" class="home-account__info">
          <div class="home-account__name">{{ user.name }}</div>
          <div class="home-account__role">{{ user.role }} · {{ user.department }}</div>
          <div class="home-account__id">ID: {{ user.id }} · {{ user.email }}</div>
        </div>
        <el-skeleton v-else :rows="2" animated />
      </div>
      <div class="home-account__actions">
        <el-button type="primary" plain>
          <i class="ri-edit-line" /> 编辑资料
        </el-button>
        <el-button plain>
          <i class="ri-lock-password-line" /> 修改密码
        </el-button>
      </div>
    </TitledSection>

    <!-- §3 推荐课程 + 最近学习 (2-column grid, 参考 profile-grid) -->
    <div class="home-grid-2col">
      <TitledSection :title="t('home.recommend')" :description="t('home.recommendDesc', '本周精选 · 推荐给你')">
        <el-skeleton v-if="loading" :rows="3" animated />
        <el-carousel v-else :interval="4000" type="card" height="180px" indicator-position="outside">
          <el-carousel-item v-for="c in recommend" :key="c.id">
            <div class="home-slide">
              <img :src="c.cover" :alt="c.title" class="home-slide__img" />
              <div class="home-slide__overlay">
                <h3>{{ c.title }}</h3>
                <p>{{ c.description }}</p>
                <div class="home-slide__meta">
                  <el-tag size="small" type="info">{{ c.instructor }}</el-tag>
                  <el-rate v-model="c.rating" disabled show-score text-color="#ff9900" :score-template="String(c.rating)" />
                </div>
              </div>
            </div>
          </el-carousel-item>
        </el-carousel>
      </TitledSection>

      <TitledSection :title="t('home.recent')" :description="t('home.recentDesc', '最近 6 条学习动态')">
        <el-timeline>
          <el-timeline-item
            v-for="item in timeline"
            :key="item.id"
            :timestamp="formatDateTime(item.timestamp)"
            :type="item.type"
            placement="top"
          >
            <h4 class="home-timeline__title">{{ item.title }}</h4>
            <p class="home-timeline__desc">{{ item.description }}</p>
          </el-timeline-item>
        </el-timeline>
      </TitledSection>
    </div>

    <!-- §4 我的课程 + 待办事项 (从 Profile 移过来, 2 列布局) -->
    <div class="home-grid-2col">
      <TitledSection title="我的课程" :description="`进行中 / 已完成 ${myCoursesProgress.length} 门`">
        <ul class="home-course">
          <li v-for="c in myCoursesProgress" :key="c.id" class="home-course__row">
            <el-image :src="c.cover" class="home-course__cover" fit="cover" />
            <div class="home-course__body">
              <div class="home-course__title">{{ c.title }}</div>
              <el-progress
                :percentage="c.progress"
                :status="c.progress === 100 ? 'success' : ''"
                :stroke-width="6"
              />
              <div class="home-course__meta">
                <span><i class="ri-time-line" /> {{ c.lastView }}</span>
                <el-button text type="primary" size="small">继续学习</el-button>
              </div>
            </div>
          </li>
        </ul>
      </TitledSection>

      <TitledSection title="待办事项" :description="`${todos.length} 项待办`">
        <ul class="home-todo">
          <li v-for="t in todos" :key="t.id" class="home-todo__item">
            <i :class="todoIcon(t.type)" class="home-todo__icon" />
            <div class="home-todo__body">
              <div class="home-todo__title">{{ t.title }}</div>
              <div class="home-todo__meta">
                <el-tag size="small" :type="t.type === 'exam' ? 'danger' : t.type === 'homework' ? 'warning' : 'info'" effect="plain">
                  {{ todoTypeLabel(t.type) }}
                </el-tag>
                <span class="home-todo__due">{{ t.due }}</span>
              </div>
            </div>
          </li>
        </ul>
      </TitledSection>
    </div>
  </div>
</template>

<style scoped>
.home-page {
  max-width: 1280px;
}

/* §1 Welcome banner — inside HeaderSection (#welcome slot), 渐变条带合并到标题卡内 */
.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, var(--app-primary) 0%, var(--el-color-primary-dark-2, var(--app-primary)) 100%);
  color: white;
  border-radius: var(--app-radius-sm);
  padding: 16px 20px;
}
.welcome__sub {
  margin: 0;
  font-size: 13px;
  opacity: 0.95;
}
.welcome__brand {
  font-size: 40px;
  opacity: 0.4;
  flex-shrink: 0;
  margin-left: 12px;
}

/* §1 KPI 区 CSS 已移到 @/components/common/KpiLayout.vue + KpiSection.vue */

/* §2 2-column grid (推荐+最近) — 参考 profile-grid */
.home-grid-2col {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--app-block-mb);
  align-items: start;
}
@media (max-width: 1024px) { .home-grid-2col { grid-template-columns: 1fr; } }

/* §2 carousel slide */
.home-slide {
  position: relative;
  height: 100%;
  border-radius: var(--app-radius-md);
  overflow: hidden;
}
.home-slide__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.home-slide__overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(0deg, rgba(0, 0, 0, 0.7) 0%, rgba(0, 0, 0, 0) 60%);
  padding: 16px 20px;
  color: white;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}
.home-slide__overlay h3 { margin: 0 0 4px; font-size: 18px; color: white; }
.home-slide__overlay p { margin: 0 0 8px; font-size: 12px; opacity: 0.9; }
.home-slide__meta { display: flex; align-items: center; gap: 12px; }

/* §2 timeline */
.home-timeline__title { margin: 0 0 4px; font-size: 14px; }
.home-timeline__desc { margin: 0; color: var(--app-text-secondary); font-size: 12px; }

/* §3 账户信息 (从 Profile 搬过来) */
.home-account__inner {
  display: flex;
  align-items: center;
  gap: 16px;
}
.home-account__info { flex: 1; min-width: 0; }
.home-account__name { font-size: 18px; font-weight: 600; color: var(--app-text); }
.home-account__role { font-size: 13px; color: var(--app-text-secondary); margin-top: 2px; }
.home-account__id { font-size: 12px; color: var(--app-text-tertiary); margin-top: 4px; }
.home-account__actions { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }

/* §4 我的课程 (从 Profile 搬过来) — 流体卡片行, 无固定宽度无横向滚动 */
.home-course { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.home-course__row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  min-width: 0;
}
.home-course__row:last-child { border-bottom: none; }
.home-course__cover {
  width: 64px; height: 40px;
  border-radius: 4px; flex-shrink: 0;
  background: var(--el-fill-color-light);
}
.home-course__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.home-course__title {
  font-size: 13px; color: var(--app-text); font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.home-course__meta {
  display: flex; align-items: center; justify-content: space-between;
  gap: 8px; font-size: 12px; color: var(--app-text-tertiary);
}
@media (max-width: 480px) {
  .home-course__row { flex-direction: column; align-items: stretch; gap: 8px; }
  .home-course__cover { width: 100%; height: 80px; }
}

/* §4 待办事项 (从 Profile 搬过来) — 跟 §2 timeline 同构的竖向列表 */
.home-todo { list-style: none; margin: 0; padding: 0; }
.home-todo__item {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  min-width: 0;
}
.home-todo__item:last-child { border-bottom: none; }
.home-todo__icon { font-size: 18px; color: var(--app-primary); flex-shrink: 0; margin-top: 2px; }
.home-todo__body { flex: 1; min-width: 0; }
.home-todo__title {
  font-size: 13px; color: var(--app-text); font-weight: 500;
  margin-bottom: 4px;
  word-break: break-word;
}
.home-todo__meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text-tertiary); flex-wrap: wrap; }
</style>