<script setup lang="ts">defineOptions({ name: 'FcMain' })
// FcMain — 主内容区壳.
// 默认走 vue-router 的 <router-view> + <keep-alive> + <transition>,
// host 也可整体覆盖 slot (比如塞自定义内容而不是 router-view).

interface Props {
  keepAlive?: boolean
  transitionName?: string
}

const props = withDefaults(defineProps<Props>(), {
  keepAlive: true,
  transitionName: 'fade',
})
</script>

<template>
  <main class="fc-main">
    <slot>
      <router-view v-slot="{ Component }">
        <transition :name="transitionName" mode="out-in">
          <keep-alive v-if="keepAlive">
            <component :is="Component" />
          </keep-alive>
          <component v-else :is="Component" />
        </transition>
      </router-view>
    </slot>
  </main>
</template>

<style scoped>
.fc-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  /* 主题感知背景, 跟着 html[data-theme] 切换; --app-main-bg 在 brands/_*.scss 里定义,
     缺省回退到 --app-bg-page (light/dark/orange-black 各自的 page 底色). */
  background: var(--app-main-bg, var(--app-bg-page));
  color: var(--app-text);
  transition: background 0.25s ease, color 0.25s ease;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
