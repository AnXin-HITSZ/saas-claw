<script setup lang="ts">
withDefaults(defineProps<{ glow?: boolean; hoverable?: boolean }>(), {
  glow: false,
  hoverable: true,
})
</script>

<template>
  <div class="app-card" :class="{ glow, static: !hoverable }">
    <slot />
  </div>
</template>

<style scoped>
.app-card {
  position: relative;
  padding: var(--card-padding);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 40%),
    var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition: border-color 0.25s var(--ease-out), transform 0.25s var(--ease-out),
    box-shadow 0.25s var(--ease-out);
}
.app-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 24px;
  right: 24px;
  height: 1px;
  background: var(--gradient-aurora);
  opacity: 0;
  transition: opacity 0.25s var(--ease-out);
}
.app-card:hover::before {
  opacity: 0.7;
}
.app-card:hover:not(.static) {
  border-color: var(--border-light);
  transform: translateY(-2px);
  box-shadow: var(--shadow);
}
.app-card.glow {
  border-color: rgba(245, 168, 61, 0.3);
  box-shadow: 0 0 24px rgba(245, 168, 61, 0.12);
}
.app-card.glow::before {
  opacity: 0.7;
}
</style>