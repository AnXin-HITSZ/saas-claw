<template>
  <div class="app-empty">
    <div class="app-empty-icon">{{ icon }}</div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <slot />
  </div>
</template>

<script setup>
defineProps({
  icon: { type: String, default: "◇" },
  title: { type: String, required: true },
  description: { type: String, default: "" },
});
</script>

<style scoped>
.app-empty { text-align: center; padding: 64px 24px; }
.app-empty-icon { font-size: 44px; margin-bottom: 16px; opacity: 0.5; }
.app-empty h3 { font-size: 17px; font-weight: 600; margin-bottom: 6px; color: var(--text-secondary); }
.app-empty p { font-size: 14px; color: var(--text-muted); max-width: 420px; margin: 0 auto 20px; line-height: 1.6; }
</style>
