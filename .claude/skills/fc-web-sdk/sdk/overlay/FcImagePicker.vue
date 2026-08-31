<template>
  <FcPicker
    ref="pickerRef"
    :title="dialogTitle"
    :dialog-width="dialogWidth"
    :dialog-height="dialogHeight"
    :is-mobile="isMobile"
    :trigger-action="'none'"
    :disabled="disabled"
    :draggable="true"
    :resizable="true"
    :close-on-click-modal="false"
    append-to-body
    @context-menu="onContextMenu"
  >
    <!-- 触发器 -->
    <template #trigger>
      <input ref="fileInputEl" type="file" :accept="acceptAttr" :multiple="multiple" hidden @change="handleFileSelect" />

      <!-- Inline 槽位 -->
      <div
        v-if="mode === 'inline'"
        class="fc-image-picker__slot"
        :style="{ width: width + 'px', height: height + 'px' }"
        @mouseenter="isHovered = true"
        @mouseleave="isHovered = false"
      >
        <div
          v-if="modelValue"
          class="fc-image-picker__preview"
          :class="['shape-' + shape, { 'cover-mode': coverMenu }]"
          @click.stop="previewVisible = true"
          @dragover.prevent="isDragOver = true"
          @dragleave.prevent="isDragOver = false"
          @drop="handleDrop"
        >
          <img loading="lazy" :src="modelValue" class="fc-image-picker__img" draggable="false" :style="{ borderRadius: shape === 'circle' ? '50%' : 'var(--radius-md)' }" />
        </div>

        <div
          v-else
          class="fc-image-picker__empty"
          :class="['shape-' + shape, { 'drag-over': isDragOver }]"
          @click="triggerUpload"
          @dragover.prevent="isDragOver = true"
          @dragleave.prevent="isDragOver = false"
          @drop="handleDrop"
        >
          <div v-if="isUploading" class="fc-image-picker__loading">
            <i class="ri-loader-4-line spinning" />
            <span>{{ t('common.uploading') }}</span>
          </div>
          <template v-else>
            <i class="ri-add-line" />
            <span>{{ placeholder }}</span>
          </template>
        </div>

        <!-- 上工具栏 (右上角, hover 时显示): 仅删除 (预览改由点击图片触发) -->
        <div v-if="modelValue" class="fc-image-picker__top-bar">
          <el-tooltip :content="t('menu.delete')" placement="top" :show-after="300">
            <button class="bar-btn danger" @click.stop="clearImage">
              <i class="ri-delete-bin-line" />
            </button>
          </el-tooltip>
        </div>

        <!-- 下工具栏 (右下角, hover 时显示, 与右上删除对称): 仅无图时显示 -->
        <div v-if="!modelValue" class="fc-image-picker__cover-menu">
          <el-tooltip v-if="pasteEnabled" :content="t('menu.paste')" placement="top" :show-after="300">
            <button class="cover-btn" @click.stop="onPasteClick">
              <i class="ri-clipboard-line" />
            </button>
          </el-tooltip>
          <el-tooltip :content="t('menu.more')" placement="top" :show-after="300">
            <button class="cover-btn" @click.stop="openMorePanel">
              <i class="ri-more-fill" />
            </button>
          </el-tooltip>
        </div>
      </div>

      <!-- Popover 模式触发器 -->
      <div
        v-else
        class="fc-image-picker__popover-trigger"
        @click="onSlotClick"
        @mouseenter="isHovered = true"
        @mouseleave="isHovered = false"
      >
        <slot name="trigger">
          <el-button :icon="UploadIcon">{{ placeholder }}</el-button>
        </slot>
      </div>
    </template>

    <!-- popup panel: 内部 tabs (upload / url / recent) -->
    <template #panel>
      <div class="fc-image-picker__panel">
        <!-- Tab 栏 (el-tabs, 仅用其 nav 做 source 切换) -->
        <el-tabs v-model="activeSource" class="fc-image-picker__tabs">
          <el-tab-pane
            v-for="src in visibleSources"
            :key="src.value"
            :name="src.value"
          >
            <template #label>
              <span class="fc-image-picker__tab-label">
                <i :class="src.icon" />
                <span>{{ src.label }}</span>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>

        <!-- 内容区 -->
        <div class="fc-image-picker__content">
          <!-- 上传 tab: 暂存已上传图片, 多选后点确定生效 (不立即关窗) -->
          <div v-if="activeSource === 'upload'" class="fc-image-picker__upload-zone">
            <!-- 空状态: drop zone + 左下粘贴按钮 -->
            <div
              v-if="pendingUploads.length === 0"
              class="fc-image-picker__upload-empty"
              :class="{ 'drag-over': isDragOver }"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="isDragOver = false"
              @drop.prevent="handleDrop"
            >
              <div class="fc-image-picker__upload-empty-body" @click="triggerUpload">
                <i class="ri-upload-cloud-2-line fc-image-picker__upload-icon" />
                <p>{{ t('upload.hint') }}</p>
                <p class="fc-image-picker__upload-subhint">{{ multiHint }}</p>
              </div>
              <div class="fc-image-picker__upload-empty-footer">
                <el-button
                  v-if="pasteEnabled"
                  :disabled="isUploading"
                  @click="onPasteClick"
                >
                  <i class="ri-clipboard-line" />
                  <span>{{ t('menu.paste') }}</span>
                </el-button>
              </div>
            </div>
            <!-- 有暂存: thumbnail grid + 粘贴按钮 (始终可见) -->
            <div v-else class="fc-image-picker__upload-pending">
              <div class="fc-image-picker__upload-grid">
                <div
                  v-for="url in pendingUploads"
                  :key="url"
                  class="fc-image-picker__upload-card"
                  :class="{ selected: isSelected(url) }"
                  @click="onPendingToggle(url)"
                >
                  <img :src="url" />
                  <div v-if="isSelected(url)" class="fc-image-picker__recent-check">
                    <i class="ri-check-line" />
                  </div>
                </div>
                <!-- 添加更多按钮 -->
                <div class="fc-image-picker__upload-add" @click="triggerUpload">
                  <i class="ri-add-line" />
                </div>
              </div>
              <div class="fc-image-picker__upload-footer">
                <el-button
                  v-if="pasteEnabled"
                  :disabled="isUploading"
                  @click="onPasteClick"
                >
                  <i class="ri-clipboard-line" />
                  <span>{{ t('menu.paste') }}</span>
                </el-button>
              </div>
            </div>
          </div>

          <!-- URL tab -->
          <div v-else-if="activeSource === 'url'" class="fc-image-picker__url-zone">
            <el-input
              v-model="urlInput"
              :placeholder="t('url.placeholder')"
              clearable
              @keyup.enter.exact="confirmUrl"
            />
            <el-button style="margin-top: 8px" :disabled="!urlInput.trim()" @click="confirmUrl">
              {{ t('common.confirm') }}
            </el-button>
          </div>

          <!-- 最近使用 tab -->
          <div v-else-if="activeSource === 'recent'" class="fc-image-picker__recent-zone">
            <div v-if="recentImages.length === 0" class="fc-image-picker__empty-hint">
              {{ t('recent.empty') }}
            </div>
            <div v-else class="fc-image-picker__recent-grid">
              <div
                v-for="(url, i) in recentImages"
                :key="i"
                class="fc-image-picker__recent-item"
                :class="{ selected: isSelected(url) }"
                @click="onRecentClick(url)"
              >
                <img loading="lazy" :src="url" :alt="`recent-${i}`" />
                <div v-if="isSelected(url)" class="fc-image-picker__recent-check">
                  <i class="ri-check-line" />
                </div>
                <button
                  class="fc-image-picker__recent-remove"
                  :title="t('recent.remove')"
                  @click.stop="removeRecent(url)"
                >
                  <i class="ri-close-line" />
                </button>
              </div>
            </div>
          </div>

          <!-- 外部来源 slot -->
          <div v-else class="fc-image-picker__custom-zone">
            <slot
              :name="`source-${activeSource}`"
              :select="onSlotSelect"
              :multiple="props.multiple"
              :toggle="onSlotToggle"
              :is-selected="isSelected"
              :selected-count="selectedList.length"
            />
          </div>
        </div>

        <!-- 底部 -->
        <div class="fc-image-picker__footer">
          <span v-if="multiple && selectedList.length > 0" class="fc-image-picker__footer-tip">
            {{ t('upload.selectedHint', { n: selectedList.length }) }}
          </span>
          <div class="fc-image-picker__footer-actions">
            <el-button v-if="multiple && selectedList.length > 0" text @click="clearSelection">
              {{ t('common.clear') }}
            </el-button>
            <el-button
              v-if="multiple"
              type="primary"
              :disabled="selectedList.length === 0"
              @click="confirmMulti"
            >
              {{ t('common.confirm') }}{{ selectedList.length > 0 ? ` (${selectedList.length})` : '' }}
            </el-button>
            <el-button text @click="closePanel">
              {{ t('common.close') }}
            </el-button>
          </div>
        </div>
      </div>
    </template>

    <!-- 右键菜单 (桌面) -->
    <template #context-menu="{ pos, close: closeCtx, open }">
      <FcContextMenu
        :visible="open"
        :pos="pos"
        :items="actionItems"
        @select="onContextSelect"
        @close="closeCtx"
      />
    </template>

    <!-- 手机 action sheet -->
    <template #action-sheet="{ close: closeSheet }">
      <div class="fc-image-picker__action-sheet">
        <button
          v-for="item in actionItems"
          :key="item.value"
          class="fc-image-picker__action-item"
          :class="{ danger: item.danger }"
          @click="onContextSelect(item.value); closeSheet()"
        >
          <i :class="item.icon" />
          <span>{{ item.label }}</span>
        </button>
        <button class="fc-image-picker__action-item cancel" @click="closeSheet">
          <span>{{ t('menu.cancel') }}</span>
        </button>
      </div>
    </template>
  </FcPicker>

  <!-- 预览: 全屏查看当前图 -->
  <el-image-viewer
    v-if="previewVisible && modelValue"
    :url-list="[modelValue]"
    @close="previewVisible = false"
  />
