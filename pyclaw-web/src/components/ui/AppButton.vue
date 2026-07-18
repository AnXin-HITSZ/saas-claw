<template>
  <button
    class="app-button"
    :class="`variant-${variant}`"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
    @click="$emit('click', $event)"
  >
    <AppSpinner v-if="loading" size="sm" class="btn-spinner" />
    <span v-if="loading" class="btn-loading-text">{{ loadingText }}</span>
    <slot v-else />
  </button>
</template>

<script setup>
import AppSpinner from "./AppSpinner.vue";

defineProps({
  variant: { type: String, default: "primary", validator: v => ["primary", "ghost", "danger"].includes(v) },
  loading: { type: Boolean, default: false },
  loadingText: { type: String, default: "处理中..." },
  disabled: { type: Boolean, default: false },
  type: { type: String, default: "button" },
});
defineEmits(["click"]);
</script>

<style scoped>
.app-button {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  padding: 9px 22px; font-size: 14px; font-weight: 600; border-radius: 10px;
  border: 1px solid transparent; font-family: inherit; cursor: pointer;
  transition: all 0.22s var(--ease-out); position: relative; overflow: hidden;
}
.variant-primary { color: #0a0e14; background: linear-gradient(135deg, var(--accent), var(--accent-soft)); }
.variant-primary::after {
  content: ""; position: absolute; inset: 0;
  background: linear-gradient(135deg, transparent 40%, rgba(255, 255, 255, 0.16) 50%, transparent 60%);
  transform: translateX(-100%); transition: transform 0.45s var(--ease-out);
}
.variant-primary:hover:not(:disabled)::after { transform: translateX(100%); }
.variant-primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: var(--glow-accent); }
.variant-ghost { color: var(--text-secondary); background: rgba(255, 255, 255, 0.03); border-color: var(--border); }
.variant-ghost:hover:not(:disabled) { background: var(--bg-raised); border-color: var(--border-light); color: var(--text-primary); }
.variant-danger { color: #fff; background: linear-gradient(135deg, #e5484d, #c93a3f); }
.variant-danger:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 0 20px rgba(255, 92, 92, 0.3); }
.app-button:active:not(:disabled) { transform: translateY(0); }
.app-button:disabled { opacity: 0.45; cursor: not-allowed; }
.btn-spinner :deep(.app-spinner) { border-color: rgba(10, 14, 20, 0.25); border-top-color: currentColor; }
.variant-ghost .btn-spinner :deep(.app-spinner) { border-color: rgba(245, 168, 61, 0.2); border-top-color: var(--accent); }
.btn-loading-text { white-space: nowrap; }
</style>
