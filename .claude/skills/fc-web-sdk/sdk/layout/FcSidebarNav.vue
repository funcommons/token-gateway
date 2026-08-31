<template>
  <FcNavGroup
    :active-path="activePath"
    :collapse="collapse"
    :openeds="defaultOpeneds"
    :accordion="accordion"
    @select="onSelect"
  >
    <template v-for="item in visibleItems" :key="item.index">
      <!-- group: sub-menu -->
      <el-sub-menu
        v-if="isGroup(item)"
        :index="item.index"
        :popper-class="item.popperClass || defaultPopperClass"
      >
        <template #title>
          <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
          <span v-if="!collapse" class="app-menu-item__label">{{ item.label }}</span>
        </template>
        <el-tooltip
          v-for="leaf in visibleLeaves(item.children)"
          :key="leaf.index"
          :content="leaf.tooltip || leaf.label"
          placement="right"
          :disabled="!collapse"
        >
          <el-menu-item :index="leaf.index">
            <el-icon v-if="leaf.icon"><component :is="leaf.icon" /></el-icon>
            <template #title>
              <span class="app-menu-item__label">{{ leaf.label }}</span>
            </template>
          </el-menu-item>
        </el-tooltip>
      </el-sub-menu>

      <!-- leaf: top-level menu item -->
      <el-tooltip
        v-else
        :content="item.tooltip || item.label"
        placement="right"
        :disabled="!collapse"
      >
        <el-menu-item :index="item.index">
          <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
          <template #title>
            <span class="app-menu-item__label">{{ item.label }}</span>
          </template>
        </el-menu-item>
      </el-tooltip>
    </template>
  </FcNavGroup>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcSidebarNav' })
import { computed } from 'vue'
import type { Component } from 'vue'
import FcNavGroup from './FcNavGroup.vue'

/**
 * FcSidebarNav — 数据驱动的 sidebar nav (SDK).
 *
 * 把 nav 树抽成 `items: NavItem[]` 配置, 业务侧 (composable) 从路由 / 角色 /
 * feature flag 组装. SDK 零业务依赖: 不接 vue-router / store / i18n,
 * 路径通过 activePath prop 传, 选中后 emit('select', path) 给宿主 router.push.
 *
 * 用法:
 *   <FcSidebarNav
 *     :items="navItems"
 *     :active-path="route.path"
 *     :collapse="false"
 *     :default-openeds="['sub-plaza', 'sub-create']"
 *     @select="onSelect"
 *   />
 */

export interface NavLeaf {
  /** 唯一 id + emit('select') 时回传的值 (通常是 path) */
  index: string
  /** 显示文本 (调用方负责 i18n) */
  label: string
  /** ElementPlus 图标组件 (推荐用 resolveIcon 取) */
  icon?: Component
  /** 折叠态悬停提示, 默认 = label */
  tooltip?: string
  /** 隐藏 (false 不渲染). 业务侧用作角色 / feature flag 控制 */
  visible?: boolean
}

export interface NavGroup {
  type: 'group'
  /** sub-menu id (非 path), 用于 defaultOpeneds 标识 */
  index: string
  /** 分组标题 (i18n resolved) */
  label: string
  /** 分组图标 */
  icon?: Component
  /** 自定义 popper className. 默认 'fc-sidebar-popper' */
  popperClass?: string
  /** 隐藏 */
  visible?: boolean
  /** 子项 (叶子) */
  children: NavLeaf[]
}

export type NavItem = NavLeaf | NavGroup

interface Props {
  /** nav 树 */
  items: NavItem[]
  /** 当前激活路径, 用于高亮 leaf.index 与之相等的项 */
  activePath?: string
  /** 折叠态: 隐藏 label, 显示 tooltip */
  collapse?: boolean
  /** 默认展开的 sub-menu index 列表 */
  defaultOpeneds?: string[]
  /** 默认 popper 类名, 业务可在 group.popperClass 覆盖. mixin 通过 .fc-sidebar-popper 选品品牌化弹层样式. */
  defaultPopperClass?: string
  /** accordion 模式: 同时只允许一个 sub-menu 展开. 默认 true. */
  accordion?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  activePath: '',
  collapse: false,
  defaultOpeneds: () => [],
  defaultPopperClass: 'fc-sidebar-popper',
  accordion: true,
})

const emit = defineEmits<{
  /** 用户选中叶子, 回传 leaf.index (一般是路径, 由宿主 router.push) */
  select: [path: string]
}>()

function isGroup(item: NavItem): item is NavGroup {
  return 'type' in item && item.type === 'group'
}

const visibleItems = computed(() => props.items.filter(i => i.visible !== false))
function visibleLeaves(leaves: NavLeaf[]) {
  return leaves.filter(l => l.visible !== false)
}

function onSelect(index: string | number) {
  emit('select', String(index))
}
</script>
