<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { approvalApi, ApiError } from '@/api'
import { APPROVAL_ACTION } from '@/types/api'
import type { ApprovalRequestVO } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const tab = ref<'pending' | 'history'>('pending')
const pending = ref<ApprovalRequestVO[]>([])
const history = ref<ApprovalRequestVO[]>([])
const loading = ref(false)

// 自定义消息弹窗
const showCustom = ref(false)
const customTarget = ref<ApprovalRequestVO | null>(null)
const customMsg = ref('')
const handling = ref(false)

async function load() {
  loading.value = true
  try {
    const [p, h] = await Promise.all([approvalApi.listPending(), approvalApi.listHistory()])
    pending.value = p
    history.value = h
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function handle(a: ApprovalRequestVO, action: number, message?: string) {
  handling.value = true
  try {
    await approvalApi.handle(a.approval_id, { action, custom_message: message })
    toast.success('已处理')
    showCustom.value = false
    customMsg.value = ''
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '处理失败')
  } finally {
    handling.value = false
  }
}

function openCustom(a: ApprovalRequestVO) {
  customTarget.value = a
  customMsg.value = ''
  showCustom.value = true
}
function submitCustom() {
  if (!customTarget.value) return
  if (!customMsg.value.trim()) return toast.error('请输入自定义消息')
  handle(customTarget.value, APPROVAL_ACTION.CUSTOM, customMsg.value.trim())
}

function statusText(status: number) {
  // 约定：0=待处理 1=已处理（具体以后端为准，这里做展示兜底）
  return status === 0 ? { cls: 'tag-warning', text: '待处理' } : { cls: 'tag-success', text: '已处理' }
}
function actionText(action: number | null) {
  if (action === APPROVAL_ACTION.ALLOW) return '允许'
  if (action === APPROVAL_ACTION.DENY) return '拒绝'
  if (action === APPROVAL_ACTION.CUSTOM) return '自定义'
  return '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">工具审批</div>
        <div class="page-sub">Agent 调用敏感工具时会请求人工审批，你可允许、拒绝或返回自定义消息。</div>
      </div>
      <div class="row">
        <button class="btn" :class="{ 'btn-primary': tab === 'pending' }" @click="tab = 'pending'">
          待处理<span v-if="pending.length"> ({{ pending.length }})</span>
        </button>
        <button class="btn" :class="{ 'btn-primary': tab === 'history' }" @click="tab = 'history'">历史</button>
      </div>
    </div>

    <!-- 待处理 -->
    <div v-if="tab === 'pending'" class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!pending.length" class="empty">没有待处理的审批。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>Agent</th>
            <th>工具</th>
            <th>输入摘要</th>
            <th>时间</th>
            <th style="width: 240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in pending" :key="a.approval_id">
            <td>{{ a.agent_name || '#' + a.agent_id }}</td>
            <td class="mono">{{ a.tool_name || '#' + a.tool_id }}</td>
            <td class="text-weak">{{ a.input_summary }}</td>
            <td class="text-weak">{{ a.created_at }}</td>
            <td>
              <div class="row" style="gap: 6px">
                <button
                  class="btn btn-sm btn-primary"
                  :disabled="handling"
                  @click="handle(a, APPROVAL_ACTION.ALLOW)"
                >
                  允许
                </button>
                <button
                  class="btn btn-sm btn-danger"
                  :disabled="handling"
                  @click="handle(a, APPROVAL_ACTION.DENY)"
                >
                  拒绝
                </button>
                <button class="btn btn-sm" :disabled="handling" @click="openCustom(a)">自定义</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 历史 -->
    <div v-else class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!history.length" class="empty">暂无历史记录。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>Agent</th>
            <th>工具</th>
            <th>输入摘要</th>
            <th>结果</th>
            <th>状态</th>
            <th>处理时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in history" :key="a.approval_id">
            <td>{{ a.agent_name || '#' + a.agent_id }}</td>
            <td class="mono">{{ a.tool_name || '#' + a.tool_id }}</td>
            <td class="text-weak">{{ a.input_summary }}</td>
            <td>
              {{ actionText(a.action) }}
              <span v-if="a.custom_message" class="text-weak">（{{ a.custom_message }}）</span>
            </td>
            <td><span class="tag" :class="statusText(a.status).cls">{{ statusText(a.status).text }}</span></td>
            <td class="text-weak">{{ a.handled_at || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showCustom" title="返回自定义消息">
      <div class="form-item">
        <label>自定义消息（将作为工具结果返回给 Agent）</label>
        <textarea v-model="customMsg" class="textarea" placeholder="输入要返回的内容…" />
      </div>
      <template #footer>
        <button class="btn" @click="showCustom = false">取消</button>
        <button class="btn btn-primary" :disabled="handling" @click="submitCustom">提交</button>
      </template>
    </BaseModal>
  </div>
</template>
