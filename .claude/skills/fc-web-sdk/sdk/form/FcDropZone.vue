<script setup lang="ts">
defineOptions({ name: 'FcDropZone' })
/**
 * FcDropZone — 通用拖放区 (SDK).
 *
 * 仅处理拖放语义 + 文件/URL 透传, 不绑业务 (不调上传 API, 不弹 dialog).
 * 跟 useImageUpload / useBatchGroups 解耦 — 业务层拿到 File[] 后自己决定怎么处理.
 *
 * 用法:
 *   <FcDropZone
 *     accept="image/*"
 *     multiple
 *     :max-size="10 * 1024 * 1024"
 *     @drop="onFiles"
 *     @drop-url="onUrl"
 *     @reject="onReject"
 *   >
 *     <template #default="{ isDragOver }">
 *       <i class="ri-upload-cloud-2-line" />
 *       <span>{{ isDragOver ? '松开上传' : '拖拽 / 点击上传' }}</span>
 *     </template>
 *   </FcDropZone>
 *
 * 内置:
 *  - 拖入文件 → emit('drop', File[])
 *  - 拖入 URL 文本 → emit('drop-url', string)
 *  - 自动校验 accept + max-size + multiple, 不合规则 emit('reject', { reason, files })
 *  - 点击触发原生 file input 选择
 *  - slot 暴露 isDragOver
 */
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface RejectPayload {
  reason: 'type' | 'size' | 'count'
  files: File[]
  message: string
}

interface Props {
  /** 接受的 mime / 扩展. 默认 '*' (任意). */
  accept?: string
  /** 是否多选. 默认 true. */
  multiple?: boolean
  /** 单文件最大字节数. 默认 10MB. */
  maxSize?: number
  /** 是否禁用 (不响应点击 / 拖放). */
  disabled?: boolean
  /** 是否启用点击触发文件选择. 默认 true. */
  clickable?: boolean
  /** 容器 tag. 默认 'div'. */
  as?: string
  /** 容器 class (业务可追加). */
  containerClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  accept: '*',
  multiple: true,
  maxSize: 10 * 1024 * 1024,
  disabled: false,
  clickable: true,
  as: 'div',
  containerClass: '',
})

const emit = defineEmits<{
  /** 文件通过校验, 抛出 File[] */
  drop: [files: File[]]
  /** 拖入的是 URL 字符串 (从外部浏览器拖链接时) */
  'drop-url': [url: string]
  /** 文件未通过校验 */
  reject: [payload: RejectPayload]
  /** 用户点击触发器 (业务可在此打开 dialog / picker) */
  click: []
}>()

const { t } = useI18n()

const isDragOver = ref(false)
const dragCounter = ref(0)
const fileInputEl = ref<HTMLInputElement>()

function matchesAccept(file: File): boolean {
  if (props.accept === '*' || !props.accept) return true
  const patterns = props.accept.split(',').map(p => p.trim().toLowerCase())
  const fileName = file.name.toLowerCase()
  const fileType = file.type.toLowerCase()
  return patterns.some(p => {
    if (p.startsWith('.')) return fileName.endsWith(p)
    if (p.endsWith('/*')) return fileType.startsWith(p.slice(0, -1))
    return fileType === p
  })
}

function validateAndEmit(files: File[]) {
  if (!files.length) return
  if (!props.multiple && files.length > 1) {
    emit('reject', {
      reason: 'count',
      files,
      message: t('common.warning') + ': multiple=false but got >1 file',
    })
    return
  }
  const rejected: File[] = []
  const accepted: File[] = []
  for (const f of files) {
    if (!matchesAccept(f)) rejected.push(f)
    else if (props.maxSize > 0 && f.size > props.maxSize) rejected.push(f)
    else accepted.push(f)
  }
  if (accepted.length) emit('drop', accepted)
  if (rejected.length) {
    const reason = rejected.some(f => !matchesAccept(f)) ? 'type' : 'size'
    emit('reject', {
      reason,
      files: rejected,
      message: reason === 'type'
        ? t('common.warning') + ': unsupported file type'
        : t('common.warning') + ': file too large',
    })
  }
}