</template>

<script setup lang="ts">defineOptions({ name: 'FcImagePicker' })
/**
 * FcImagePicker — 通用图片选择器 (SDK).
 *
 * 零业务依赖:
 *  - 文件验证/转换/缩放在 SDK 内部完成
 *  - 上传逻辑由 upload prop 提供 (业务 wrapper 调 @/api/oss)
 *  - 最近图片通过 recentStorageKey 启用 (内部用 useRecentImages 走 localStorage)
 *  - i18n 通过 t prop 提供 (默认英文)
 *
 * 用法 (典型 - 业务 wrapper):
 *   <FcImagePicker
 *     v-model="url"
 *     :upload="uploadImage"  // 业务: 调 @/api/oss
 *     :t="t"                  // vue-i18n 的 t
 *     :recent-storage-key="'app:recent'"
 *   />
 */
import { ref, computed, h, onUnmounted, reactive } from 'vue'
import FcPicker from './FcPicker.vue'
import FcContextMenu, { type MenuItem } from './FcContextMenu.vue'
import { convertImage, downscaleToMaxEdge, useRecentImages } from '@/composables'
import { useResponsive, useEventListener } from '@/composables'
import { logger } from '@/utils'

/**
 * 上传服务配置. 业务 wrapper 通过 server 把 OSS/自建上传端点配置给 SDK,
 * SDK 内部 defaultUpload 会组装 FormData + fetch + assertSuccess + 提取 URL.
 *
 * 字段语义:
 *  - 静态字段 (url/method/fieldName/formData/queryParams/headers/withCredentials):
 *    请求构造参数, 全部可选 (除 url).
 *  - 动态字段 (getHeaders): 用于 token / 签名等运行时才拿到的 header.
 *  - 响应字段 (responseUrlPath/parseResponse): 从响应里拿到图片 URL.
 *  - 控制字段 (assertSuccess/onProgress/fetcher): 业务码判定 / 进度 / 自定义 fetch.
 *
 * 默认行为:
 *  - method = 'POST'
 *  - fieldName = 'file'
 *  - withCredentials = false
 *  - responseUrlPath = 'data.url' (对齐 mc-api-spec v1.6 信封 { code, message, data, ... })
 *  - assertSuccess = 不判断 (HTTP ok 即视为成功, 兼容纯 REST 风格后端)
 */
