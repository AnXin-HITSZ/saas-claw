<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ show: boolean; title?: string; width?: string }>()
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.show) emit('close')
}

watch(
  () => props.show,
  (v) => {
    if (v) document.addEventListener('keydown', onKey)
    else document.removeEventListener('keydown', onKey)
  },
)

onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="show" class="modal-overlay" @click.self="emit('close')">
        <div class="modal-panel" :style="width ? { width } : undefined" role="dialog" aria-modal="true">
          <header v-if="title" class="modal-head">
            <h2>{{ title }}</h2>
            <button class="modal-x" aria-label="关闭" @click="emit('close')">✕</button>
          </header>
          <div class="modal-body">
            <slot />
          </div>
          <footer v-if="$slots.actions" class="modal-actions">
            <slot name="actions" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(3, 5, 9, 0.72);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.modal-panel {
  width: 480px;
  max-width: calc(100vw - 32px);
  max-height: 85vh;
  overflow-y: auto;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow), 0 0 40px rgba(139, 124, 246, 0.15);
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 0;
}
.modal-head h2 {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  margin: 0;
}
.modal-x {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 14px;
  cursor: pointer;
  padding: 4px;
  transition: color 0.15s var(--ease-out);
}
.modal-x:hover {
  color: var(--text-primary);
}
.modal-body {
  padding: 20px 24px 8px;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px 24px;
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.28s var(--ease-spring);
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
.modal-fade-enter-active .modal-panel {
  animation: modal-pop 0.28s var(--ease-spring);
}
@keyframes modal-pop {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(10px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
</style>