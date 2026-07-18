<template>
  <span class="app-tag" :class="`tone-${tone}`">
    <span class="tag-dot" :class="{ pulse }"></span>
    <slot />
  </span>
</template>

<script setup>
defineProps({
  tone: { type: String, default: "neutral", validator: v => ["success", "danger", "warning", "info", "neutral"].includes(v) },
  pulse: { type: Boolean, default: false },
});
</script>

<style scoped>
.app-tag {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; padding: 3px 11px; border-radius: 999px; font-weight: 600;
}
.tag-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.tag-dot.pulse { animation: tag-pulse 1.8s var(--ease-in-out) infinite; }
@keyframes tag-pulse {
  0%, 100% { box-shadow: 0 0 0 0 currentColor; opacity: 1; }
  50% { box-shadow: 0 0 0 4px transparent; opacity: 0.6; }
}
.tone-success { background: rgba(63, 206, 108, 0.12); color: var(--success); }
.tone-danger { background: rgba(255, 92, 92, 0.1); color: var(--danger); }
.tone-warning { background: rgba(224, 168, 50, 0.12); color: var(--warning); }
.tone-info { background: rgba(77, 208, 225, 0.1); color: var(--info); }
.tone-neutral { background: rgba(139, 150, 171, 0.12); color: var(--text-secondary); }
</style>
