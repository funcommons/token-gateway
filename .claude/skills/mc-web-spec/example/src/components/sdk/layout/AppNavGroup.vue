<script setup lang="ts">
// AppNavGroup — 薄壳: 包 Element Plus <el-menu>, 提供 activePath / select 接口.
// 业务侧在 AppSidebar 的 default slot 里塞 <el-sub-menu> / <el-menu-item> 树.
//
// 原 NavGroup 用 NavNode[] 数据驱动递归, 改为薄壳让 host 完全控制菜单内容.
// 这样 SDK 零业务知识 (不知道什么路由, 什么分组), 业务侧用 EP el-menu 原生语法自填.

interface Props {
  /** 当前激活路径, 跟 route.path 对应. 不传则 el-menu 没有高亮. */
  activePath?: string
  /** 是否水平排列, 默认垂直. */
  mode?: 'horizontal' | 'vertical'
  /** 折叠态 (icon 模式). SDK 把它转给 el-menu, 让 EP 接管 popper / 图标居中.
   *  host 在 App.vue 传 v-model:collapse 或 :collapse 进来. */
  collapse?: boolean
  /** 默认展开的 sub-menu index 列表. SDK 不感知业务, host 用 v-model:openeds
   *  在路由变化时重算 (例如 route.path.startsWith('/spec/') ? ['spec'] : []).
   *  el-menu 收到新值会重新展开对应 sub-menu. */
  openeds?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  activePath: '',
  mode: 'vertical',
  collapse: false,
  openeds: () => [],
})

const emit = defineEmits<{
  /** 用户点 menu item 触发, 业务侧决定 router.push 哪里 */
  select: [path: string]
  /** el-menu 内部 default-openeds 变化 (用户点 sub-menu 头), host 可同步回 store */
  'update:openeds': [v: string[]]
}>()

function onSelect(index: string | number) {
  emit('select', String(index))
}

function onOpenedsChange(v: string[]) {
  emit('update:openeds', v)
}
</script>

<template>
  <el-menu
    class="app-nav-group"
    :default-active="activePath"
    :mode="mode"
    :collapse="collapse"
    :collapse-transition="false"
    @select="onSelect"
  >
    <slot />
  </el-menu>
</template>

<style scoped>
/* 菜单项 padding / 高度 / hover 跟 EP 默认, host 不需要重写.
   只有顶级 .app-nav-group 让 EP 子选择器能挂上. */
.app-nav-group {
  border-right: none;
}
/* is-group-divider 业务侧标记: 系统分组前的视觉分隔.
   不用 border-top 横线 (跟 ldx2t-web-framework 一致), 仅靠 12px 上 margin 拉开距离. */
.app-nav-group :deep(.el-menu-item.is-group-divider) {
  margin-top: 12px;
}
</style>

<style>
/* 折叠态 (.app-sidebar.is-collapsed) 折叠 margin, 纯图标模式不应有视觉分组. */
.app-sidebar.is-collapsed .app-nav-group .el-menu-item.is-group-divider {
  margin-top: 2px !important;
}
</style>
