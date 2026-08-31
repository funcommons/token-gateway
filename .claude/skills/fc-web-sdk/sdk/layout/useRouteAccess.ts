/**
 * useRouteAccess - SDK 路由访问控制 composable.
 *
 * 业务侧 useSidebarNavItems / useRouteMeta 等过滤逻辑的共用底座.
 * 提供 4 个维度的过滤钩子:
 *   - requireTitle: 路由必须 meta.title 才纳入 (菜单默认开启)
 *   - features: meta.feature 校验 (返回 boolean)
 *   - roles: 业务 store role 检查 (route + userRole -> boolean)
 *   - customFilter: 业务完全自定义
 *
 * 任一钩子返回 false, 该路由就被排除.
 *
 * 用法:
 *   const accessibleRoutes = useRouteAccess(router, {
 *     features: isFeatureEnabled,
 *     customFilter: r => !r.meta?.hideInMenu,
 *   })
 */
import { computed, type ComputedRef } from 'vue'
import type { RouteRecordNormalized, Router } from 'vue-router'

export interface RouteAccessOptions {
  /** 路由必须有 meta.title 才纳入. 默认 true. */
  requireTitle?: boolean
  /** meta.feature 校验函数. 返回 false 时该路由被排除. (generic key, 业务传具体类型) */
  features?: (featureKey: any) => boolean
  /** 角色校验: 接收 (route, userRole) 返回 boolean. */
  roles?: (route: RouteRecordNormalized, userRole?: string | null) => boolean
  /** 完全自定义过滤器. */
  customFilter?: (route: RouteRecordNormalized) => boolean
}

function defaultFilter(
  route: RouteRecordNormalized,
  options: RouteAccessOptions,
): boolean {
  if (options.requireTitle !== false && !route.meta?.title) return false
  if (options.features) {
    const feature = route.meta?.feature as string | undefined
    if (feature && !options.features(feature)) return false
  }
  if (options.roles) {
    const requiredRoles = route.meta?.roles as string[] | string | undefined
    const userRole = (route.meta as { __userRole?: string } | undefined)?.__userRole
    if (requiredRoles && !options.roles(route, userRole ?? null)) return false
  }
  if (options.customFilter && !options.customFilter(route)) return false
  return true
}

/**
 * 同步版本, 接收路由列表 + options. 业务侧手写 useSidebarNavItems 时可直接复用.
 */
export function filterRoutes(
  routes: RouteRecordNormalized[],
  options: RouteAccessOptions = {},
): RouteRecordNormalized[] {
  return routes.filter((r) => defaultFilter(r, options))
}

/**
 * composable 版本: 接 router, 返回 reactive filtered routes.
 * 依赖响应式 source (route meta / user store), 自动随其变化重算.
 *
 * @example
 *   const accessibleRoutes = useRouteAccess(router, { features: isFeatureEnabled })
 *   const items = computed(() => buildNavItems({ routes: accessibleRoutes.value, ... }))
 */
export function useRouteAccess(
  router: Router,
  options: RouteAccessOptions = {},
): ComputedRef<RouteRecordNormalized[]> {
  return computed(() => {
    const all = router.getRoutes()
    return filterRoutes(all, options)
  })
}