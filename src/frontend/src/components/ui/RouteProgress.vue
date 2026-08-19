<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const width = ref(0)
const visible = ref(false)
let timer: number | null = null

function start() {
  if (timer) return
  visible.value = true
  width.value = 10
  timer = window.setInterval(() => {
    // 每 120ms 随机往上涨，封顶 82%
    width.value = Math.min(82, width.value + Math.random() * 10)
  }, 120)
}
function done() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  width.value = 100
  setTimeout(() => {
    visible.value = false
    width.value = 0
  }, 220)
}

onMounted(() => {
  router.beforeEach(start)
  router.afterEach(done)
  router.onError(done)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <Transition name="route-progress">
    <div v-if="visible" class="route-progress" :style="{ width: width + '%' }" />
  </Transition>
</template>

<style scoped>
.route-progress {
  position: fixed;
  top: 0;
  left: 0;
  height: 2px;
  background: var(--gradient-aurora);
  box-shadow: 0 0 10px rgba(245, 168, 61, 0.55);
  z-index: 2000;
  transition: width 0.12s var(--ease-out), opacity 0.22s var(--ease-out);
}
.route-progress-enter-active,
.route-progress-leave-active {
  transition: opacity 0.22s var(--ease-out);
}
.route-progress-enter-from,
.route-progress-leave-to {
  opacity: 0;
}
</style>