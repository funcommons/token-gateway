<script setup lang="ts">defineOptions({ name: 'FcSidePanel' })
/**
 * FcSidePanel — 侧边面板的通用壳.
 *
 * 支持四向 (rtl / ltr / ttb / btt), 默认右侧.
 *
 * 桌面 (≥768px): 渲染为 FcSection 卡片, 默认 width=280, 内部 section body 滚动.
 * 手机 (<768px): 不占布局, 渲染对应角的 FAB; 点击 FAB 打开 el-drawer.
 *   抽屉宽度复用 width prop (而非独立 size); el-drawer 去 header; 内部 FcSection
 *   透明化 (border/shadow/radius/bg 全 neutralize), 让 el-drawer 当唯一外框.
 */
import { computed } from 'vue'
import FcSection from '@/components/sdk/section/FcSection.vue'
import FcDrawer from '@/components/sdk/overlay/FcDrawer.vue'
import { useResponsive } from '@/composables'

interface Props {
  /** 桌面端宽度 / 抽屉宽度. 数字按 px, 字符串原样传给 CSS. 默认 280px. */
  width?: string | number
  /** 浮动按钮图标 class (remix-icon). 默认侧拉门图标. */
  fabIcon?: string
  /** 抽屉标题 (用于 FAB title 属性; el-drawer header 已去掉). */
  drawerTitle?: string
  /** 抽屉滑入方向. 默认 'rtl' (右侧). */
  drawerDirection?: 'rtl' | 'ltr' | 'ttb' | 'btt'
}

const props = withDefaults(defineProps<Props>(), {
  width: 280,
  fabIcon: 'ri-side-bar-fill',
  drawerTitle: '',
  drawerDirection: 'rtl',
})

const { isMobile } = useResponsive()
const drawerOpen = defineModel<boolean>('open', { default: false })

const widthValue = computed(() =>
  typeof props.width === 'number' ? `${props.width}px` : props.width,
)
</script>

<template>
  <!-- 桌面: 固定宽度侧边卡片, 内部滚动 -->
  <FcSection
    v-if="!isMobile"
    class="fc-side-panel"
    :style="{ width: widthValue }"
  >
    <slot />
  </FcSection>

  <!-- 手机: 浮动按钮 + 抽屉 -->
  <template v-else>
    <button
      class="fc-side-panel-fab"
      :title="drawerTitle"
      @click="drawerOpen = true"
    >
      <i :class="fabIcon" />
    </button>
    <FcDrawer
      v-model:open="drawerOpen"
      :with-header="false"
      :direction="drawerDirection"
      :size="widthValue"
      drawer-class="fc-side-panel-drawer"
    >
      <FcSection class="fc-side-panel-drawer-body">
        <!-- 对应角自定义关闭按钮 (el-drawer header 已去) -->
        <button
          class="fc-side-panel-close"
          :title="drawerTitle"
          @click="drawerOpen = false"
        >
          <i class="ri-close-line" />
        </button>
        <slot />
      </FcSection>
    </FcDrawer>
  </template>
</template>

<style scoped lang="scss" src="./FcSidePanel.styles.scss"></style>

<style>
.fc-side-panel-drawer .el-drawer__body {
  padding: 0;
}
/* 抽屉圆角: 只去掉贴屏幕边那一侧的圆角, 保留面向内容那一侧.
   direction=rtl: 右贴边 → 去掉右上/右下; 左上/左下保留.
   direction=ltr: 左贴边 → 去掉左上/左下; 右上/右下保留.
   direction=ttb: 上贴边 → 去掉左上/右上; 左下/右下保留.
   direction=btt: 下贴边 → 去掉左下/右下; 左上/右上保留. */
.fc-side-panel-drawer.el-drawer {
  border-radius: 8px !important;
}
.fc-side-panel-drawer.rtl {
  border-top-right-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
.fc-side-panel-drawer.ltr {
  border-top-left-radius: 0 !important;
  border-bottom-left-radius: 0 !important;
}
.fc-side-panel-drawer.ttb {
  border-top-left-radius: 0 !important;
  border-top-right-radius: 0 !important;
}
.fc-side-panel-drawer.btt {
  border-bottom-left-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
</style>
