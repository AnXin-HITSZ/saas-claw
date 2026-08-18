<script setup lang="ts">
import { useToast } from '@/composables/useToast'
const { toasts } = useToast()
</script>

<template>
  <div class="toast-wrap">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast-${t.type}`">
      {{ t.message }}
    </div>
  </div>
</template>

<style scoped>
.toast-wrap {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}
.toast {
  padding: 8px 16px;
  border-radius: 6px;
  color: #fff;
  font-size: 14px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  animation: slide-in 0.2s ease;
}
.toast-success {
  background: #2ea121;
}
.toast-error {
  background: #f54a45;
}
.toast-info {
  background: #3370ff;
}
@keyframes slide-in {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