export interface FcImagePickerServerConfig {
  /** 上传 URL. 相对路径基于当前 origin 解析. */
  url: string
  /** HTTP method. 默认 'POST'. */
  method?: 'POST' | 'PUT'
  /** file 字段名. 默认 'file'. */
  fieldName?: string
  /** 额外 FormData 字段 (业务参数). */
  formData?: Record<string, string | Blob>
  /** URL query 参数. */
  queryParams?: Record<string, string>
  /** 静态请求头. */
  headers?: Record<string, string>
  /** 动态请求头 (token / 签名). Promise 支持异步. */
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>
  /** with credentials. 默认 false. */
  withCredentials?: boolean
  /** 从响应提取 URL 的 path. 默认 'data.url'. */
  responseUrlPath?: string
  /** 自定义响应解析器 (返回最终 URL). 优先级高于 responseUrlPath. */
  parseResponse?: (response: Response, json: unknown) => string | Promise<string>
  /** 上传进度. */
  onProgress?: (event: { loaded: number; total: number; percent: number }) => void
  /** 自定义 fetcher. 默认 window.fetch. */
  fetcher?: (url: string, init: RequestInit) => Promise<Response>
  /**
   * 上传成功断言 (HTTP 200 + 业务码 != 0 场景).
   * throws 抛错; 不 throw 视为成功.
   * 默认不判断 (只看 HTTP status).
   * 注意: HTTP !ok 时不会调用 (HTTP 错误已抛).
   */
  assertSuccess?: (response: Response, json: unknown) => void
}

/** FcImagePicker 全部 props 类型 (供业务 wrapper 继承) */
export interface FcImagePickerProps {
  // ===== Model =====
  modelValue: string | null

  // ===== Visual =====
  mode?: 'inline' | 'popover'
  shape?: 'rect' | 'circle'
  width?: number
  height?: number
  placeholder?: string
  disabled?: boolean
  coverMenu?: boolean
  clickAction?: 'upload' | 'menu'

  // ===== File handling (SDK 内部处理) =====
  allowedTypes?: string[]
  maxSize?: number
  accept?: string
  convertFormat?: 'webp' | 'png' | 'jpg' | null
  maxEdge?: number | null
  blobMode?: boolean

  // ===== Tabs =====
  showSources?: string[]
  defaultTab?: string
  sourceLabels?: Record<string, string>
  sourceIcons?: Record<string, string>

  // ===== Picker behavior =====
  pasteEnabled?: boolean
  multiple?: boolean

  // ===== URL mode =====
  urlMode?: 'reference' | 'download'
  sameOriginPath?: string[]

  // ===== Dialog =====
  dialogWidth?: number
  dialogHeight?: number
  dialogTitle?: string

  // ===== Upload (二选一: upload 函数 或 server 配置; 都不传则需 blobMode=true) =====
  upload?: (file: File) => Promise<string>

  /** 上传服务配置. 与 upload 二选一; 同时传时 upload 优先. */
  server?: FcImagePickerServerConfig

  // ===== Recent images (OPTIONAL) =====
  recentStorageKey?: string

  // ===== i18n =====
  t?: (key: string, params?: Record<string, unknown>) => string
}

const props = withDefaults(defineProps<FcImagePickerProps>(), {
  mode: 'inline',
  shape: 'rect',
  width: 100,
  height: 100,
  placeholder: '',
  disabled: false,
  coverMenu: false,
  clickAction: 'upload',
  allowedTypes: () => ['image/jpeg', 'image/png', 'image/webp', 'image/gif'],
  maxSize: 10,
  showSources: () => ['upload', 'url', 'recent'],
  defaultTab: 'recent',
  sourceLabels: () => ({}),
  sourceIcons: () => ({}),
  pasteEnabled: true,
  multiple: false,
  convertFormat: 'webp',
  maxEdge: 4096,
  blobMode: false,
  urlMode: 'reference',
  sameOriginPath: () => [],
  dialogWidth: 840,
  dialogHeight: 720,
  dialogTitle: '',
  t: undefined,
  recentStorageKey: undefined,
  upload: undefined,
  server: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [url: string | null]
  select: [url: string]
  multi: [urls: string[]]
  uploading: [isUploading: boolean]
  error: [message: string]
}>()

const pickerRef = ref<InstanceType<typeof FcPicker> | null>(null)
const fileInputEl = ref<HTMLInputElement | null>(null)
const isUploading = ref(false)
const isDragOver = ref(false)
const isHovered = ref(false)
const previewVisible = ref(false)
const urlInput = ref('')
const activeSource = ref(props.defaultTab)
const ctxPos = ref({ x: 0, y: 0 })

// 默认英文翻译
const en = {
  'common.uploading': 'Uploading…',
  'common.confirm': 'Confirm',
  'common.clear': 'Clear',
  'common.close': 'Close',
  'menu.preview': 'Preview',
  'menu.delete': 'Delete',
  'menu.upload': 'Upload',
  'menu.paste': 'Paste',
  'menu.more': 'More',
  'menu.cancel': 'Cancel',
  'upload.hint': 'Click or drag image here',
  'upload.dropHint': 'Drop image here or click to browse',
  'upload.selectedHint': '{n} selected',
  'url.placeholder': 'Paste image URL…',
  'recent.empty': 'No recent images',
  'recent.remove': 'Remove',
} as const

// 工具: 安全取 label/icon
// label 解析顺序: props.sourceLabels (业务覆盖) → i18n `source.${s}` (项目 locales) → builtin 英文兜底 → 原值
function labelOf(s: string): string {
  if (props.sourceLabels?.[s]) return props.sourceLabels[s]
  const i18nKey = `source.${s}`
  const localized = t(i18nKey)
  if (localized && localized !== i18nKey) return localized
  return builtinSourceLabels[s] || s
}
function iconOf(s: string): string { return props.sourceIcons?.[s] || builtinSourceIcons[s] || 'ri-folder-line' }

const t = (key: string, params?: Record<string, unknown>): string => {
  if (props.t) return props.t(key, params)
  let str = (en as Record<string, string>)[key] ?? key
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      str = str.replace(`{${k}}`, String(v))
    }
  }
  return str
}

