<script setup lang="ts">
import AppModal from './AppModal.vue'
import AppButton from './AppButton.vue'

defineProps<{
  show: boolean
  title?: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  loading?: boolean
}>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <AppModal :show="show" :title="title ?? '确认操作'" @close="emit('cancel')">
    <p class="confirm-message">{{ message }}</p>
    <template #actions>
      <AppButton variant="ghost" @click="emit('cancel')">{{ cancelText ?? '取消' }}</AppButton>
      <AppButton :variant="danger ? 'danger' : 'primary'" :loading="loading" @click="emit('confirm')">
        {{ confirmText ?? '确认' }}
      </AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.confirm-message {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-secondary);
  word-break: break-word;
}
</style>