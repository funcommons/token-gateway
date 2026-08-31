// src/config/nav-config.ts
// Host-only bridge between project routes + i18n and the SDK sidebar.
// Not part of the SDK — consumes the project's `routes` config and i18n,
// produces a NavItemMeta[] the host can iterate to build the sidebar nav.

import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { routes as allRoutes } from '@/router/routes'

export interface NavItemMeta {
  path: string
  label: string
  icon: string
  group: string
  badge?: 'dot' | number
}

/** Per-path badge config. The sidebar's badgeMap prop receives this directly. */
export const BADGE_MAP: Record<string, 'dot' | number> = {
  '/settings': 'dot',
  '/profile': 99,
}

export function useNavConfig() {
  const { t } = useI18n()

  const items = computed<NavItemMeta[]>(() =>
    allRoutes
      .filter((r) => r.meta?.title && !r.meta?.hidden && r.meta?.showInMenu !== false)
      .map((r) => ({
        path: r.path,
        label: t(r.meta!.title as string),
        icon: r.meta!.icon as string,
        group: r.meta!.group as string,
      })),
  )

  return { items, badgeMap: BADGE_MAP }
}