const UploadIcon = { render: () => h('i', { class: 'ri-upload-2-line' }) }
const { isMobile } = useResponsive()

// ========== Recent images (SDK 内 useRecentImages) ==========
const { images: recentImages, add: addToRecent, remove: removeFromRecent, clear: clearRecentImages, getAll: getRecentImages } = useRecentImages(props.recentStorageKey ?? 'fc-image-picker:recent')

// ========== Upload pipeline: props.upload > defaultUpload(server) > blobMode ==========
/**
 * 按 path 取嵌套值. 'data.url' → obj.data.url; 不存在返回 undefined.
 * 用于从 server 响应里提取图片 URL.
 */
function getPath(obj: unknown, path: string): unknown {
  if (!obj || !path) return undefined
  const segs = path.split('.')
  let cur: any = obj
  for (const s of segs) {
    if (cur == null) return undefined
    cur = cur[s]
  }
  return cur
}

/**
 * 默认上传实现: 用 server 配置组装 FormData + fetch + assertSuccess + 提取 URL.
 * 失败时 throw, 由 uploadFile 的 catch 转 emit('error').
 */
async function defaultUpload(file: File): Promise<string> {
  const cfg = props.server
  if (!cfg) throw new Error('FcImagePicker: server config missing')
  const method = cfg.method || 'POST'
  const fieldName = cfg.fieldName || 'file'

  // URL + query string
  let url = cfg.url
  if (cfg.queryParams) {
    const qs = new URLSearchParams(cfg.queryParams).toString()
    if (qs) url += (url.includes('?') ? '&' : '?') + qs
  }

  // FormData
  const fd = new FormData()
  fd.append(fieldName, file)
  if (cfg.formData) {
    for (const [k, v] of Object.entries(cfg.formData)) fd.append(k, v)
  }

  // Headers: 静态 + 动态
  const headers: Record<string, string> = { ...(cfg.headers || {}) }
  if (cfg.getHeaders) {
    const dyn = await cfg.getHeaders()
    Object.assign(headers, dyn)
  }

  // fetch (支持进度 via XHR 替代)
  const fetcher = cfg.fetcher || ((u: string, init: RequestInit) => fetch(u, init))

  // onProgress 需 XHR; fetch 无原生进度. 这里走 XHR 实现 (fetcher 未自定义时).
  if (cfg.onProgress && !cfg.fetcher) {
    const url_ = await new Promise<string>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open(method, url, true)
      xhr.withCredentials = !!cfg.withCredentials
      for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v)
      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) {
          cfg.onProgress!({ loaded: ev.loaded, total: ev.total, percent: Math.round(ev.loaded / ev.total * 100) })
        }
      }
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(xhr.responseText)
        } else {
          reject(new Error(`Upload failed: HTTP ${xhr.status}`))
        }
      }
      xhr.onerror = () => reject(new Error('Upload failed: network error'))
      xhr.send(fd)
    })
    // 把 response text 包成 Response-like 给后续 parse
    const json = safeParseJson(url_)
    await assertOk({ status: 200, ok: true } as Response, json, cfg)
    return extractUrl(json, cfg, url_)
  }

  const res = await fetcher(url, {
    method,
    body: fd,
    headers,
    credentials: cfg.withCredentials ? 'include' : 'same-origin',
  })
  if (!res.ok) throw new Error(`Upload failed: HTTP ${res.status}`)
  const text = await res.text()
  const json = safeParseJson(text)
  await assertOk(res, json, cfg)
  return extractUrl(json, cfg, text)
}

function safeParseJson(text: string): unknown {
  try { return JSON.parse(text) } catch { return text }
}

async function assertOk(res: Response, json: unknown, cfg: FcImagePickerServerConfig) {
  if (cfg.assertSuccess) {
    await cfg.assertSuccess(res, json)
  }
}

function extractUrl(json: unknown, cfg: FcImagePickerServerConfig, rawText: string): string {
  if (cfg.parseResponse) {
    const r = cfg.parseResponse({} as Response, json)
    if (typeof r === 'string') return r
    // Promise<string> — 罕见, 上游已 await 过; 退回 raw
    return rawText
  }
  const path = cfg.responseUrlPath ?? 'data.url'
  const v = getPath(json, path)
  if (typeof v === 'string' && v) return v
  throw new Error(`FcImagePicker: cannot extract url from response (path="${path}")`)
}

