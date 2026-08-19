<script setup lang="ts">
import AppSpinner from './AppSpinner.vue'

defineProps<{
  variant?: 'primary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  loading?: boolean
  loadingText?: string
  disabled?: boolean
  type?: 'button' | 'submit'
}>()
</script>

<template>
  <button
    class="app-btn"
    :class="[`app-btn-${variant ?? 'primary'}`, size ? `app-btn-${size}` : '']"
    :disabled="disabled || loading"
    :type="type ?? 'button'"
    :aria-busy="loading ? 'true' : undefined"
  >
    <AppSpinner v-if="loading" size="sm" />
    <span>{{ loading ? (loadingText ?? '处理中…') : '' }}</span>
    <slot />
  </button>
</template>

<style scoped>
.app-btn {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 22px;
  font-size: 14px;
  font-weight: 600;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: transform 0.22s var(--ease-out), box-shadow 0.22s var(--ease-out),
    background 0.2s var(--ease-out), border-color 0.2s var(--ease-out), color 0.2s var(--ease-out);
}
.app-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.app-btn-primary {
  color: #0a0e14;
  background: linear-gradient(135deg, var(--accent), var(--accent-soft));
}
.app-btn-primary::after {
  content: '';
  position: absolute;
  top: 0;
  left: -80%;
  width: 40%;
  height: 100%;
  background: linear-gradient(115deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  transform: skewX(-20deg);
  transition: left 0.45s var(--ease-out);
}
.app-btn-primary:hover::after {
  left: 130%;
}
.app-btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: var(--glow-accent);
}
.app-btn-primary:disabled {
  box-shadow: none;
}

.app-btn-sm {
  padding: 6px 14px;
  font-size: 12.5px;
  border-radius: 8px;
  gap: 6px;
}
.app-btn-sm .app-spinner {
  width: 13px;
  height: 13px;
  border-width: 1.5px;
}

.app-btn-ghost {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border);
}
.app-btn-ghost:hover {
  background: var(--bg-raised);
  border-color: var(--border-light);
  color: var(--text-primary);
}

.app-btn-danger {
  color: #fff;
  background: linear-gradient(135deg, #e5484d, #c93a3f);
  box-shadow: 0 0 20px rgba(255, 92, 92, 0.18);
}
.app-btn-danger:hover {
  transform: translateY(-1px);
  box-shadow: 0 0 24px rgba(255, 92, 92, 0.3);
}
</style>