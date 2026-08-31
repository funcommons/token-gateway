<script setup lang="ts">defineOptions({ name: 'FcNavGroup' })
// FcNavGroup — 薄壳: 包 Element Plus <el-menu>, 提供 activePath / select 接口.
// 业务侧在 FcSidebar 的 default slot 里塞 <el-sub-menu> / <el-menu-item> 树.

interface Props {
  activePath?: string
  mode?: 'horizontal' | 'vertical'
  collapse?: boolean
  openeds?: string[]
  /** accordion: 同时只允许一个 sub-menu 展开 (绑定 el-menu unique-opened) */
  accordion?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  activePath: '',
  mode: 'vertical',
  collapse: false,
  openeds: () => [],
  accordion: false,
})

const emit = defineEmits<{
  select: [path: string]
  'update:openeds': [v: string[]]
}>()

function onSelect(index: string | number) {
  emit('select', String(index))
}
</script>

<template>
  <el-menu
    class="fc-nav-group"
    :default-active="activePath"
    :mode="mode"
    :collapse="collapse"
    :default-openeds="openeds"
    :unique-opened="accordion"
    :collapse-transition="false"
    @select="onSelect"
  >
    <slot />
  </el-menu>
</template>

<style scoped>
.fc-nav-group {
  border-right: none;
}
.fc-nav-group :deep(.el-menu-item.is-group-divider) {
  margin-top: 12px;
}
</style>

<style>
.fc-sidebar.is-collapsed .fc-nav-group .el-menu-item.is-group-divider {
  margin-top: 2px !important;
}
</style>