/**
 * 最终 upload 函数:
 *  1. props.upload (业务自定义) — 优先
 *  2. defaultUpload (server 配置驱动)
 *  3. null (blobMode 兜底, 由 processToUrl 处理)
 */
const effectiveUpload = computed<((file: File) => Promise<string>) | null>(() => {
  if (props.upload) return props.upload
  if (props.server) return (f: File) => defaultUpload(f)
  return null
})

const acceptAttr = computed(() => props.accept || props.allowedTypes.join(','))
const typeLabels = computed(() => props.allowedTypes.map(t => (t.split('/')[1] || 'img').toUpperCase()).join(' / '))
const multiHint = computed(() => t('upload.dropHint') + ` (${typeLabels.value}, ≤${props.maxSize}MB)`)

// ========== Sources & action items ==========
const builtinSourceIcons: Record<string, string> = {
  upload: 'ri-upload-cloud-2-line',
  url: 'ri-link',
  recent: 'ri-time-line',
}
const builtinSourceLabels: Record<string, string> = {
  upload: 'Upload',
  url: 'URL',
  recent: 'Recent',
}

const visibleSources = computed(() => {
  const srcs: Array<{ value: string; icon: string; label: string }> = []
  for (const s of props.showSources) {
    if (s === 'upload' || s === 'url') {
      srcs.push({ value: s, icon: iconOf(s), label: labelOf(s) })
    } else if (s === 'recent' && props.recentStorageKey) {
      srcs.push({ value: s, icon: iconOf(s), label: labelOf(s) })
    } else if (s !== 'recent') {
      srcs.push({ value: s, icon: iconOf(s), label: labelOf(s) })
    }
  }
  return srcs
})

const actionItems = computed<MenuItem[]>(() => {
  const items: MenuItem[] = []
  if (props.modelValue) items.push({ value: '_preview', icon: 'ri-eye-line', label: t('menu.preview') })
  if (props.pasteEnabled) items.push({ value: '_paste', icon: 'ri-clipboard-line', label: t('menu.paste') })
  for (const s of props.showSources) {
    items.push({ value: s, icon: iconOf(s), label: labelOf(s) })
  }
  if (props.modelValue) items.push({ value: '_delete', icon: 'ri-delete-bin-line', label: t('menu.delete'), danger: true })
  return items
})

// ========== Multi-select state ==========
const selectedSet = reactive<Set<string>>(new Set())
const selectedList = computed(() => Array.from(selectedSet))
// 上传 tab 暂存 (本对话框会话期内上传的 URL, 关窗 / 确认后清空)
const pendingUploads = ref<string[]>([])
function isSelected(url: string) { return selectedSet.has(url) }
function onRecentClick(url: string) {
  if (!props.multiple) {
    handleSelect(url)
    return
  }
  if (selectedSet.has(url)) selectedSet.delete(url)
  else selectedSet.add(url)
}
function removeRecent(url: string) {
  // 多选模式下同时清掉选中态, 避免 ghost 选中
  selectedSet.delete(url)
  removeFromRecent(url)
}
function clearSelection() { selectedSet.clear() }

/** 打开 dialog (重置上次选择 + 清空 upload tab 暂存) — 内部统一入口 */
function openPickerDialog() {
  console.log('[DIAG:dialog] openPickerDialog → clear pendingUploads:', [...pendingUploads.value])
  clearSelection()
  pendingUploads.value = []
  pickerRef.value?.openDialog()
}
function confirmMulti() {
  console.log('[DIAG:confirm] selectedList', selectedList.value.length, [...selectedList.value])
  if (selectedList.value.length === 0) return
  emit('multi', selectedList.value)
  pickerRef.value?.closeDialog()
  clearSelection()
}

// ========== Trigger / panel actions ==========
function onSlotClick() {
  if (props.disabled) return
  if (isMobile.value) {
    pickerRef.value?.openActionSheet()
    return
  }
  if (props.clickAction === 'menu') {
    activeSource.value = props.defaultTab
    openPickerDialog()
    return
  }
  triggerUpload()
}
function onContextMenu(pos: { x: number; y: number }) {
  ctxPos.value = pos
}
function openMorePanel() {
  if (props.disabled) return
  activeSource.value = props.defaultTab
  openPickerDialog()
}
function closePanel() { pickerRef.value?.closeDialog() }
function onContextSelect(value: string) {
  if (value === '_preview') previewVisible.value = true
  else if (value === '_paste') onPasteClick()
  else if (value === '_delete') clearImage()
  else openSourceTab(value)
}
function onSlotSelect(url: string) { handleSelect(url) }
/** slot 多选 toggle: 多选模式 toggle selectedSet; 单选模式直接 select */
function onSlotToggle(url: string) {
  if (!props.multiple) {
    handleSelect(url)
    return
  }
  if (selectedSet.has(url)) selectedSet.delete(url)
  else selectedSet.add(url)
}
function openSourceTab(source: string) {
  activeSource.value = source
  if (source === 'upload') triggerUpload()
  else openPickerDialog()
}
function clearImage() { emit('update:modelValue', null) }

// ========== File selection / upload pipeline ==========
function triggerUpload() { fileInputEl.value?.click() }

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  await processFiles(files)
  input.value = ''
}

