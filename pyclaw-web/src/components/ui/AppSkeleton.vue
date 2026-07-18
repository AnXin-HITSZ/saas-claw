<template>
  <div class="app-skeleton" :class="`variant-${variant}`" :style="styleObj" aria-hidden="true">
    <template v-if="variant === 'text' && lines > 1">
      <div v-for="i in lines" :key="i" class="skeleton-line skeleton" :style="{ width: i === lines ? '60%' : '100%' }"></div>
    </template>
  </div>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  variant: { type: String, default: "rect", validator: v => ["text", "rect", "circle"].includes(v) },
  width: { type: [String, Number], default: "100%" },
  height: { type: [String, Number], default: 16 },
  lines: { type: Number, default: 1 },
});

function toCss(v) { return typeof v === "number" ? `${v}px` : v; }

const styleObj = computed(() => {
  if (props.variant === "circle") {
    const size = toCss(props.width === "100%" ? 40 : props.width);
    return { width: size, height: size, borderRadius: "50%" };
  }
  if (props.variant === "text" && props.lines > 1) return { width: toCss(props.width) };
  return { width: toCss(props.width), height: toCss(props.height) };
});
</script>

<style scoped>
.app-skeleton { display: block; }
.variant-rect, .variant-circle, .variant-text:not(.app-skeleton:has(.skeleton-line)) {
  background: linear-gradient(90deg, var(--bg-raised) 25%, var(--bg-hover) 50%, var(--bg-raised) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.6s var(--ease-in-out) infinite;
  border-radius: var(--radius-sm);
}
.variant-circle { border-radius: 50%; }
.skeleton-line { height: 14px; margin-bottom: 10px; }
.skeleton-line:last-child { margin-bottom: 0; }
@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}
</style>
