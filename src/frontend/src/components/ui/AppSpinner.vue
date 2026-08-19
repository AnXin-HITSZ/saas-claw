<script setup lang="ts">
withDefaults(defineProps<{ size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })
</script>

<template>
  <span
    class="app-spinner"
    :class="`app-spinner-${size}`"
    role="status"
    aria-label="加载中"
  ></span>
</template>

<style scoped>
.app-spinner {
  display: inline-block;
  border-radius: 50%;
  border-style: solid;
  border-width: 2px;
  border-color: rgba(245, 168, 61, 0.2);
  border-top-color: var(--accent);
  animation: app-spin 0.7s linear infinite;
  flex-shrink: 0;
}
.app-spinner-sm {
  width: 14px;
  height: 14px;
}
.app-spinner-md {
  width: 20px;
  height: 20px;
}
.app-spinner-lg {
  width: 32px;
  height: 32px;
  border-width: 3px;
}
@keyframes app-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>