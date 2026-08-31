/**
 * useSidebarNavItems - SDK 默认 nav 数据 composable.
 *
 * 业务接 vue-router + i18n 后, 一行调用即可获得响应式 NavItem[].
 *
 * 内部使用 useRouteAccess (过滤) + buildNavItems (组装), 业务只需传 group 规则.
 *
 * 用法:
 *   const items = useSidebarNavItems({
 *     iconResolver: (name) => resolveIcon(name),
 *     features: isFeatureEnabled,
 *     groups: [
 *       { id: 'plaza', labelKey: 'sidebar.plaza', iconName: 'Files', routeNames: ['Plaza', 'TemplatePlaza'] },
 *       { id: 'create', labelKey: 'sidebar.create', iconName: 'MagicStick', routeNamePrefix: 'Create' },
 *     ],
 *     topLevels: [
 *       { routeNames: ['Home', 'Chat'] },
 *     ],
 *   })
 *
 * 业务侧需要扩展 (如根据 user role 显隐 group) 时, 直接调底层的 useRouteAccess + buildNavItems.
 */
import { computed, type ComputedRef } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { Component } from 'vue'
import type { NavItem } from './FcSidebarNav.vue'
import { useRouteAccess, type RouteAccessOptions } from './useRouteAccess'
import { buildNavItems, type NavGroupRule, type NavTopLevelRule } from './buildNavItems'

export interface UseSidebarNavOptions {
  /** icon name -> Component. 业务接 @element-plus/icons-vue 的 resolveIcon. */
  iconResolver: (name: string) => Component | null | undefined
  /** meta.feature 校验 (返回 false 时该路由被排除). 例: isFeatureEnabled */
  features?: (featureKey: string) => boolean
  /** 完全自定义过滤 (在 features 之后跑). 例: r => !r.meta?.hideInMenu */
  customFilter?: (route: import('vue-router').RouteRecordNormalized) => boolean
  /** sub-menu 分组规则. */
  groups?: NavGroupRule[]
  /** 顶层叶子 (不分组, 直接放菜单). */
  topLevels?: NavTopLevelRule[]
  /** 自定义 routes 源 (默认 router.getRoutes()). */
  routes?: import('vue-router').RouteRecordNormalized[]
}

export function useSidebarNavItems(
  options: UseSidebarNavOptions,
): ComputedRef<NavItem[]> {
  const router = useRouter()
  const { t } = useI18n()

  const accessOptions: RouteAccessOptions = {
    requireTitle: true,
    features: options.features,
    customFilter: options.customFilter,
  }

  const accessibleRoutes = useRouteAccess(router, accessOptions)

  return computed(() =>
    buildNavItems({
      routes: options.routes ?? accessibleRoutes.value,
      t,
      iconResolver: options.iconResolver,
      groups: options.groups ?? [],
      topLevels: options.topLevels ?? [],
    }),
  )
}