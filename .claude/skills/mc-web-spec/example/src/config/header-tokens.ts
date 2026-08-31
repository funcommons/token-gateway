// src/config/header-tokens.ts
//
// 顶部栏 (AppHeader) 全局常量 — 不可修改, 编译时锁定.
// 配套 CSS 变量 (主题可改) 见 src/styles/_variables.scss 中的 --app-header-* 段.

import { BREAKPOINTS } from '@/composables/useResponsive'

export const HEADER_TOKENS = {
  // ========== 规格 (3.2.1) ==========
  // 规格 3.2.1: AppHeader 必须 z-index 1000, 高于 sidebar / drawer
  Z_INDEX: 1000,

  // ========== 响应式断点 (与 BREAKPOINTS.tablet 同源) ==========
  // @media 断点, 用于隐藏 logo 副标题 / 中心搜索框
  MOBILE_BREAKPOINT_PX: BREAKPOINTS.tablet,  // 768

  // ========== Avatar 尺寸 (作为 el-avatar :size prop) ==========
  AVATAR_SIZE: 32,

  // ========== 通知未读数 (示例数据) ==========
  // 仅用于演示, 真实数据从 API 注入
  DEMO_NOTIFICATION_COUNT: 3,
} as const

export type HeaderTokens = typeof HEADER_TOKENS
