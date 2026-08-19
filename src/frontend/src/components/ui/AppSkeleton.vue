<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'text' | 'rect' | 'circle'
    width?: string
    height?: string
    lines?: number
  }>(),
  { variant: 'rect', width: '100%', height: '16px', lines: 1 },
)
</script>

<template>
  <div v-if="variant === 'circle'" class="skeleton" :style="{ width, height, borderRadius: '50%' }" />
  <div v-else-if="variant === 'text'" class="skeleton text-lines">
    <span v-for="i in lines" :key="i" class="line" :class="{ short: i === lines && lines > 1 }" />
  </div>
  <div v-else class="skeleton" :style="{ width, height }" />
</template>

<style scoped>
.skeleton {
  display: inline-block;
  background: linear-gradient(90deg, var(--bg-raised) 25%, var(--bg-hover) 50%, var(--bg-raised) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.6s var(--ease-in-out) infinite;
  border-radius: var(--radius-sm);
}
.text-lines {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: none;
  animation: none;
}
.text-lines .line {
  height: 14px;
  border-radius: 4px;
  background: linear-gradient(
    90deg,
    var(--bg-raised) 25%,
    var(--bg-hover) 50%,
    var(--bg-raised) 75%
  );
  background-size: 200% 100%;
  animation: shimmer 1.6s var(--ease-in-out) infinite;
}
.text-lines .line.short {
  width: 60%;
}
@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>