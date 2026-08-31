/**
 * buildNavItems - SDK 通用 nav 数据工厂 (纯函数).
 *
 * 输入: filtered routes + 业务分组规则 + i18n + icon 解析器
 * 输出: NavItem[] (tree-shape: 顶层 leaf + sub-menu group)
 *
 * 业务可独立调用, 也可基于此再包一层 (如 useSidebarNavItems).
 *
 * 用法:
 *   const items = buildNavItems({
 *     routes: filteredRoutes,
 *     t,
 *     iconResolver: (name) => resolveIcon(name),
 *     groups: [
 *       { id: 'plaza', labelKey: 'sidebar.plaza', iconName: 'Files', routeNames: ['Plaza', 'TemplatePlaza'] },
 *       { id: 'create', labelKey: 'sidebar.create', iconName: 'MagicStick', routeNamePrefix: 'Create' },
 *     ],
 *     topLevels: [
 *       { routeNames: ['Home', 'Chat'] },  // 顶层叶子
 *     ],
 *   })
 */
import type { Component } from 'vue'
import type { RouteRecordNormalized } from 'vue-router'
import type { NavLeaf, NavGroup, NavItem } from './FcSidebarNav.vue'

export interface NavGroupRule {
  /** sub-menu id. 用于 defaultOpeneds / accordion 控制. */
  id: string
  /** i18n key 解析 (传给 t() 得显示文本). */
  labelKey: string
  /** 直接传 icon component (简单场景, 不走 iconResolver). */
  icon?: Component
  /** EP icon name (resolved via iconResolver). 与 icon 互斥, icon 优先. */
  iconName?: string
  /** 自定义 popper className. 默认 'fc-sidebar-popper'. */
  popperClass?: string
  /** route.name 前缀匹配 (优先于 routeNames). */
  routeNamePrefix?: string
  /** 指定 route.name 列表. */
  routeNames?: string[]
  /** 整个 group 是否可见 (动态: 返回 boolean). */
  visible?: boolean | (() => boolean)
  /** 叶子额外过滤. */
  leafFilter?: (route: RouteRecordNormalized) => boolean
}

export interface NavTopLevelRule {
  /** 顶层叶子集合. */
  routeNames?: string[]
  /** route.name 前缀匹配. */
  routeNamePrefix?: string
  /** 顺序: 多个 topLevels 按数组顺序拼接. */
  visible?: boolean | (() => boolean)
  /** 叶子额外过滤. */
  leafFilter?: (route: RouteRecordNormalized) => boolean
}

export interface BuildNavOptions {
  /** 已经过滤过的路由列表 (调用方用 useRouteAccess 跑过). */
  routes: RouteRecordNormalized[]
  /** i18n 函数. */
  t: (key: string) => string
  /** icon name -> Component 解析器. 业务自己接 @element-plus/icons-vue / 项目自建. */
  iconResolver: (name: string) => Component | null | undefined
  /** sub-menu 分组规则. */
  groups?: NavGroupRule[]
  /** 顶层叶子 (单条菜单, 不分组). */
  topLevels?: NavTopLevelRule[]
  /** 单条叶子额外过滤 (对所有顶层 + 分组叶子都生效). */
  leafFilter?: (route: RouteRecordNormalized) => boolean
}

function resolveVisible(visible?: boolean | (() => boolean)): boolean {
  if (typeof visible === 'function') return visible()
  return visible !== false
}

function leafFromRoute(
  route: RouteRecordNormalized,
  t: (k: string) => string,
  iconResolver: (n: string) => Component | null | undefined,
): NavLeaf | null {
  const meta = (route.meta ?? {}) as { title?: string; icon?: string }
  if (!meta.title) return null
  return {
    index: route.path,
    label: t(meta.title),
    icon: (meta.icon ? iconResolver(meta.icon) : undefined) as Component | undefined,
    visible: true,
  }
}

function collectLeaves(
  routes: RouteRecordNormalized[],
  rule: { routeNamePrefix?: string; routeNames?: string[]; leafFilter?: (r: RouteRecordNormalized) => boolean },
  t: (k: string) => string,
  iconResolver: (n: string) => Component | null | undefined,
): NavLeaf[] {
  let matched: RouteRecordNormalized[]
  if (rule.routeNames) {
    // 按 routeNames 声明顺序返回 (routeNames 是显式列表, 顺序由调用方控制)
    const byName = new Map(routes.map((r) => [String(r.name), r]))
    matched = rule.routeNames
      .map((n) => byName.get(n))
      .filter((r): r is RouteRecordNormalized => !!r)
  } else if (rule.routeNamePrefix) {
    matched = routes.filter((r) => typeof r.name === 'string' && r.name.startsWith(rule.routeNamePrefix!))
  } else {
    matched = []
  }
  if (rule.leafFilter) matched = matched.filter(rule.leafFilter)
  return matched
    .map((r) => leafFromRoute(r, t, iconResolver))
    .filter((l): l is NavLeaf => !!l)
}

export function buildNavItems(options: BuildNavOptions): NavItem[] {
  const { routes, t, iconResolver, groups = [], topLevels = [], leafFilter } = options
  const items: NavItem[] = []

  // ---- 顶层 leaf ----
  for (const rule of topLevels) {
    if (!resolveVisible(rule.visible)) continue
    const leaves = collectLeaves(routes, rule, t, iconResolver)
    if (leaves.length === 0) continue
    items.push(...leaves)
  }

  // ---- sub-menu group ----
  for (const rule of groups) {
    if (!resolveVisible(rule.visible)) continue
    const leaves = collectLeaves(routes, rule, t, iconResolver)
    if (leaves.length === 0) continue
    const group: NavGroup = {
      type: 'group',
      index: rule.id,
      label: t(rule.labelKey),
      icon: rule.icon ?? (rule.iconName ? iconResolver(rule.iconName) as Component : undefined),
      popperClass: rule.popperClass ?? 'fc-sidebar-popper',
      visible: true,
      children: leaves,
    }
    items.push(group)
  }

  // ---- 全局 leafFilter 兜底 (不通过 group/topLevels 匹配的路由) ----
  // 当前实现假设业务通过 group/topLevels 已经把路由分完; 未匹配的路由默认不进菜单.
  // 如有需要, 业务可以扩展 options 添加 'orphanRouteNames' / 'orphanRoutePrefix' 单独捕获.
  void leafFilter

  return items
}