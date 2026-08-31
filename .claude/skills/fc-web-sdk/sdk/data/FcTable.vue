<script setup lang="ts">
defineOptions({ name: 'FcTable', inheritAttrs: false })
/**
 * FcTable — el-table 薄封装 (SDK).
 *
 * 替代散落 <el-table class="custom-table"> + :deep 覆写.
 * 统一表头/空态/边框/分页位置/列宽/斑马纹.
 *
 * 两种用法:
 *  1. 声明式 (推荐): 传 columns 配置 + data, 自动渲染列
 *     <FcTable :data="rows" :columns="cols" :loading="loading" @row-click="onRow" />
 *  2. 插槽式: 传 data + default slot 自己写 el-table-column (用于自定义 slot 单元格)
 *     <FcTable :data="rows" :loading="loading">
 *       <el-table-column prop="name" label="姓名" />
 *       <el-table-column label="操作">
 *         <template #default="{ row }"><FcButton @click="edit(row)">编辑</FcButton></template>
 *       </el-table-column>
 *     </FcTable>
 *
 * 两种可组合: columns 渲染默认列 + default slot 追加自定义列.
 */
import { computed } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import FcEmpty from '../display/FcEmpty.vue'
import type { TableColumn } from './_types'

interface Props {
  /** 数据. */
  data: Record<string, unknown>[]
  /** 列定义 (声明式用法). */
  columns?: TableColumn[]
  /** 加载中. */
  loading?: boolean
  /** 行 key. 默认取 row.id. */
  rowKey?: string | ((row: Record<string, unknown>) => string | number)
  /** 斑马纹. 默认 false. */
  stripe?: boolean
  /** 边框. 默认 false. */
  border?: boolean
  /** 表头背景. 默认 false. */
  headerBg?: boolean
  /** 行 hover 高亮. 默认 true. */
  highlightCurrentRow?: boolean
  /** 空态文案. */
  emptyText?: string
  /** 空态类型. */
  emptyType?: 'empty' | 'error' | 'search' | 'no-result' | 'processing'
  /** 行高. 默认 48. */
  rowHeight?: number
  /** 滚动高度 (传值后固定表头 + 出滚动条). */
  height?: number | string
  /** 最大滚动高度. */
  maxHeight?: number | string
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  rowKey: 'id',
  stripe: false,
  border: false,
  headerBg: false,
  highlightCurrentRow: true,
  emptyType: 'empty',
  emptyText: undefined,
  rowHeight: 48,
  height: undefined,
  maxHeight: undefined,
})

const emit = defineEmits<{
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'row-click': [row: any, column: any, event: Event]
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'row-dblclick': [row: any, column: any, event: Event]
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'selection-change': [rows: any[]]
  'sort-change': [payload: { prop: string, order: 'ascending' | 'descending' | null }]
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  select: [selection: any[], row: any]
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'select-all': [selection: any[]]
}>()

const rowKeyAttr = computed(() => {
  if (typeof props.rowKey === 'function') return undefined
  return props.rowKey
})

const listeners = {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'row-click': (row: any, column: any, event: Event) => emit('row-click', row, column, event),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'row-dblclick': (row: any, column: any, event: Event) => emit('row-dblclick', row, column, event),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'selection-change': (rows: any[]) => emit('selection-change', rows),
  'sort-change': ({ prop, order }: { prop: string, order: 'ascending' | 'descending' | null }) =>
    emit('sort-change', { prop, order }),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'select': (selection: any[], row: any) => emit('select', selection, row),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  'select-all': (selection: any[]) => emit('select-all', selection),
}
</script>

<template>
  <div class="fc-table" :class="{ 'is-loading': loading }">
    <el-table
      v-bind="$attrs"
      :data="data"
      :row-key="rowKeyAttr"
      :stripe="stripe"
      :border="border"
      :height="height"
      :max-height="maxHeight"
      :header-cell-class-name="headerBg ? 'fc-table__header-cell--bg' : ''"
      :highlight-current-row="highlightCurrentRow"
      :row-class-name="`fc-table__row--h${rowHeight}`"
      v-on="listeners"
    >
      <!-- 声明式列 -->
      <el-table-column
        v-for="col in columns"
        :key="col.prop || col.label"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
        :header-align="col.headerAlign || col.align || 'left'"
        :fixed="col.fixed"
        :sortable="col.sortable"
        :show-overflow-tooltip="col.overflow ?? true"
        :formatter="col.formatter"
      />

      <!-- 插槽式列 (业务自定义) -->
      <slot />

      <!-- 空态 -->
      <template #empty>
        <FcEmpty :type="emptyType" :title="emptyText" />
      </template>
    </el-table>

    <!-- loading 蒙层 -->
    <div v-if="loading" class="fc-table__loading">
      <el-icon class="fc-table__spinner"><Loading /></el-icon>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/mixins' as *;

.fc-table {
  position: relative;
  width: 100%;
}

.fc-table :deep(.el-table) {
  --el-table-border-color: var(--app-separator, #e5e5e5);
  --el-table-header-bg-color: var(--app-bg-muted, #fafafa);
  --el-table-row-hover-bg-color: var(--app-bg-muted, #f5f5f5);

  border-radius: var(--app-radius-md, 8px);
  overflow: hidden;
}

.fc-table :deep(.el-table th.el-table__cell) {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text, #333);
  background: var(--el-table-header-bg-color);
}

.fc-table :deep(.el-table td.el-table__cell) {
  font-size: 13px;
  color: var(--app-text, #333);
  padding: 8px 0;
}

.fc-table :deep(.fc-table__row--h48) {
  height: 48px;
}

.fc-table :deep(.fc-table__header-cell--bg) {
  background: var(--app-bg-muted, #f5f5f5);
  font-weight: 600;
}

.fc-table__loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--app-bg-card, #fff) 60%, transparent);
  backdrop-filter: blur(2px);
  z-index: 1;
}

.fc-table__spinner {
  font-size: 24px;
  color: var(--app-primary, #409eff);
  animation: fc-table-spin 0.8s linear infinite;
}

@keyframes fc-table-spin {
  to { transform: rotate(360deg); }
}
</style>