async function processFiles(files: File[]) {
  if (files.length === 0) return
  // 判定场景: 多图模式 + dialog 开 + 当前在 upload tab → 暂存 pendingUploads (用户后续点"确定"才 emit)
  //          否则 (单图模式 / 直接点 picker 槽位 / 拖入未开 dialog 等) → 直接 emit, 槽位立刻拿图
  const shouldQueue = props.multiple && (pickerRef.value?.isDialogOpen?.() ?? false) && activeSource.value === 'upload'
  // 多文件: 全部上传完 → queue 或 emit multi
  if (files.length > 1) {
    isUploading.value = true
    emit('uploading', true)
    try {
      const collected: string[] = []
      for (const f of files) {
        try {
          const url = await processToUrl(f)
          if (url) {
            if (shouldQueue) queuePendingUpload(url)
            else collected.push(url)
          }
        } catch (err) {
          logger.warn('[FcImagePicker] one file failed:', err)
        }
      }
      if (!shouldQueue && collected.length > 0) {
        emit('multi', collected)
      }
    } finally {
      isUploading.value = false
      emit('uploading', false)
    }
    return
  }
  // 单文件
  await uploadFile(files[0]!)
}

/** SDK 内部处理: 验证 + 转换 + 缩放 → 然后调用 props.upload 拿 URL */
async function processToUrl(file: File): Promise<string | null> {
  if (!props.allowedTypes.includes(file.type)) {
    emit('error', `Invalid format: ${file.type}`)
    return null
  }
  const sizeMB = file.size / 1024 / 1024
  if (sizeMB > props.maxSize) {
    emit('error', `File too large: ${sizeMB.toFixed(1)}MB (max ${props.maxSize}MB)`)
    return null
  }
  let blob: Blob = file
  let name = file.name
  const target = props.convertFormat
  if (target) {
    const targetMime = target === 'webp' ? 'image/webp' : target === 'png' ? 'image/png' : 'image/jpeg'
    if (file.type !== targetMime) {
      blob = await convertImage(file, targetMime)
      const ext = target === 'jpg' ? 'jpg' : target
      name = file.name.replace(/\.[^.]+$/, '') + '.' + ext
    }
  }
  const edge = props.maxEdge
  if (edge && edge > 0) {
    const scaled = await downscaleToMaxEdge(blob, edge)
    if (scaled !== blob) blob = scaled
  }
  if (props.blobMode && !effectiveUpload.value) {
    const u = URL.createObjectURL(blob)
    trackBlobUrl(u)
    return u
  }
  const uploadFn = effectiveUpload.value
  if (!uploadFn) {
    throw new Error('FcImagePicker: no upload method (configure `server` or `upload`, or set blobMode=true)')
  }
  const uploadTarget = new File([blob], name, { type: blob.type || file.type })
  return await uploadFn(uploadTarget)
}

async function uploadFile(file: File) {
  console.log('[DIAG:upload] START', file?.name, file?.type, file?.size)
  isUploading.value = true
  emit('uploading', true)
  try {
    const url = await processToUrl(file)
    console.log('[DIAG:upload] processToUrl returned', url)
    if (url) {
      // 多图模式 + dialog 开 + upload tab → 暂存 (用户点"确定"才 emit)
      // 单图模式 / dialog 没开 → 直接 handleSelect: emit('update:modelValue') + 关 dialog (如果开着) + 加 recent
      const shouldQueue = props.multiple && (pickerRef.value?.isDialogOpen?.() ?? false) && activeSource.value === 'upload'
      if (shouldQueue) {
        queuePendingUpload(url)
      } else {
        console.log('[DIAG:upload] direct emit handleSelect (no dialog or not upload tab)', url)
        handleSelect(url)
      }
    }
    console.log('[DIAG:upload] DONE', { url, pendingLen: pendingUploads.value.length, selectedSize: selectedSet.size })
  } catch (err) {
    console.log('[DIAG:upload] ERROR', err)
    emit('error', err instanceof Error ? err.message : 'Upload failed')
    logger.error('[FcImagePicker] upload failed:', err)
  } finally {
    isUploading.value = false
    emit('uploading', false)
  }
}

/** 把已上传 URL 暂存到当前对话框会话; 自动进选中态 */
function queuePendingUpload(url: string) {
  console.log('[DIAG:queue] IN', url, 'pendingUploads-before:', [...pendingUploads.value], 'selected-size:', selectedSet.size)
  // 去重: 已有同 URL → 选中态维护一下就 return, 不重复 push
  if (pendingUploads.value.includes(url)) {
    selectedSet.add(url)
    console.log('[DIAG:queue] DEDUP-HIT (url already in pendingUploads), selected updated')
    return
  }
  pendingUploads.value.push(url)
  selectedSet.add(url)
  addToRecent(url)
  console.log('[DIAG:queue] OUT', 'pendingUploads-after:', [...pendingUploads.value], 'selectedSet-has:', selectedSet.has(url))
}

/** 切换暂存图选中 (upload tab 专用, 不直接发 emit) */
function onPendingToggle(url: string) {
  if (selectedSet.has(url)) selectedSet.delete(url)
  else selectedSet.add(url)
}

// ========== Blob URL lifecycle ==========
const blobUrlPool = new Set<string>()
function trackBlobUrl(url: string) {
  const prev = props.modelValue
  if (prev && prev.startsWith('blob:') && blobUrlPool.has(prev)) {
    URL.revokeObjectURL(prev)
    blobUrlPool.delete(prev)
  }
  blobUrlPool.add(url)
}

// ========== URL tab ==========
function confirmUrl() {
  const raw = urlInput.value.trim()
  if (!raw) return
  const finalUrl = raw.startsWith('http') ? raw : 'https://' + raw
  const isSameOrigin = matchesSameOrigin(finalUrl, props.sameOriginPath)
  const mode = isSameOrigin ? 'reference' : props.urlMode
  // URL mode 走 download 时, 业务需要自己 fetch + 上传, SDK 不直接处理
  if (mode === 'download') {
    emit('error', 'URL download mode requires custom handling')
    return
  }
  emit('select', finalUrl)
  urlInput.value = ''
  // 直接走 handleSelect 走正常 update:modelValue + 关 dialog + 加 recent;
  // 留 emit('select') 是为了兼容曾经绑定过 @select 的旧调用方 (现在已无消费者, 留作保险).
  handleSelect(finalUrl)
}

