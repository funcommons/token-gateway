<script setup lang="ts">
// AppMain — 主内容区壳.
// 默认走 vue-router 的 <router-view> + <keep-alive> + <transition>,
// host 也可整体覆盖 slot (比如塞自定义内容而不是 router-view).
//
// vue-router 视为 peer dep. SDK 不直接 import useRouter, 仍走 <router-view>.

interface Props {
  /** 是否 keep-alive 路由组件, 默认 true */
  keepAlive?: boolean
  /** 过渡名, 默认 'fade' */
  transitionName?: string
}

const props = withDefaults(defineProps<Props>(), {
  keepAlive: true,
  transitionName: 'fade',
})
</script>

<template>
  <main class="app-main">
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
.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
/* 默认 fade 过渡, host 可通过 transitionName prop 覆盖 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
