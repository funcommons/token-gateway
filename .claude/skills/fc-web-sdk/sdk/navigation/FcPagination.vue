<script setup lang="ts">
defineOptions({ name: 'FcPagination', inheritAttrs: false })
/**
 * FcPagination — el-pagination 薄封装 (SDK).
 *
 * 替代业务侧 <el-pagination class="custom-page"> + :deep 覆写.
 * 统一布局 (prev/pager/next + jumper + total), 字号, 颜色.
 *
 * 用法:
 *   <FcPagination
 *     v-model:current-page="page"
 *     v-model:page-size="size"
 *     :total="total"
 *     @change="onPage"
 *   />
 */
import { useAttrs } from 'vue'

interface Props {
  /** 当前页 (1-based). */
  currentPage?: number
  /** 每页条数. */
  pageSize?: number
  /** 总条数. */
  total?: number
  /** 可选每页条数. 默认 [10, 20, 50, 100]. */
  pageSizes?: number[]
  /** 是否显示 jumper. 默认 true. */
  showJumper?: boolean
  /** 是否显示 total 文案. 默认 true. */
  showTotal?: boolean
  /** 是否显示 size selector. 默认 true. */
  showSize?: boolean
  /** 是否带背景. 默认 false (扁平). */
  background?: boolean
  /** 布局顺序. */
  layout?: string
  /** 禁用. */
  disabled?: boolean
  /** 小尺寸. */
  small?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  pageSizes: () => [10, 20, 50, 100],
  showJumper: true,
  showTotal: true,
  showSize: true,
  background: false,
  layout: undefined,
  disabled: false,
  small: false,
})

const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  change: [page: number, size: number]
}>()

const attrs = useAttrs()

const fullLayout = props.layout ?? [
  props.showTotal && 'total',
  props.showSize && 'sizes',
  'prev',
  'pager',
  'next',
  props.showJumper && 'jumper',
].filter(Boolean).join(', ')

function onCurrentPage(p: number) {
  emit('update:currentPage', p)
  emit('change', p, props.pageSize ?? 10)
}

function onPageSize(s: number) {
  emit('update:pageSize', s)
  emit('change', props.currentPage ?? 1, s)
}
</script>

<template>
  <el-pagination
    v-bind="attrs"
    :current-page="currentPage"
    :page-size="pageSize"
    :total="total"
    :page-sizes="pageSizes"
    :layout="fullLayout"
    :background="background"
    :disabled="disabled"
    :small="small"
    class="fc-pagination"
    @update:current-page="onCurrentPage"
    @update:page-size="onPageSize"
  />
</template>

<style scoped lang="scss">
.fc-pagination {
  :deep(.el-pagination__total) {
    font-size: 13px;
    color: var(--app-text-secondary, #666);
    margin-right: 8px;
  }

  :deep(.el-pagination__sizes .el-input__wrapper),
  :deep(.el-pagination__jump .el-input__wrapper) {
    box-shadow: 0 0 0 1px var(--app-separator, #e5e5e5) inset;
    border-radius: var(--app-radius-sm, 4px);
  }

  :deep(.el-pager li) {
    font-size: 13px;
    color: var(--app-text-secondary, #666);
    border-radius: var(--app-radius-sm, 4px);
    margin: 0 2px;
  }

  :deep(.el-pager li.is-active) {
    color: var(--app-on-primary, #fff);
    background: var(--app-primary, #409eff);
  }

  :deep(.btn-prev),
  :deep(.btn-next) {
    color: var(--app-text-secondary, #666);
    border-radius: var(--app-radius-sm, 4px);
  }
}
</style>