function matchesSameOrigin(url: string, patterns: readonly string[]): boolean {
  if (!patterns || patterns.length === 0) return false
  return patterns.some(p => {
    if (!p) return false
    const re = new RegExp('^' + p.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$')
    return re.test(url)
  })
}

// ========== Paste handling ==========
function handleGlobalPaste(e: ClipboardEvent) {
  if (!props.pasteEnabled || props.disabled || isUploading.value) return
  const tag = document.activeElement?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA') return
  // 鼠标放在 slot 上 OR dialog 开且在 upload tab, 都接收粘贴
  const dialogOpen = pickerRef.value?.isDialogOpen?.() ?? false
  const inUploadTab = dialogOpen && activeSource.value === 'upload'
  if (!isHovered.value && !inUploadTab) return
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      e.preventDefault()
      const file = item.getAsFile()
      if (file) uploadFile(file)
      return
    }
  }
}

async function onPasteClick() {
  if (!props.pasteEnabled || props.disabled || isUploading.value) return
  if (navigator.clipboard?.read) {
    try {
      const items = await navigator.clipboard.read()
      for (const item of items) {
        for (const type of item.types) {
          if (type.startsWith('image/')) {
            const blob = await item.getType(type)
            const file = new File([blob], `pasted.${type.split('/')[1] || 'png'}`, { type })
            uploadFile(file)
            return
          }
        }
      }
      emit('error', 'Clipboard has no image')
    } catch (err) {
      logger.warn('[FcImagePicker] clipboard.read failed:', err)
      emit('error', 'Clipboard access denied')
    }
    return
  }
  emit('error', 'Clipboard API not supported')
}

// ========== Drag-drop ==========
function handleDrop(e: DragEvent) {
  isDragOver.value = false
  const files = e.dataTransfer?.files
  if (!files || files.length === 0) return
  e.preventDefault()
  const imgs = Array.from(files).filter(f => f.type.startsWith('image/'))
  if (imgs.length === 0) return
  if (props.multiple && imgs.length > 1) processFiles(imgs)
  else uploadFile(imgs[0]!)
}

// ========== Selection handlers ==========
function handleSelect(url: string) {
  if (url.startsWith('blob:')) trackBlobUrl(url)
  else addToRecent(url)
  emit('update:modelValue', url)
  pickerRef.value?.closeDialog()
}

useEventListener(document, 'paste', handleGlobalPaste)

onUnmounted(() => {
  blobUrlPool.forEach(u => URL.revokeObjectURL(u))
  blobUrlPool.clear()
})

defineExpose({
  uploadFile,
  triggerUpload,
  onPasteClick,
  openPanel: () => pickerRef.value?.openDialog(),
  closePanel: () => pickerRef.value?.closeDialog(),
  clearImage,
  isBlobImage: () => !!props.modelValue && props.modelValue.startsWith('blob:'),
  getRecentImages,
  clearRecentImages,
  setTab: (tab: string) => { activeSource.value = tab },
})
</script>

