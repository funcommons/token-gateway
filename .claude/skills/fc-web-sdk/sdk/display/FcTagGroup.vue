<template>
  <div class="fc-tag-group">
    <FcTag
      v-for="(tag, index) in tags"
      :key="`${tag}-${index}`"
      :color="color"
      :closable="editable"
      size="sm"
      @close="removeTag(index)"
    >{{ tag }}</FcTag>

    <input
      v-if="editable"
      ref="inputRef"
      v-model="newTag"
      class="fc-tag-group__input"
      :placeholder="placeholder"
      @keydown.enter="addTag"
      @keydown.backspace="handleBackspace"
    />
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcTagGroup' })
import { ref } from 'vue'
import { FcTag } from '@/components/sdk'

interface Props {
  tags: string[]
  editable?: boolean
  color?: 'primary' | 'gray' | 'success' | 'warning' | 'danger'
  placeholder?: string
}

const props = withDefaults(defineProps<Props>(), {
  editable: true,
  color: 'primary',
  placeholder: 'Add tag...',
})

const emit = defineEmits<{
  'update:tags': [tags: string[]]
}>()

const inputRef = ref<HTMLInputElement>()
const newTag = ref('')

function addTag() {
  const tag = newTag.value.trim()
  if (tag && !props.tags.includes(tag)) {
    const newTags = [...props.tags, tag]
    emit('update:tags', newTags)
    newTag.value = ''
  }
}

function removeTag(index: number) {
  const newTags = props.tags.filter((_, i) => i !== index)
  emit('update:tags', newTags)
}

function handleBackspace() {
  if (newTag.value === '' && props.tags.length > 0) {
    removeTag(props.tags.length - 1)
  }
}

function focus() {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<style scoped lang="scss">
.fc-tag-group {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 4px;
  border-radius: var(--app-radius-md, 8px);
  background: var(--app-bg-card, #fff);
  border: 1px solid var(--app-separator, #e5e5e5);
}

.fc-tag-group__input {
  flex: 1;
  min-width: 80px;
  border: none;
  outline: none;
  background: transparent;
  font-size: 12px;
  color: var(--app-text-primary, #333);
  padding: 2px 4px;

  &::placeholder {
    color: var(--app-text-tertiary, #999);
  }
}
</style>
