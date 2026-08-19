<script setup lang="ts">
withDefaults(defineProps<{ tone?: 'success' | 'danger' | 'warning' | 'info' | 'neutral'; pulse?: boolean }>(), {
  tone: 'neutral',
  pulse: false,
})
</script>

<template>
  <span class="app-tag" :class="[`tone-${tone}`, { pulse }]">
    <span class="dot" />
    <slot />
  </span>
</template>

<style scoped>
.app-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 11px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.app-tag.pulse .dot {
  animation: app-pulse 1.8s var(--ease-in-out) infinite;
}

.tone-success {
  color: var(--success);
  background: rgba(63, 206, 108, 0.12);
}
.tone-danger {
  color: var(--danger);
  background: rgba(255, 92, 92, 0.1);
}
.tone-warning {
  color: var(--warning);
  background: rgba(224, 168, 50, 0.12);
}
.tone-info {
  color: var(--info);
  background: rgba(77, 208, 225, 0.12);
}
.tone-neutral {
  color: var(--text-secondary);
  background: rgba(139, 150, 171, 0.12);
}

@keyframes app-pulse {
  0% {
    box-shadow: 0 0 0 0 currentColor;
  }
  70% {
    box-shadow: 0 0 0 4px transparent;
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}
</style>