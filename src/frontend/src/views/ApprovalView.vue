<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { approvalApi, ApiError } from '@/api'
import { APPROVAL_ACTION } from '@/types/api'
import type { ApprovalBatchVO, ApprovalRequestVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const tab = ref<'pending' | 'batch' | 'history'>('pending')
const pending = ref<ApprovalRequestVO[]>([])
const history = ref<ApprovalRequestVO[]>([])
const batchesPending = ref<ApprovalBatchVO[]>([])
const batchesHistory = ref<ApprovalBatchVO[]>([])
const loading = ref(false)

// 单条审批自定义消息弹窗
const showCustom = ref(false)
const customTarget = ref<ApprovalRequestVO | null>(null)
const customMsg = ref('')
// 批量审批自定义消息弹窗
const showBatchCustom = ref(false)
const batchCustomTarget = ref<ApprovalBatchVO | null>(null)
const batchCustomMsg = ref('')
// 逐项处理态：只锁定正在处理的那一行（其余行保持可操作），不全局禁用全部按钮。
const handlingKey = ref<string | null>(null)
const handlingAction = ref<number | null>(null)
function rowKey(kind: 'a' | 'b', id: number | string): string {
  return `${kind}:${id}`
}
function isHandling(kind: 'a' | 'b', id: number | string): boolean {
  return handlingKey.value === rowKey(kind, id)
}

async function load() {
  loading.value = true
  try {
    const [p, h, bp, bh] = await Promise.all([
      approvalApi.listPending(),
      approvalApi.listHistory(),
      approvalApi.listPendingBatches(),
      approvalApi.listBatchHistory(),
    ])
    pending.value = p
    history.value = h
    batchesPending.value = bp
    batchesHistory.value = bh
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function handleBatch(b: ApprovalBatchVO, action: number, message?: string) {
  if (handlingKey.value) return // 同一时刻只处理一项，避免刷新/回调串扰
  const k = rowKey('b', b.batch_id)
  handlingKey.value = k
  handlingAction.value = action
  try {
    await approvalApi.handleBatch(b.batch_id, { action, custom_message: message })
    toast.success('已处理')
    showBatchCustom.value = false
    batchCustomMsg.value = ''
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '处理失败')
  } finally {
    handlingKey.value = null
    handlingAction.value = null
  }
}

async function handle(a: ApprovalRequestVO, action: number, message?: string) {
  if (handlingKey.value) return // 同一时刻只处理一项
  const k = rowKey('a', a.approval_id)
  handlingKey.value = k
  handlingAction.value = action
  try {
    await approvalApi.handle(a.approval_id, { action, custom_message: message })
    toast.success('已处理')
    showCustom.value = false
    customMsg.value = ''
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '处理失败')
  } finally {
    handlingKey.value = null
    handlingAction.value = null
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

function openBatchCustom(b: ApprovalBatchVO) {
  batchCustomTarget.value = b
  batchCustomMsg.value = ''
  showBatchCustom.value = true
}
function submitBatchCustom() {
  if (!batchCustomTarget.value) return
  if (!batchCustomMsg.value.trim()) return toast.error('请输入自定义消息')
  handleBatch(batchCustomTarget.value, APPROVAL_ACTION.CUSTOM, batchCustomMsg.value.trim())
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
          <button class="tab-btn" :class="{ active: tab === 'batch' }" @click="tab = 'batch'">
            批量<template v-if="batchesPending.length"> ({{ batchesPending.length }})</template>
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
                  <AppButton
                    size="sm"
                    :loading="isHandling('a', a.approval_id) && handlingAction === APPROVAL_ACTION.ALLOW"
                    :disabled="isHandling('a', a.approval_id)"
                    @click="handle(a, APPROVAL_ACTION.ALLOW)"
                    >允许</AppButton
                  >
                  <AppButton
                    size="sm"
                    variant="danger"
                    :loading="isHandling('a', a.approval_id) && handlingAction === APPROVAL_ACTION.DENY"
                    :disabled="isHandling('a', a.approval_id)"
                    @click="handle(a, APPROVAL_ACTION.DENY)"
                    >拒绝</AppButton
                  >
                  <AppButton size="sm" variant="ghost" :disabled="isHandling('a', a.approval_id)" @click="openCustom(a)">自定义</AppButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 批量审批 -->
    <template v-else-if="tab === 'batch'">
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 2" :key="i" variant="rect" height="150px" />
      </div>

      <AppEmpty
        v-else-if="!batchesPending.length && !batchesHistory.length"
        icon="◈"
        title="暂无批量审批"
        description="当 Agent 并行派发多个子任务并触发敏感操作时，会聚合为一张批量审批卡。"
      />

      <template v-else>
        <!-- 待处理批量 -->
        <section v-if="batchesPending.length" class="batch-section">
          <div class="section-title">待处理</div>
          <div v-for="b in batchesPending" :key="b.batch_id" class="card batch-card">
            <div class="batch-head">
              <div>
                <div class="batch-agent">{{ b.agent_name || '#' + b.agent_id }}</div>
                <div class="text-weak mono">{{ b.request_id }}</div>
              </div>
              <span class="text-weak">{{ fmtTime(b.created_at) }}</span>
            </div>
            <div class="batch-subs">
              <div v-for="s in b.sub_requests" :key="s.request_id" class="batch-sub">
                <span class="mono">{{ s.tool_name || '#' + s.tool_id }}</span>
                <span class="text-weak">{{ s.input_summary }}</span>
              </div>
            </div>
            <div class="row" style="gap: 6px; margin-top: 12px">
              <AppButton
                size="sm"
                :loading="isHandling('b', b.batch_id) && handlingAction === APPROVAL_ACTION.ALLOW"
                :disabled="isHandling('b', b.batch_id)"
                @click="handleBatch(b, APPROVAL_ACTION.ALLOW)"
                >全部允许</AppButton
              >
              <AppButton
                size="sm"
                variant="danger"
                :loading="isHandling('b', b.batch_id) && handlingAction === APPROVAL_ACTION.DENY"
                :disabled="isHandling('b', b.batch_id)"
                @click="handleBatch(b, APPROVAL_ACTION.DENY)"
                >全部拒绝</AppButton
              >
              <AppButton size="sm" variant="ghost" :disabled="isHandling('b', b.batch_id)" @click="openBatchCustom(b)">自定义</AppButton>
            </div>
          </div>
        </section>

        <!-- 批量历史 -->
        <section v-if="batchesHistory.length" class="batch-section">
          <div class="section-title">历史</div>
          <div v-for="b in batchesHistory" :key="b.batch_id" class="card batch-card">
            <div class="batch-head">
              <div>
                <div class="batch-agent">{{ b.agent_name || '#' + b.agent_id }}</div>
                <div class="text-weak mono">{{ b.request_id }}</div>
              </div>
              <div class="row" style="gap: 8px">
                <AppTag :tone="statusText(b.status).tone">{{ statusText(b.status).text }}</AppTag>
                <span class="text-weak">{{ fmtTime(b.handled_at ?? '') }}</span>
              </div>
            </div>
            <div class="batch-subs">
              <div v-for="s in b.sub_requests" :key="s.request_id" class="batch-sub">
                <span class="mono">{{ s.tool_name || '#' + s.tool_id }}</span>
                <span class="text-weak">{{ s.input_summary }}</span>
              </div>
            </div>
            <div class="text-weak" style="margin-top: 10px">
              结果：{{ actionText(b.action) }}<template v-if="b.custom_message">（{{ b.custom_message }}）</template>
            </div>
          </div>
        </section>
      </template>
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
        <AppButton
          :loading="!!customTarget && isHandling('a', customTarget.approval_id)"
          @click="submitCustom"
          >提交</AppButton
        >
      </template>
    </AppModal>

    <!-- 批量自定义消息弹窗 -->
    <AppModal :show="showBatchCustom" title="批量审批自定义消息" width="480px" @close="showBatchCustom = false">
      <div class="form-item">
        <label>自定义消息（作为整体决策返回，覆盖所有子请求）</label>
        <textarea v-model="batchCustomMsg" class="textarea" placeholder="输入要返回的内容…" />
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showBatchCustom = false">取消</AppButton>
        <AppButton
          :loading="!!batchCustomTarget && isHandling('b', batchCustomTarget.batch_id)"
          @click="submitBatchCustom"
          >提交</AppButton
        >
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

<style scoped>
/* 批量审批卡（独立 style 块，避免改动上方 tab 样式） */
.batch-section {
  margin-bottom: 8px;
}
.batch-section .section-title {
  margin-bottom: 12px;
}
.batch-card {
  padding: 16px 18px;
  margin-bottom: 14px;
}
.batch-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.batch-agent {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 2px;
}
.batch-subs {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.batch-sub {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
}
.batch-sub .mono {
  flex-shrink: 0;
  color: var(--accent-2);
}
.batch-sub .text-weak {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>