<template>
  <span class="app-spinner" :class="`size-${size}`" role="status" aria-label="加载中"></span>
</template>

<script setup>
defineProps({
  size: { type: String, default: "md", validator: v => ["sm", "md", "lg"].includes(v) },
});
</script>

<style scoped>
.app-spinner {
  display: inline-block;
  border-radius: 50%;
  border: 2px solid rgba(245, 168, 61, 0.2);
  border-top-color: var(--accent);
  animation: spin 0.7s linear infinite;
}
.size-sm { width: 14px; height: 14px; border-width: 2px; }
.size-md { width: 20px; height: 20px; }
.size-lg { width: 32px; height: 32px; border-width: 3px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
