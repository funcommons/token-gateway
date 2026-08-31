<script setup lang="ts">
// 404 page — applies the empty-state pattern from spec/material §4.1.
// Same el-empty + useBrandEmpty composition as the 3 demo cells in
// Material.vue, plus a #default slot for the "back to home" CTA.
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBrandEmpty } from '@/composables/useBrandEmpty'

const router = useRouter()
const { t } = useI18n()
const { svg } = useBrandEmpty()
</script>

<template>
  <div class="app-page not-found">
    <el-empty :image-size="160" :description="t('common.empty.notFound')">
      <template #image>
        <div class="not-found__svg" v-html="svg" />
      </template>
      <template #default>
        <el-button type="primary" @click="router.push('/home')">
          <i class="ri-arrow-left-line" />
          {{ t('common.empty.notFoundCta') }}
        </el-button>
      </template>
    </el-empty>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.not-found__svg {
  width: 160px;
  height: 160px;
  color: var(--app-text-tertiary);
}
.not-found__svg :deep(svg) {
  width: 100%;
  height: 100%;
}
</style>
