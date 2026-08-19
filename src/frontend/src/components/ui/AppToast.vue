<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts, dismiss } = useToast()
</script>

<template>
  <Teleport to="body">
    <div class="app-toast-wrap">
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="app-toast"
          :class="`tone-${t.type}`"
          role="status"
          @click.self="dismiss(t.id)"
        >
          <span class="dot" />
          <span class="msg">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.app-toast-wrap {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
.app-toast {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 380px;
  padding: 11px 16px;
  border-radius: 12px;
  font-size: 13px;
  color: var(--text-primary);
  background: rgba(16, 21, 31, 0.92);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-light);
  box-shadow: var(--shadow);
  pointer-events: auto;
  cursor: pointer;
}
.app-toast .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.tone-success {
  border-color: rgba(63, 206, 108, 0.35);
}
.tone-success .dot {
  background: var(--success);
  box-shadow: 0 0 8px var(--success);
}
.tone-error {
  border-color: rgba(255, 92, 92, 0.35);
}
.tone-error .dot {
  background: var(--danger);
  box-shadow: 0 0 8px var(--danger);
}
.tone-info {
  border-color: rgba(77, 208, 225, 0.35);
}
.tone-info .dot {
  background: var(--info);
  box-shadow: 0 0 8px var(--info);
}

.toast-enter-active {
  transition: all 0.3s var(--ease-spring);
}
.toast-leave-active {
  transition: all 0.2s var(--ease-out);
}
.toast-enter-from {
  opacity: 0;
  transform: translateX(24px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>