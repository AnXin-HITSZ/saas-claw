<script setup lang="ts">
withDefaults(defineProps<{ icon?: string; title: string; description?: string }>(), {
  icon: '◇',
  description: '',
})
</script>

<template>
  <div class="app-empty">
    <div class="icon">{{ icon }}</div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="app-empty-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.app-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 64px 24px;
  text-align: center;
}
.icon {
  font-size: 44px;
  opacity: 0.5;
  margin-bottom: 12px;
  line-height: 1;
}
h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-secondary);
  margin: 0 0 8px;
}
p {
  font-size: 14px;
  color: var(--text-muted);
  max-width: 420px;
  margin: 0 0 16px;
}
.app-empty-actions {
  display: flex;
  gap: 10px;
}
</style>