<template>
  <Teleport to="body">
    <div class="toast-container" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast-${t.type}`">
          <span class="toast-dot"></span>
          <span class="toast-msg">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup>
import { useToastStore } from "../../composables/useToast.js";

const { toasts } = useToastStore();
</script>

<style scoped>
.toast-container {
  position: fixed; top: 20px; right: 20px; z-index: 1000;
  display: flex; flex-direction: column; gap: 10px; pointer-events: none;
}
.toast {
  display: flex; align-items: center; gap: 10px;
  min-width: 240px; max-width: 380px; padding: 12px 16px;
  background: rgba(16, 21, 31, 0.92); border: 1px solid var(--border-light);
  border-radius: 12px; box-shadow: var(--shadow);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  font-size: 13px; color: var(--text-primary); pointer-events: auto;
}
.toast-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto; }
.toast-success .toast-dot { background: var(--success); box-shadow: 0 0 8px var(--success); }
.toast-error .toast-dot { background: var(--danger); box-shadow: 0 0 8px var(--danger); }
.toast-info .toast-dot { background: var(--info); box-shadow: 0 0 8px var(--info); }
.toast-success { border-color: rgba(63, 206, 108, 0.35); }
.toast-error { border-color: rgba(255, 92, 92, 0.35); }
.toast-info { border-color: rgba(77, 208, 225, 0.35); }
.toast-enter-active { transition: all 0.3s var(--ease-spring); }
.toast-leave-active { transition: all 0.2s var(--ease-out); }
.toast-enter-from { opacity: 0; transform: translateX(24px); }
.toast-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
