// src/router/routes.ts
import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/home',
    name: 'home',
    component: () => import('@/views/home/Home.vue'),
    meta: { title: 'nav.pages.home', icon: 'ri-home-2-line', group: 'home' },
  },
  // / 重定向到 /home (保留旧的根路径可访问)
  {
    path: '/',
    redirect: '/home',
  },

  // === 规范 (项目级文档) — 放在 sidebar 第一行, 子项: 一、总则 / 二、布局 / ... ===
  {
    path: '/spec/general',
    name: 'spec-general',
    component: () => import('@/views/spec/General.vue'),
    meta: { title: 'nav.pages.spec-general', group: 'spec' },
  },
  {
    path: '/spec/layout',
    name: 'spec-layout',
    component: () => import('@/views/spec/Layout.vue'),
    meta: { title: 'nav.pages.spec-layout', group: 'spec' },
  },
  {
    path: '/spec/material',
    name: 'spec-material',
    component: () => import('@/views/spec/Material.vue'),
    meta: { title: 'nav.pages.spec-material', group: 'spec' },
  },
  {
    path: '/spec/feedback',
    name: 'spec-feedback',
    component: () => import('@/views/spec/Feedback.vue'),
    meta: { title: 'nav.pages.spec-feedback', group: 'spec' },
  },
  {
    path: '/spec/style',
    name: 'spec-style',
    component: () => import('@/views/spec/Style.vue'),
    meta: { title: 'nav.pages.spec-style', group: 'spec' },
  },
  {
    path: '/spec/forms',
    name: 'spec-forms',
    component: () => import('@/views/spec/Forms.vue'),
    meta: { title: 'nav.pages.spec-forms', group: 'spec' },
  },

  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/settings/Index.vue'),
    meta: { title: 'nav.pages.settings', icon: 'ri-settings-3-line', group: 'settings' },
  },
  {
    // 个人中心页已迁入首页, 保留路由 + 菜单入口, 点击跳转首页
    path: '/profile',
    name: 'profile',
    redirect: '/home',
    meta: { title: 'nav.pages.profile', icon: 'ri-user-3-line', group: 'profile' },
  },

  {
    // Catch-all 404. vue-router 4 / path-to-regexp 6+ requires a
    // named capture group for the catch-all (`/:pathMatch(.*)*`) and
    // forbids trailing pattern data after the `*` / `**` element.
    // The simplest valid form is just `/:pathMatch(.*)` which matches
    // any number of segments and exposes them in `route.params.pathMatch`.
    path: '/:pathMatch(.*)',
    name: 'not-found',
    component: () => import('@/views/placeholder/NotFound.vue'),
    meta: { hidden: true },
  },
]
