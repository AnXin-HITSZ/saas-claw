<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { approvalApi, ApiError } from '@/api'
import { APPROVAL_ACTION } from '@/types/api'
import type { ApprovalRequestVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

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
  return status === 0
    ? { tone: 'warning' as const, text: '待处理' }
    : { tone: 'success' as const, text: '已处理' }
}
function actionText(action: number | null) {
  if (action === APPROVAL_ACTION.ALLOW) return '允许'
  if (action === APPROVAL_ACTION.DENY) return '拒绝'
  if (action === APPROVAL_ACTION.CUSTOM) return '自定义'
  return '—'
}

function fmtTime(s: string) {
  return s?.replace('T', ' ').slice(0, 16) ?? '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="工具审批" subtitle="Agent 调用敏感工具时会请求人工审批，你可允许、拒绝或返回自定义消息。">
      <template #actions>
        <div class="tab-switch">
          <button class="tab-btn" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">
            待处理<template v-if="pending.length"> ({{ pending.length }})</template>
          </button>
          <button class="tab-btn" :class="{ active: tab === 'history' }" @click="tab = 'history'">历史</button>
        </div>
      </template>
    </PageHeader>

    <!-- 待处理 -->
    <template v-if="tab === 'pending'">
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="130px" />
      </div>

      <AppEmpty
        v-else-if="!pending.length"
        icon="◎"
        title="没有待处理的审批"
        description="当 Agent 请求调用敏感工具时，审批请求会出现在这里。"
      />

      <div v-else class="card">
        <table class="data-table">
          <thead>
            <tr>
              <th>Agent</th>
              <th>工具</th>
              <th>输入摘要</th>
              <th>Claw</th>
              <th>时间</th>
              <th style="width: 220px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in pending" :key="a.approval_id">
              <td>{{ a.agent_name || '#' + a.agent_id }}</td>
              <td>
                <span class="mono">{{ a.tool_name || '#' + a.tool_id }}</span>
              </td>
              <td class="text-weak">{{ a.input_summary }}</td>
              <td class="text-weak">#{{ a.claw_id }}</td>
              <td class="text-weak">{{ fmtTime(a.created_at) }}</td>
              <td>
                <div class="row" style="gap: 6px">
                  <AppButton size="sm" :disabled="handling" @click="handle(a, APPROVAL_ACTION.ALLOW)">允许</AppButton>
                  <AppButton size="sm" variant="danger" :disabled="handling" @click="handle(a, APPROVAL_ACTION.DENY)">拒绝</AppButton>
                  <AppButton size="sm" variant="ghost" :disabled="handling" @click="openCustom(a)">自定义</AppButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 历史 -->
    <template v-else>
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="130px" />
      </div>

      <AppEmpty
        v-else-if="!history.length"
        icon="◎"
        title="暂无历史记录"
        description="已处理的审批将记录在这里。"
      />

      <div v-else class="card">
        <table class="data-table">
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
              <td>
                <span class="mono">{{ a.tool_name || '#' + a.tool_id }}</span>
              </td>
              <td class="text-weak">{{ a.input_summary }}</td>
              <td>
                {{ actionText(a.action) }}
                <span v-if="a.custom_message" class="text-weak">（{{ a.custom_message }}）</span>
              </td>
              <td>
                <AppTag :tone="statusText(a.status).tone">{{ statusText(a.status).text }}</AppTag>
              </td>
              <td class="text-weak">{{ fmtTime(a.handled_at ?? '') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 自定义消息弹窗 -->
    <AppModal :show="showCustom" title="返回自定义消息" width="480px" @close="showCustom = false">
      <div class="form-item">
        <label>自定义消息（将作为工具结果返回给 Agent）</label>
        <textarea v-model="customMsg" class="textarea" placeholder="输入要返回的内容…" />
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showCustom = false">取消</AppButton>
        <AppButton :loading="handling" @click="submitCustom">{{ handling ? '' : '提交' }}</AppButton>
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
.tab-switch {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 12px;
}
.tab-btn {
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  transition: color 0.2s var(--ease-out), background 0.2s var(--ease-out);
}
.tab-btn:hover {
  color: var(--text-primary);
}
.tab-btn.active {
  color: var(--accent);
  background: var(--accent-glow);
}
</style>