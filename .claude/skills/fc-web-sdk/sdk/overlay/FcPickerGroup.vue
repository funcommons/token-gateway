<template>
  <FcReorderableGrid
    ref="gridRef"
    v-model="slots"
    :min="min"
    :max="max"
    :layout="layout"
    :columns="columns"
    :reorderable="reorderable"
    :show-index="showIndex"
    :keep-null="keepNull"
    :trailing-empty="trailingEmpty"
    @change="onGridChange"
    @remove="(i) => $emit('remove', i)"
    @reorder="(from, to) => $emit('reorder', from, to)"
  >
    <template #default="{ value, index }">
      <slot :value="value" :index="index" />
    </template>
  </FcReorderableGrid>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcPickerGroup' })
import { ref, watch } from 'vue'
import FcReorderableGrid from '../data/FcReorderableGrid.vue'

interface Props {
  /** 槽位值数组. null 表示空槽. */
  modelValue: (string | null)[]
  min?: number
  max?: number
  layout?: 'grid' | 'wrap'
  columns?: number
  reorderable?: boolean
  showIndex?: boolean
  keepNull?: boolean
  /** 末尾空槽规则 */
  trailingEmpty?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  min: 1,
  max: 8,
  layout: 'wrap',
  columns: 4,
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

const slots = ref<(string | null)[]>([])
const gridRef = ref<InstanceType<typeof FcReorderableGrid> | null>(null)

// 关键: 同步 props.modelValue → 内部 slots ref. 之前漏了这个 watch,
// 导致父级改 modelValue (例如 AigcImageGroup 的 watch / setImages 后的 emit)
// 不会传播到 FcReorderableGrid, picker slot 不更新. 比较 compressed 形式避免循环.
watch(() => props.modelValue, (v) => {
  const normalized = Array.isArray(v) ? v.map(u => (u == null ? null : String(u))) : []
  const compress = (a: (string | null)[]) => JSON.stringify(a.filter(x => x !== null))
  if (compress(normalized) !== compress(slots.value)) {
    slots.value = normalized
  }
}, { deep: true, immediate: true })

function onGridChange(next: (string | null)[]) {
  slots.value = next
  const out = props.keepNull ? [...next] : next.filter(u => u !== null) as string[]
  emit('update:modelValue', out as (string | null)[])
  emit('change', next)
}

defineExpose({
  slots,
  setSlot: (i: number, v: string | null) => gridRef.value?.setSlot(i, v),
  remove: (i: number) => gridRef.value?.remove(i),
  getGrid: () => gridRef.value,
})
</script>