<style scoped lang="scss">
.fc-image-picker__slot {
  position: relative;
  display: inline-block;
}
.fc-image-picker__preview,
.fc-image-picker__empty {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
  background: var(--app-bg-muted, #f5f5f5);
  border: 1px dashed var(--app-separator, #e5e5e5);
  border-radius: var(--radius-md, 8px);
  transition: border-color 0.15s, background 0.15s;
  &:hover { border-color: var(--app-primary, #007aff); }
  &.drag-over {
    background: color-mix(in srgb, var(--app-primary, #007aff) 8%, transparent);
    border-color: var(--app-primary, #007aff);
  }
  &.shape-circle { border-radius: 50%; }
  &.cover-mode { border: 0; }
}
.fc-image-picker__img { width: 100%; height: 100%; object-fit: cover; }
.fc-image-picker__empty {
  flex-direction: column;
  gap: 8px;
  color: var(--app-text-tertiary, #999);
  font-size: 13px;
  i { font-size: 28px; }
}
.fc-image-picker__loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--app-text-tertiary, #999);
  font-size: 13px;
  i { font-size: 28px; }
  .spinning { animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
}

.fc-image-picker__top-bar,
.fc-image-picker__cover-menu {
  position: absolute;
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}
.fc-image-picker__slot:hover .fc-image-picker__top-bar,
.fc-image-picker__slot:hover .fc-image-picker__cover-menu { opacity: 1; }
.fc-image-picker__top-bar { top: 2px; right: 2px; }
.fc-image-picker__cover-menu { bottom: 2px; right: 2px; }

.bar-btn, .cover-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: var(--app-overlay-strong, rgba(0, 0, 0, 0.6));
  color: var(--app-on-primary, #fff);
  border-radius: var(--radius-sm, 6px);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  &:hover { background: var(--app-overlay-softer, rgba(0, 0, 0, 0.8)); }
  &.danger { color: var(--app-color-danger, #ff3b30); }
}

.fc-image-picker__popover-trigger { display: inline-block; }

.fc-image-picker__panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.fc-image-picker__tabs {
  // el-tabs 紧凑化: header 紧贴顶部, nav 等宽撑满, item 居中
  --el-tabs-header-height: 36px;

  .el-tabs__header {
    margin: 0 0 8px;
    padding: 4px;
    background: var(--app-bg-page, #fafafa);
    border-radius: var(--radius-md, 8px);
    border: none;
  }
  .el-tabs__nav-wrap {
    width: 100%;
    &::after { display: none; }
  }
  .el-tabs__nav {
    width: 100%;
    display: flex;
  }
  .el-tabs__item {
    flex: 1;
    padding: 0 8px;
    justify-content: center;
    gap: 6px;
    color: var(--app-text-secondary, #666);
    font-size: 13px;
    font-weight: 500;
    border: none;
    height: 32px;
    line-height: 32px;
    &:hover { color: var(--app-primary, #409eff); }
    &.is-active { color: var(--app-primary, #409eff); }
  }
  .el-tabs__active-bar { display: none; }
  .el-tabs__content { display: none; }

  .fc-image-picker__tab-label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    i { font-size: 14px; }
  }
}

.fc-image-picker__content { flex: 1; min-height: 0; overflow: auto; }
.fc-image-picker__upload-zone {
  height: 100%;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.fc-image-picker__upload-empty {
  flex: 1;
  padding: 24px 16px;
  border-radius: var(--radius-md, 8px);
  transition: background 0.15s, border-color 0.15s;
  border: 1px dashed transparent;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  &:hover { background: var(--app-bg-page, #fafafa); border-color: var(--app-separator, #e5e5e5); }
  &.drag-over {
    background: color-mix(in srgb, var(--app-primary, #007aff) 8%, transparent);
    border-color: var(--app-primary, #007aff);
  }
}
.fc-image-picker__upload-empty-body {
  flex: 1;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  text-align: center;
  p { margin: 4px 0; color: var(--app-text-secondary, #666); font-size: 13px; }
  .fc-image-picker__upload-icon { font-size: 36px; color: var(--app-text-tertiary, #999); }
  .fc-image-picker__upload-subhint { color: var(--app-text-tertiary, #999); font-size: 11px; }
}
.fc-image-picker__upload-empty-footer {
  width: 100%;
  display: flex;
  justify-content: flex-start;
}
.fc-image-picker__upload-pending {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 240px;
}
.fc-image-picker__upload-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(72px, 1fr));
  gap: 8px;
  padding: 4px 2px;
}
.fc-image-picker__upload-card {
  position: relative;
  aspect-ratio: 1;
  border: 2px solid transparent;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: var(--app-bg-page, #fafafa);
  transition: border-color 0.15s, transform 0.15s;
  &:hover { transform: scale(1.02); }
  &.selected {
    border-color: var(--app-primary, #007aff);
    box-shadow: 0 0 0 1px var(--app-primary, #007aff);
  }
  img { width: 100%; height: 100%; object-fit: cover; }
}
.fc-image-picker__upload-add {
  aspect-ratio: 1;
  border: 1px dashed var(--app-separator, #e5e5e5);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--app-text-tertiary, #999);
  font-size: 24px;
  transition: all 0.15s;
  &:hover {
    border-color: var(--app-primary, #007aff);
    color: var(--app-primary, #007aff);
    background: color-mix(in srgb, var(--app-primary, #007aff) 4%, transparent);
  }
}
.fc-image-picker__upload-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-extra-light, #f0f0f0);
}
.fc-image-picker__upload-footer-tip {
  flex: 1;
  text-align: center;
  color: var(--app-text-secondary, #666);
  font-size: 12px;
}
.fc-image-picker__url-zone { padding: 8px 0; }
.fc-image-picker__recent-zone { padding: 4px 0; }
.fc-image-picker__empty-hint {
  padding: 32px 0;
  text-align: center;
  color: var(--app-text-tertiary, #999);
  font-size: 13px;
}
.fc-image-picker__recent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, 120px);
  gap: 8px;
  justify-content: start;
}
.fc-image-picker__recent-item {
  position: relative;
  aspect-ratio: 1;
  cursor: pointer;
  border-radius: var(--radius-sm, 6px);
  overflow: hidden;
  border: 2px solid transparent;
  img { width: 100%; height: 100%; object-fit: cover; }
  &.selected { border-color: var(--app-primary, #007aff); }
  .fc-image-picker__recent-check {
    position: absolute;
    top: 4px; left: 4px;
    width: 20px; height: 20px;
    background: var(--app-primary, #007aff);
    color: var(--app-on-primary, #fff);
    border-radius: 50%;
    display: flex; align-items: center; justify-content: center;
    font-size: 12px;
  }
  .fc-image-picker__recent-remove {
    position: absolute;
    top: 4px; right: 4px;
    width: 20px; height: 20px;
    padding: 0;
    border: none;
    border-radius: 50%;
    background: var(--app-overlay-strong, rgba(0, 0, 0, 0.6));
    color: var(--app-on-primary, #fff);
    cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    font-size: 14px;
    opacity: 0;
    transition: opacity 0.15s;
    &:hover { background: var(--app-color-danger, #ff3b30); }
  }
  &:hover .fc-image-picker__recent-remove { opacity: 1; }
}
.fc-image-picker__custom-zone {
  height: 100%;
  padding: 8px;
  overflow: auto;
}

.fc-image-picker__footer {
  padding: 8px 4px 0;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  font-size: 12px;
  color: var(--app-text-tertiary, #999);
}
.fc-image-picker__footer-tip { margin-right: auto; }
.fc-image-picker__footer-actions { display: flex; gap: 4px; }

.fc-image-picker__action-sheet {
  display: flex;
  flex-direction: column;
  background: var(--app-bg-card, #fff);
}
.fc-image-picker__action-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  border: none;
  background: transparent;
  color: var(--app-text-primary, #333);
  font-size: 15px;
  text-align: left;
  cursor: pointer;
  border-bottom: 1px solid var(--app-separator, #f0f0f0);
  i { font-size: 18px; color: var(--app-text-tertiary, #999); }
  &:last-child { border-bottom: none; }
  &.danger {
    color: var(--app-color-danger, #ff3b30);
    i { color: var(--app-color-danger, #ff3b30); }
  }
  &.cancel { justify-content: center; color: var(--app-text-tertiary, #999); }
}
</style>
