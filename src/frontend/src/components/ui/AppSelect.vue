<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

export interface SelectOption<T = string | number | boolean> {
  value: T
  label: string
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue: string | number | boolean | null
    options: SelectOption[]
    placeholder?: string
    disabled?: boolean
    ariaLabel?: string
    /** 控制触发器宽度，如 '200px' */
    width?: string
  }>(),
  { placeholder: '请选择', disabled: false, ariaLabel: '', width: '' },
)
const emit = defineEmits<{ 'update:modelValue': [string | number | boolean | null]; change: [] }>()

const open = ref(false)
const activeIndex = ref(-1)
const triggerEl = ref<HTMLElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)
const panelStyle = ref<Record<string, string>>({})

const selected = computed(() => props.options.find((o) => o.value === props.modelValue))

function toggle() {
  if (props.disabled) return
  open.value ? close() : openDropdown()
}
function openDropdown() {
  open.value = true
  activeIndex.value = props.options.findIndex((o) => o.value === props.modelValue)
  positionPanel()
}
function close() {
  open.value = false
}
function pick(i: number) {
  const o = props.options[i]
  if (!o || o.disabled) return
  emit('update:modelValue', o.value)
  emit('change')
  close()
}
function onKeydown(e: KeyboardEvent) {
  if (!open.value) return
  if (e.key === 'Escape') close()
  else if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = (activeIndex.value + 1) % props.options.length
    scrollActive()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = (activeIndex.value - 1 + props.options.length) % props.options.length
    scrollActive()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    if (activeIndex.value >= 0) pick(activeIndex.value)
  }
}
function scrollActive() {
  const el = panelEl.value?.querySelector('[data-active="true"]') as HTMLElement | null
  el?.scrollIntoView({ block: 'nearest' })
}
function positionPanel() {
  const trigger = triggerEl.value
  if (!trigger) return
  const rect = trigger.getBoundingClientRect()
  const spaceBelow = window.innerHeight - rect.bottom
  const height = Math.min(240, props.options.length * 36 + 12)
  const pos = spaceBelow < height + 12 ? 'above' : 'below'
  panelStyle.value = pos === 'below'
    ? { top: `${rect.bottom + 6}px`, left: `${rect.left}px`, minWidth: `${rect.width}px` }
    : { bottom: `${window.innerHeight - rect.top + 6}px`, left: `${rect.left}px`, minWidth: `${rect.width}px` }
}

function onScroll() {
  if (open.value) positionPanel()
}

onMounted(() => {
  document.addEventListener('scroll', onScroll, true)
  document.addEventListener('resize', onScroll)
})
onBeforeUnmount(() => {
  document.removeEventListener('scroll', onScroll, true)
  document.removeEventListener('resize', onScroll)
})
watch(open, (v) => {
  if (v) document.addEventListener('keydown', onKeydown)
  else document.removeEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="app-select" :style="width ? { width } : undefined">
    <button
      ref="triggerEl"
      type="button"
      class="trigger"
      :class="{ open }"
      :disabled="disabled"
      :aria-label="ariaLabel || placeholder"
      @click="toggle"
    >
      <span class="value" :class="{ placeholder: !selected }">
        {{ selected?.label ?? placeholder }}
      </span>
      <span class="caret">▾</span>
    </button>

    <Teleport to="body">
      <Transition name="dropdown">
        <div
          v-if="open"
          ref="panelEl"
          class="dropdown"
          :style="panelStyle"
          role="listbox"
          @keydown.stop
        >
          <div
            v-for="(o, i) in options"
            :key="String(o.value)"
            class="option"
            :class="{ active: i === activeIndex, selected: o.value === modelValue, disabled: o.disabled }"
            :data-active="i === activeIndex"
            role="option"
            :aria-selected="o.value === modelValue"
            @mouseenter="activeIndex = i"
            @click="pick(i)"
          >
            <span class="opt-label">{{ o.label }}</span>
            <span v-if="o.value === modelValue" class="check">✓</span>
          </div>
          <div v-if="options.length === 0" class="no-options">无选项</div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.app-select {
  display: inline-block;
  position: relative;
}
.trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 140px;
  padding: 10px 14px;
  font-size: 14px;
  color: var(--text-primary);
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
  font-family: inherit;
}
.trigger:hover {
  border-color: var(--border-light);
}
.trigger.open {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-glow);
}
.trigger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.value.placeholder {
  color: var(--text-muted);
}
.caret {
  color: var(--text-muted);
  transition: transform 0.2s var(--ease-out), color 0.2s var(--ease-out);
  font-size: 12px;
}
.trigger.open .caret {
  transform: rotate(180deg);
  color: var(--accent);
}

.dropdown {
  position: fixed;
  z-index: 2000;
  padding: 6px;
  max-height: 240px;
  overflow-y: auto;
  background: rgba(16, 21, 31, 0.96);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  box-shadow: var(--shadow), 0 0 32px rgba(139, 124, 246, 0.15);
}
.option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--text-secondary);
  border-radius: 8px;
  cursor: pointer;
}
.option:hover,
.option.active {
  background: var(--accent-glow);
  color: var(--text-primary);
}
.option.selected {
  color: var(--accent);
}
.option.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.check {
  font-size: 12px;
}
.no-options {
  padding: 12px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.dropdown-enter-active {
  transition: opacity 0.16s var(--ease-out), transform 0.16s var(--ease-out);
}
.dropdown-enter-from {
  opacity: 0;
  transform: translateY(-6px);
}
.dropdown-leave-active {
  transition: opacity 0.12s ease;
}
.dropdown-leave-to {
  opacity: 0;
}
</style>