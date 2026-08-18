<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { authApi, ApiError } from '@/api'
import type { ApiKeyVO, CreateApiKeyVO } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const list = ref<ApiKeyVO[]>([])
const loading = ref(false)

const showCreate = ref(false)
const form = reactive({ name: '' })
const submitting = ref(false)

// 明文仅创建时返回一次，单独弹窗展示
const created = ref<CreateApiKeyVO | null>(null)
const showPlain = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await authApi.listApiKeys()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!form.name.trim()) {
    toast.error('请输入名称')
    return
  }
  submitting.value = true
  try {
    created.value = await authApi.createApiKey({ name: form.name.trim() })
    showCreate.value = false
    form.name = ''
    showPlain.value = true
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '创建失败')
  } finally {
    submitting.value = false
  }
}

async function revoke(k: ApiKeyVO) {
  if (!confirm(`确认吊销 API Key「${k.name}」？使用该 Key 的程序将立即失效。`)) return
  try {
    await authApi.revokeApiKey(k.id)
    toast.success('已吊销')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '吊销失败')
  }
}

async function copyPlain() {
  if (!created.value) return
  await navigator.clipboard.writeText(created.value.api_key)
  toast.success('已复制到剪贴板')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">API Key</div>
        <div class="page-sub">用于程序调用（Bearer sk-xxx）。明文仅在创建时展示一次，请妥善保存。</div>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">创建 API Key</button>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">还没有 API Key。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>Key</th>
            <th>状态</th>
            <th>创建时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="k in list" :key="k.id">
            <td>{{ k.id }}</td>
            <td>{{ k.name }}</td>
            <td class="mono text-weak">sk-••••{{ k.key_suffix }}</td>
            <td>
              <span v-if="k.status === 1" class="tag tag-success">启用</span>
              <span v-else class="tag tag-danger">已吊销</span>
            </td>
            <td class="text-weak">{{ k.created_at }}</td>
            <td>
              <button v-if="k.status === 1" class="btn btn-sm btn-danger" @click="revoke(k)">吊销</button>
              <span v-else class="text-weak">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showCreate" title="创建 API Key">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="用途备注，如 ci-bot" @keyup.enter="create" />
      </div>
      <template #footer>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="create">
          {{ submitting ? '创建中…' : '创建' }}
        </button>
      </template>
    </BaseModal>

    <BaseModal v-model="showPlain" title="API Key 已创建">
      <div class="alert alert-info">请立即复制并保存，关闭后将无法再次查看明文。</div>
      <div class="plain-key mono">{{ created?.api_key }}</div>
      <template #footer>
        <button class="btn btn-primary" @click="copyPlain">复制</button>
        <button class="btn" @click="showPlain = false">我已保存</button>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.plain-key {
  background: #f5f6f8;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 12px;
  word-break: break-all;
  font-size: 13px;
}
</style>
