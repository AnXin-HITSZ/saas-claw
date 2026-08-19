<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="header-text">
      <h1>{{ title }}</h1>
      <p v-if="subtitle" class="page-sub">{{ subtitle }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: var(--section-gap);
  flex-wrap: wrap;
}
.header-text h1 {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0;
}
.page-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}
.page-header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}
</style>