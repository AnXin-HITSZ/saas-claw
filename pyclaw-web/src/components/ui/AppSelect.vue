<template>
  <div class="app-select" :class="{ disabled, open }">
    <button
      ref="triggerRef"
      type="button"
      class="select-trigger"
      :disabled="disabled"
      :required="required"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-label="ariaLabel || undefined"
      @click="toggle"
      @keydown="onTriggerKey"
    >
      <span class="select-value" :class="{ placeholder: !hasSelection }">{{ displayLabel }}</span>
      <span class="select-chevron" aria-hidden="true">▾</span>
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        ref="panelRef"
        class="select-panel"
        :style="panelStyle"
        role="listbox"
        :aria-label="ariaLabel || undefined"
        @keydown="onPanelKey"
      >
        <div
          v-for="(opt, i) in options"
          :key="String(opt.value)"
          class="select-option"
          :class="{ selected: isSelected(opt), active: i === activeIndex, disabled: opt.disabled }"
          role="option"
          :aria-selected="isSelected(opt)"
          :aria-disabled="opt.disabled || undefined"
          @click="choose(opt)"
          @mouseenter="activeIndex = i"
        >
          <span class="option-label">{{ opt.label }}</span>
          <span v-if="isSelected(opt)" class="option-check" aria-hidden="true">✓</span>
        </div>
        <div v-if="!options.length" class="select-empty">无选项</div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";

const props = defineProps({
  modelValue: { type: [String, Number, Boolean, Object], default: "" },
  options: { type: Array, default: () => [] }, // [{ value, label, disabled? }]
  placeholder: { type: String, default: "请选择" },
  disabled: { type: Boolean, default: false },
  required: { type: Boolean, default: false },
  ariaLabel: { type: String, default: "" },
});
const emit = defineEmits(["update:modelValue", "change"]);

const open = ref(false);
const activeIndex = ref(0);
const triggerRef = ref(null);
const panelRef = ref(null);
const panelStyle = ref({});

const hasSelection = computed(() => {
  return props.options.some(o => o.value === props.modelValue) && props.modelValue !== "" && props.modelValue !== null && props.modelValue !== undefined;
});
const displayLabel = computed(() => {
  const sel = props.options.find(o => o.value === props.modelValue);
  return sel ? sel.label : (hasSelection.value ? "" : props.placeholder);
});

function isSelected(opt) { return opt.value === props.modelValue; }

function computePosition() {
  if (!triggerRef.value) return;
  const r = triggerRef.value.getBoundingClientRect();
  const panelWidth = r.width;
  const estimatedHeight = Math.min(props.options.length * 38 + 8, 280);
  let top = r.bottom + 6;
  if (top + estimatedHeight > window.innerHeight) top = r.top - estimatedHeight - 6;
  panelStyle.value = {
    left: `${r.left}px`,
    top: `${top}px`,
    width: `${panelWidth}px`,
  };
}

function openPanel() {
  if (props.disabled) return;
  open.value = true;
  const idx = props.options.findIndex(o => o.value === props.modelValue);
  activeIndex.value = idx >= 0 ? idx : 0;
  nextTick(() => {
    computePosition();
    window.addEventListener("scroll", computePosition, true);
    window.addEventListener("resize", computePosition);
    document.addEventListener("mousedown", onDocMouseDown);
  });
}
function closePanel() {
  if (!open.value) return;
  open.value = false;
  window.removeEventListener("scroll", computePosition, true);
  window.removeEventListener("resize", computePosition);
  document.removeEventListener("mousedown", onDocMouseDown);
}
function toggle() { open.value ? closePanel() : openPanel(); }

function choose(opt) {
  if (opt.disabled) return;
  emit("update:modelValue", opt.value);
  emit("change", opt.value);
  closePanel();
  triggerRef.value?.focus();
}

function onTriggerKey(e) {
  if (e.key === "ArrowDown" || e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    openPanel();
  }
}
function onPanelKey(e) {
  if (e.key === "ArrowDown") { e.preventDefault(); activeIndex.value = Math.min(activeIndex.value + 1, props.options.length - 1); }
  else if (e.key === "ArrowUp") { e.preventDefault(); activeIndex.value = Math.max(activeIndex.value - 1, 0); }
  else if (e.key === "Enter") { e.preventDefault(); const o = props.options[activeIndex.value]; if (o) choose(o); }
  else if (e.key === "Escape") { e.preventDefault(); closePanel(); triggerRef.value?.focus(); }
}
function onDocMouseDown(e) {
  if (panelRef.value && !panelRef.value.contains(e.target) && triggerRef.value && !triggerRef.value.contains(e.target)) {
    closePanel();
  }
}

watch(() => props.options, () => { if (open.value) computePosition(); });
onBeforeUnmount(closePanel);
</script>

<style scoped>
.app-select { position: relative; display: block; }
.select-trigger {
  width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 10px 14px; background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: 10px; color: var(--text-primary); font-size: 14px; font-family: inherit;
  text-align: left; cursor: pointer;
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
}
.select-trigger:hover:not(:disabled) { border-color: var(--border-light); }
.select-trigger:focus-visible { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
.select-trigger:disabled { opacity: 0.45; cursor: not-allowed; }
.app-select.open .select-trigger { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
.select-value { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.select-value.placeholder { color: var(--text-muted); }
.select-chevron { color: var(--text-muted); font-size: 15px; line-height: 1; transition: transform 0.2s var(--ease-out), color 0.2s var(--ease-out); flex: 0 0 auto; }
.app-select.open .select-chevron { transform: rotate(180deg); color: var(--accent); }
</style>

<style>
/* 全局（Teleport 到 body，需非 scoped） */
.select-panel {
  position: fixed; z-index: 1100; max-height: 280px; overflow-y: auto;
  background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: 12px;
  box-shadow: var(--shadow), 0 0 24px rgba(139, 124, 246, 0.08);
  padding: 6px; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  animation: select-panel-in 0.16s var(--ease-out);
}
@keyframes select-panel-in {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}
.select-option {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 9px 12px; border-radius: 8px; font-size: 13px; color: var(--text-secondary);
  cursor: pointer; transition: background 0.14s var(--ease-out), color 0.14s var(--ease-out);
}
.select-option:hover:not(.disabled), .select-option.active:not(.disabled) {
  background: var(--accent-glow); color: var(--text-primary);
}
.select-option.selected { color: var(--accent); font-weight: 600; }
.select-option.disabled { opacity: 0.4; cursor: not-allowed; }
.option-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.option-check { color: var(--accent); font-size: 12px; flex: 0 0 auto; }
.select-empty { padding: 14px; text-align: center; color: var(--text-muted); font-size: 13px; }
</style>
