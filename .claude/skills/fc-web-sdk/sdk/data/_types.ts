export interface TableColumn<R = Record<string, unknown>> {
  /** 字段名. */
  prop?: string
  /** 列标题. */
  label?: string
  /** 列宽 (px). */
  width?: number | string
  /** 最小列宽 (px). */
  minWidth?: number | string
  /** 对齐. 默认 'left'. */
  align?: 'left' | 'center' | 'right'
  /** 表头对齐. 默认同 align. */
  headerAlign?: 'left' | 'center' | 'right'
  /** 固定列. */
  fixed?: boolean | 'left' | 'right'
  /** 是否可排序. */
  sortable?: boolean | 'custom'
  /** 是否省略 (show-overflow-tooltip). 默认 true. */
  overflow?: boolean
  /** 格式化函数. */
  formatter?: (row: R, column: unknown, cellValue: unknown, index: number) => string
}
