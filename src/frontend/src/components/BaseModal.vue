<script setup lang="ts">
/** 通用弹窗骨架：标题 + 内容插槽 + 底部操作插槽。v-model 控制显隐。 */
defineProps<{ modelValue: boolean; title: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="modal-mask" @click.self="close">
      <div class="modal">
        <div class="modal-header">{{ title }}</div>
        <div class="modal-body">
          <slot />
        </div>
        <div class="modal-footer">
          <slot name="footer">
            <button class="btn" @click="close">关闭</button>
          </slot>
        </div>
      </div>
    </div>
  </Teleport>
</template>
