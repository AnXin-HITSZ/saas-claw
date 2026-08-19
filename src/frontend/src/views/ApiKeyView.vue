<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { authApi, ApiError } from '@/api'
import type { ApiKeyVO, CreateApiKeyVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const list = ref<ApiKeyVO[]>([])
const loading = ref(false)

const showCreate = ref(false)
const form = reactive({ name: '' })
const submitting = ref(false)

// 明文仅创建时返回一次，单独弹窗展示
const created = ref<CreateApiKeyVO | null>(null)
const showPlain = ref(false)

const revoking = ref<ApiKeyVO | null>(null)
const revokingLoading = ref(false)

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

async function confirmRevoke() {
  if (!revoking.value) return
  revokingLoading.value = true
  try {
    await authApi.revokeApiKey(revoking.value.id)
    toast.success('已吊销')
    revoking.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '吊销失败')
  } finally {
    revokingLoading.value = false
  }
}

async function copyPlain() {
  if (!created.value) return
  await navigator.clipboard.writeText(created.value.api_key)
  toast.success('已复制到剪贴板')
}

function fmtTime(s: string) {
  return s?.replace('T', ' ').slice(0, 16) ?? ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="API Key" subtitle="用于程序调用（Bearer sk-xxx）。明文仅在创建时展示一次，请妥善保存。">
      <template #actions>
        <AppButton @click="showCreate = true">创建 API Key</AppButton>
      </template>
    </PageHeader>

    <!-- 骨架 -->
    <div v-if="loading" class="grid">
      <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="140px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="⛁"
      title="还没有 API Key"
      description="创建后将以 sk- 开头的密钥接入网关，供程序调用 /v1 接口。"
    >
      <AppButton @click="showCreate = true">创建 API Key</AppButton>
    </AppEmpty>

    <!-- 表格 -->
    <div v-else class="card">
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>Key</th>
            <th>状态</th>
            <th>创建时间</th>
            <th style="width: 100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="k in list" :key="k.id">
            <td>{{ k.id }}</td>
            <td>{{ k.name }}</td>
            <td class="mono text-weak">sk-••••{{ k.key_suffix }}</td>
            <td>
              <AppTag v-if="k.status === 1" tone="success">启用</AppTag>
              <AppTag v-else tone="neutral">已吊销</AppTag>
            </td>
            <td class="text-weak">{{ fmtTime(k.created_at) }}</td>
            <td>
              <AppButton
                v-if="k.status === 1"
                variant="danger"
                size="sm"
                @click="revoking = k"
              >吊销</AppButton>
              <span v-else class="text-weak">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 创建弹窗 -->
    <AppModal :show="showCreate" title="创建 API Key" width="440px" @close="showCreate = false">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="用途备注，如 ci-bot" @keyup.enter="create" />
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showCreate = false">取消</AppButton>
        <AppButton :loading="submitting" @click="create">{{ submitting ? '' : '创建' }}</AppButton>
      </template>
    </AppModal>

    <!-- 明文弹窗 -->
    <AppModal :show="showPlain" title="API Key 已创建" width="520px" @close="showPlain = false">
      <div class="alert alert-info">请立即复制并保存，关闭后将无法再次查看明文。</div>
      <div class="plain-key mono">{{ created?.api_key }}</div>
      <template #actions>
        <AppButton @click="copyPlain">复制</AppButton>
        <AppButton variant="ghost" @click="showPlain = false">我已保存</AppButton>
      </template>
    </AppModal>

    <!-- 吊销确认 -->
    <AppConfirm
      :show="!!revoking"
      title="吊销 API Key"
      :message="`确认吊销 API Key「${revoking?.name}」？使用该 Key 的程序将立即失效。`"
      confirm-text="吊销"
      danger
      :loading="revokingLoading"
      @confirm="confirmRevoke"
      @cancel="revoking = null"
    />
  </div>
</template>

<style scoped>
.plain-key {
  margin-top: 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  word-break: break-all;
  font-size: 13px;
  color: var(--text-primary);
}
</style>