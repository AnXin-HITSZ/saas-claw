<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { toolApi, ApiError } from '@/api'
import type { Tool } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const list = ref<Tool[]>([])
const loading = ref(false)
const detail = ref<Tool | null>(null)
const showDetail = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await toolApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function open(t: Tool) {
  detail.value = t
  showDetail.value = true
}

function prettySchema(json: string | null): string {
  if (!json) return '（无 schema）'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">工具</div>
        <div class="page-sub">平台当前可被 Agent 调用的工具（由运行时同步，只读展示）。</div>
      </div>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无工具。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>敏感</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in list" :key="t.id">
            <td>{{ t.id }}</td>
            <td class="mono">{{ t.name }}</td>
            <td class="text-weak">{{ t.description || '—' }}</td>
            <td>
              <span v-if="t.is_sensitive" class="tag tag-warning">敏感</span>
              <span v-else class="tag">普通</span>
            </td>
            <td><button class="btn btn-sm" @click="open(t)">Schema</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showDetail" :title="detail?.name || '工具'">
      <div class="text-weak" style="margin-bottom: 8px">{{ detail?.description || '无描述' }}</div>
      <pre class="schema">{{ prettySchema(detail?.schema_json ?? null) }}</pre>
    </BaseModal>
  </div>
</template>

<style scoped>
.schema {
  background: #f5f6f8;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 12px;
  font-size: 12px;
  overflow: auto;
  max-height: 360px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
</style>
