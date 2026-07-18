<template>
  <Teleport to="body">
    <div v-if="show" class="app-modal-overlay" @click.self="$emit('close')" @keydown.esc="$emit('close')">
      <div class="app-modal" role="dialog" aria-modal="true" :aria-label="title">
        <h2 v-if="title">{{ title }}</h2>
        <slot />
        <div v-if="$slots.actions" class="app-modal-actions">
          <slot name="actions" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { watch } from "vue";

const props = defineProps({
  show: { type: Boolean, default: false },
  title: { type: String, default: "" },
});
const emit = defineEmits(["close"]);

function onKeydown(e) { if (e.key === "Escape") emit("close"); }

watch(() => props.show, v => {
  if (v) window.addEventListener("keydown", onKeydown);
  else window.removeEventListener("keydown", onKeydown);
});
</script>

<style scoped>
.app-modal-overlay {
  position: fixed; inset: 0; background: rgba(3, 5, 9, 0.72);
  display: flex; align-items: center; justify-content: center; z-index: 100;
  backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
}
.app-modal {
  background: var(--bg-surface); border: 1px solid var(--border-light);
  border-radius: var(--radius-lg); padding: 32px; width: 480px; max-width: 90vw;
  max-height: 85vh; overflow-y: auto;
  box-shadow: var(--shadow), 0 0 40px rgba(139, 124, 246, 0.08);
  animation: modal-enter 0.28s var(--ease-spring);
}
.app-modal h2 { margin-bottom: 20px; font-family: var(--font-display); font-weight: 700; letter-spacing: -0.02em; }
.app-modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
@keyframes modal-enter {
  from { opacity: 0; transform: scale(0.95) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}
</style>
