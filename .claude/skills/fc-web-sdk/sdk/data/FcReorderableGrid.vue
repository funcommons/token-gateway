<template>
  <div class="fc-reorderable-grid" :class="`layout-${layout}`" :style="gridStyle">
    <div
      v-for="(value, i) in slots"
      :key="i"
      class="arg-slot"
      :class="{ 'is-dragging': draggingIndex === i, 'is-drop-target': dropTargetIndex === i }"
      :draggable="reorderable"
      :style="slotSizeStyle"
      @dragstart="onDragStart(i, $event)"
      @dragover.prevent="onDragOver(i)"
      @dragleave="onDragLeave(i)"
      @drop.prevent="onDrop(i)"
      @dragend="onDragEnd"
    >
      <slot :value="value" :index="i" />

      <!-- 序号徽章 -->
      <div v-if="showIndex" class="arg-index">{{ i + 1 }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcReorderableGrid' })
import { ref, computed, watch, onUnmounted, type CSSProperties } from 'vue'

interface Props {
  /** 槽位值数组. null 表示空槽. */
  modelValue: (string | null)[]
  /** 最少槽位数. 删除到 ≤ min 时退化为 null 占位而不是真删. */
  min?: number
  /** 最多槽位数. */
  max?: number
  /** 布局: grid (固定列) / wrap (自适应换行). */
  layout?: 'grid' | 'wrap'
  /** grid 模式列数. */
  columns?: number
  /** 每个槽位宽 (px). */
  width?: number
  /** 每个槽位高 (px). */
  height?: number
  /** 是否可拖拽排序 (HTML5 DnD). */
  reorderable?: boolean
  /** 是否显示序号徽章. */
  showIndex?: boolean
  /** v-model 输出是否保留 null. true = (string|null)[], false = string[]. */
  keepNull?: boolean
  /** 末尾空槽规则: 是否在末尾自动追加一个 null 让用户继续添加. */
  trailingEmpty?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  min: 1,
  max: 8,
  layout: 'wrap',
  columns: 4,
  width: 120,
  height: 120,
  reorderable: false,
  showIndex: false,
  keepNull: false,
  trailingEmpty: true,
})

const emit = defineEmits<{
  'update:modelValue': [values: (string | null)[]]
  change: [values: (string | null)[]]
  remove: [index: number]
  reorder: [from: number, to: number]
}>()

// 内部槽位数组
const slots = ref<(string | null)[]>([])

const gridStyle = computed<CSSProperties>(() => {
  if (props.layout === 'grid') {
    return {
      display: 'grid',
      gridTemplateColumns: `repeat(${props.columns}, max-content)`,
      gap: '12px',
    }
  }
  return {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '12px',
  }
})

const slotSizeStyle = computed<CSSProperties>(() => ({
  width: 'max-content',
}))

function normalizeIncoming(v: (string | null)[] | string[] | null | undefined): (string | null)[] {
  if (!Array.isArray(v)) return []
  return v.map(u => (u === null || u === undefined) ? null : String(u))
}

/** 末尾空槽规则:
 *  - 满了 (length >= max) 不动
 *  - 已经有 null 在任意位置 → 不动
 *  - 否则末尾追加一个 null */
function ensureTrailingEmpty() {
  if (!props.trailingEmpty) return
  if (slots.value.length >= props.max) return
  const hasNull = slots.value.some(u => u === null || u === undefined)
  if (hasNull) return
  if (props.max <= 0) return
  slots.value.push(null)
}

watch(() => props.modelValue, (v) => {
  const normalized = normalizeIncoming(v)
  // 比较压缩后 (去 null) 的字符串, 避免 [null] 与 [] 互相触发循环
  const compress = (arr: (string | null)[]) => JSON.stringify(arr.filter(u => u !== null))
  if (compress(normalized) !== compress(slots.value)) {
    slots.value = normalized
  }
  ensureTrailingEmpty()
}, { deep: true, immediate: true })

function emitChange() {
  const out = props.keepNull
    ? [...slots.value]
    : slots.value.filter(u => u !== null) as string[]
  emit('update:modelValue', out as (string | null)[])
  emit('change', slots.value)
}

function setSlot(i: number, value: string | null) {
  if (value === null) {
    if (slots.value.length <= props.min) {
      slots.value[i] = null
      emitChange()
      return
    }
    remove(i)
    return
  }
  slots.value[i] = value
  emitChange()
  ensureTrailingEmpty()
}

function remove(i: number) {
  if (slots.value.length <= props.min) return
  slots.value.splice(i, 1)
  emit('remove', i)
  emitChange()
  ensureTrailingEmpty()
}

// === 拖拽 ===
const draggingIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)
let dragOverRaf = 0
let pendingDragOver: number | null = null

function onDragStart(i: number, e: DragEvent) {
  if (!props.reorderable) return
  draggingIndex.value = i
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(i))
  }
}
function onDragOver(i: number) {
  if (!props.reorderable || draggingIndex.value === null) return
  if (pendingDragOver === i) return
  pendingDragOver = i
  if (dragOverRaf) return
  dragOverRaf = requestAnimationFrame(() => {
    dropTargetIndex.value = pendingDragOver
    pendingDragOver = null
    dragOverRaf = 0
  })
}
function onDragLeave(i: number) {
  if (dropTargetIndex.value === i) dropTargetIndex.value = null
}
function onDrop(i: number) {
  if (!props.reorderable) return
  const from = draggingIndex.value
  if (from === null || from === i) {
    draggingIndex.value = null
    dropTargetIndex.value = null
    return
  }
  const [moved] = slots.value.splice(from, 1)
  slots.value.splice(i, 0, moved ?? null)
  emit('reorder', from, i)
  emitChange()
  draggingIndex.value = null
  dropTargetIndex.value = null
}
function onDragEnd() {
  draggingIndex.value = null
  dropTargetIndex.value = null
  if (dragOverRaf) {
    cancelAnimationFrame(dragOverRaf)
    dragOverRaf = 0
  }
}

onUnmounted(() => {
  if (dragOverRaf) cancelAnimationFrame(dragOverRaf)
})

defineExpose({
  slots,
  setSlot,
  remove,
})
</script>

<style scoped lang="scss">
.fc-reorderable-grid {
  position: relative;
}

.arg-slot {
  position: relative;
  transition: opacity 0.15s, transform 0.15s;
  user-select: none;
  -webkit-user-select: none;

  &.is-dragging {
    opacity: 0.4;
  }
  &.is-drop-target {
    transform: scale(1.04);
    outline: 2px dashed var(--app-primary, #007aff);
    outline-offset: 4px;
    border-radius: var(--radius-md, 8px);
  }
}

.arg-index {
  position: absolute;
  top: -10px;
  left: -10px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--app-overlay-strong, rgba(0, 0, 0, 0.6));
  color: var(--app-on-primary, #fff);
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
  pointer-events: none;
}
</style>