function extractFilesFromDataTransfer(data: DataTransfer) {
  const out: File[] = []
  if (data.files?.length) {
    for (let i = 0; i < data.files.length; i++) {
      const f = data.files[i]
      if (f) out.push(f)
    }
  }
  if (data.items?.length) {
    for (let i = 0; i < data.items.length; i++) {
      const it = data.items[i]
      if (!it || it.kind !== 'file') continue
      const f = it.getAsFile()
      if (f && !out.includes(f)) out.push(f)
    }
  }
  return out
}

function extractUrlFromDataTransfer(data: DataTransfer): string | null {
  const url = data.getData('text/uri-list') || data.getData('text/x-moz-url')?.split('\n')?.[0]
  if (url) return url.trim()
  const text = data.getData('text/plain')?.trim()
  if (text && /^https?:\/\//i.test(text)) return text
  return null
}

function onDragEnter(e: DragEvent) {
  if (props.disabled) return
  if (!e.dataTransfer) return
  e.preventDefault()
  dragCounter.value++
  isDragOver.value = true
}

function onDragOver(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  dragCounter.value = Math.max(0, dragCounter.value - 1)
  if (dragCounter.value === 0) isDragOver.value = false
}

function onDrop(e: DragEvent) {
  if (props.disabled) return
  e.preventDefault()
  dragCounter.value = 0
  isDragOver.value = false
  if (!e.dataTransfer) return

  const files = extractFilesFromDataTransfer(e.dataTransfer)
  if (files.length) {
    validateAndEmit(files)
    return
  }
  const url = extractUrlFromDataTransfer(e.dataTransfer)
  if (url) emit('drop-url', url)
}

function onTriggerClick() {
  if (props.disabled || !props.clickable) return
  emit('click')
  if (props.clickable) fileInputEl.value?.click()
}

function onFileInputChange(e: Event) {
  const target = e.target as HTMLInputElement
  const list = target.files
  if (!list || !list.length) return
  const files: File[] = []
  for (let i =  0; i < list.length; i++) {
    const f = list[i]
    if (f) files.push(f)
  }
  validateAndEmit(files)
  target.value = ''
}

const acceptAttr = computed(() => props.accept || undefined)
</script>

<template>
  <component
    :is="as"
    class="fc-drop-zone"
    :class="[
      containerClass,
      {
        'is-drag-over': isDragOver,
        'is-disabled': disabled,
        'is-clickable': clickable && !disabled,
      },
    ]"
    role="button"
    tabindex="0"
    @click="onTriggerClick"
    @keydown.enter.prevent="onTriggerClick"
    @keydown.space.prevent="onTriggerClick"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      ref="fileInputEl"
      type="file"
      :accept="acceptAttr"
      :multiple="multiple"
      hidden
      @change="onFileInputChange"
    />
    <slot :is-drag-over="isDragOver" />
  </component>
</template>

<style scoped lang="scss">
.fc-drop-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--app-border-extra-light, #e0e0e0);
  border-radius: var(--app-radius-md, 12px);
  background: var(--app-bg-card, transparent);
  transition: border-color 0.15s ease, background-color 0.15s ease;
  outline: none;

  &.is-clickable {
    cursor: pointer;

    &:hover {
      border-color: color-mix(in srgb, var(--app-primary, #409eff) 60%, transparent);
      background: color-mix(in srgb, var(--app-primary, #409eff) 4%, transparent);
    }
  }

  &.is-drag-over {
    border-color: var(--app-primary, #409eff);
    background: color-mix(in srgb, var(--app-primary, #409eff) 8%, transparent);
  }

  &.is-disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  &:focus-visible {
    border-color: var(--app-primary, #409eff);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #409eff) 30%, transparent);
  }
}
</style>
