<script setup lang="ts">
defineOptions({ name: 'FcConfirm' })
/**
 * FcConfirm — 统一确认弹窗 (SDK).
 *
 * 解决: ElMessageBox.confirm 文案/按钮顺序/危险操作红色按钮每次写法都不一致.
 *
 * 用法 A (声明式, 推荐):
 *   <FcConfirm
 *     v-model:open="open"
 *     title="删除作品"
 *     content="删除后无法恢复, 是否继续?"
 *     variant="danger"
 *     :confirm-text="t('common.delete')"
 *     @confirm="onConfirm"
 *   />
 *
 * 用法 B (命令式, 通过 useFcConfirm):
 *   const { confirm } = useFcConfirm()
 *   if (await confirm({ title: '删除', variant: 'danger' })) { ... }
 *
 * 内置:
 *  - variant: default | danger | primary  (danger 自动用 danger 色 + danger icon)
 *  - i18n fallback: confirmText / cancelText 不传时走 t('common.confirm') / t('common.cancel')
 *  - 自动锁 body 滚动 (FcDialog 内置)
 *  - 默认居中, 点击遮罩不关闭 (避免误触), ESC 关闭触发 cancel
 */
import { computed, watch, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import FcDialog from './FcDialog.vue'

type ConfirmVariant = 'default' | 'danger' | 'primary'

interface Props {
  /** v-model:open */
  open?: boolean
  /** 标题. 不传走 t('common.tip'). */
  title?: string
  /** 正文. 走 #default slot 时此 prop 被忽略. */
  content?: string
  /** 视觉变体. danger 用红色 + 警告 icon. */
  variant?: ConfirmVariant
  /** 确认按钮文案. 不传走 t('common.confirm'). */
  confirmText?: string
  /** 取消按钮文案. 不传走 t('common.cancel'). */
  cancelText?: string
  /** 是否在确认时 loading (异步操作期间禁用按钮 + 显示 loading). */
  loading?: boolean
  /** 是否禁用确认按钮. */
  disabled?: boolean
  /** 弹窗宽度 (number=px). 默认 420. */
  width?: string | number
  /** 点击遮罩是否关闭. 默认 false (确认场景防误触). */
  closeOnClickModal?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  open: false,
  title: '',
  content: '',
  variant: 'default',
  confirmText: '',
  cancelText: '',
  loading: false,
  disabled: false,
  width: 420,
  closeOnClickModal: false,
})

const emit = defineEmits<{
  'update:open': [boolean]
  confirm: []
  cancel: []
}>()

const { t } = useI18n()

const innerOpen = ref(props.open)
watch(() => props.open, v => { innerOpen.value = v })

function close(result: 'confirm' | 'cancel') {
  if (props.loading) return
  innerOpen.value = false
  emit('update:open', false)
  if (result === 'confirm') emit('confirm')
  else emit('cancel')
}

const resolvedTitle = computed(() => props.title || t('common.tip'))
const resolvedConfirm = computed(() => props.confirmText || t('common.confirm'))
const resolvedCancel = computed(() => props.cancelText || t('common.cancel'))

const confirmButtonType = computed<'primary' | 'danger'>(() =>
  props.variant === 'danger' ? 'danger' : 'primary'
)
</script>

<template>
  <FcDialog
    v-model:open="innerOpen"
    :title="resolvedTitle"
    :width="width"
    :close-on-click-modal="closeOnClickModal"
    dialog-class="fc-confirm-dialog"
    body-class="fc-confirm-body"
    :with-header="true"
    :show-close="!loading"
  >
    <div class="fc-confirm__content" :class="`variant-${variant}`">
      <div v-if="$slots.icon || variant === 'danger'" class="fc-confirm__icon">
        <slot name="icon">
          <svg v-if="variant === 'danger'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 9v4m0 4h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </slot>
      </div>
      <div class="fc-confirm__body">
        <slot>{{ content }}</slot>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="loading" @click="close('cancel')">{{ resolvedCancel }}</el-button>
      <el-button
        :type="confirmButtonType"
        :loading="loading"
        :disabled="disabled"
        @click="close('confirm')"
      >
        {{ resolvedConfirm }}
      </el-button>
    </template>
  </FcDialog>
</template>

<style scoped lang="scss">
.fc-confirm__content {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 4px 0;
}

.fc-confirm__icon {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 1px;

  svg,
  :deep(svg) {
    width: 100%;
    height: 100%;
    display: block;
  }

  .variant-danger & {
    color: var(--app-color-danger, #ff3b30);
  }
}

.fc-confirm__body {
  flex: 1;
  font-size: var(--app-font-size-base, 14px);
  color: var(--app-text);
  line-height: 1.6;
  word-break: break-word;
}
</style>
