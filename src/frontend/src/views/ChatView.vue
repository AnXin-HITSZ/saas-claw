<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { agentApi, chatApi, ApiError } from '@/api'
import type { Agent, ConversationMeta, TraceEvent, TraceItem } from '@/types/api'
import { useToast } from '@/composables/useToast'
import AppButton from '@/components/ui/AppButton.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSelect, { type SelectOption } from '@/components/ui/AppSelect.vue'
import renderMarkdown from '@/utils/markdown'

const toast = useToast()

const agents = ref<Agent[]>([])
const selectedAlias = ref<string>('')

const conversations = ref<ConversationMeta[]>([])
const currentConv = ref<string>('')

// 统一时间轴：消息 + 过程事件（重开会话时用 /trace 的 items 初始化）
type TimelineEntry =
  | { kind: 'message'; role: 'user' | 'assistant' | 'system' | 'tool'; content: string }
  | { kind: 'event'; event: TraceEvent }

const timeline = ref<TimelineEntry[]>([])
const liveEvents = ref<TraceEvent[]>([]) // 本轮实时过程事件
const liveUser = ref('') // 本轮用户消息
const liveAssistant = ref('') // 本轮 assistant 流式内容
const input = ref('')
const streaming = ref(false)
const abortCtrl = ref<AbortController | null>(null)
// 会话/流代次：openConversation/newConversation 时自增，作废旧会话在途流的回调
// （否则切换后旧流 chunk 仍写入新会话时间轴）
let streamGen = 0
const bodyRef = ref<HTMLElement | null>(null)

const agentOptions = computed<SelectOption[]>(() =>
  agents.value.map((a) => ({ value: a.alias, label: `${a.name}（${a.alias}）` })),
)

const canSend = computed(
  () => !!selectedAlias.value && !!input.value.trim() && !streaming.value,
)

const md = (t: string) => renderMarkdown(t)

// langchain 角色 → 前端展示角色
function normRole(role: string): 'user' | 'assistant' | 'system' | 'tool' {
  if (role === 'human') return 'user'
  if (role === 'ai') return 'assistant'
  if (role === 'system' || role === 'tool') return role
  return role as 'user' | 'assistant'
}

function uuid(): string {
  return crypto.randomUUID()
}

async function scrollBottom() {
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
}

async function loadAgents() {
  try {
    agents.value = await agentApi.list()
    if (agents.value.length) selectedAlias.value = agents.value[0].alias
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载 Agent 失败')
  }
}

async function loadConversations() {
  try {
    const res = await chatApi.listConversations()
    // 过滤缺 conversation_id 的脏条目（runtime 占位 `{}` 未回填时会出现），
    // 否则模板对 undefined 调 .slice 会抛 TypeError 把整页渲染搞白
    conversations.value = (res.list || []).filter((c) => !!c.conversation_id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载会话失败')
  }
}

// 把 /trace 的 items（消息+事件混合）转成统一时间轴，过滤内部轮次边界事件
function itemsToTimeline(items: TraceItem[]): TimelineEntry[] {
  return items
    .filter((it) => it.kind !== 'event' || (it.type !== 'chat_start' && it.type !== 'chat_end'))
    .map((it) =>
      it.kind === 'message'
        ? { kind: 'message' as const, role: normRole(it.role), content: it.content }
        : { kind: 'event' as const, event: it },
    )
}

async function openConversation(id: string) {
  // 切会话：中断在途流 + 作废旧流回调，避免旧会话增量串入新会话时间轴
  abortCtrl.value?.abort()
  abortCtrl.value = null
  streamGen++
  streaming.value = false
  currentConv.value = id
  timeline.value = []
  liveEvents.value = []
  liveUser.value = ''
  liveAssistant.value = ''
  try {
    // 单接口时间轴：/trace 的 items 已合并消息与过程事件
    const res = await chatApi.getTrace(id)
    timeline.value = itemsToTimeline(res.items || [])
    await scrollBottom()
  } catch (e) {
    // 兜底：trace 失败退回 messages
    try {
      const res = await chatApi.getMessages(id)
      timeline.value = res.messages.map((m) => ({
        kind: 'message' as const,
        role: normRole(m.role),
        content: m.content,
      }))
    } catch (e2) {
      toast.error(e2 instanceof ApiError ? e2.message : '加载会话失败')
    }
    await scrollBottom()
  }
}

function newConversation() {
  // 新对话：同样中断在途流 + 作废旧流回调（与 openConversation 一致）
  abortCtrl.value?.abort()
  abortCtrl.value = null
  streamGen++
  streaming.value = false
  currentConv.value = uuid()
  timeline.value = []
  liveEvents.value = []
  liveUser.value = ''
  liveAssistant.value = ''
  input.value = ''
}

// 过程事件卡元信息：按 type/data 派生图标/标题/摘要/色调
function eventMeta(ev: TraceEvent): {
  icon: string
  title: string
  subtitle: string
  tone: 'info' | 'success' | 'warning'
} {
  const d = (ev.data || {}) as Record<string, any>
  switch (ev.type) {
    case 'tool_start':
      return { icon: '⚙', title: `工具 · ${d.tool ?? ''}`, subtitle: d.args_summary ?? '', tone: 'info' }
    case 'tool_end':
      return { icon: '⚙', title: `工具 · ${d.tool ?? ''} 完成`, subtitle: d.result_summary ?? '', tone: 'success' }
    case 'subagent_start':
      return {
        icon: '⟳',
        title: `子任务 · ${d.name ?? (d.agent_id ? '#' + d.agent_id : '')}`,
        subtitle: d.task ?? '',
        tone: 'info',
      }
    case 'subagent_end':
      return { icon: '⟳', title: '子任务完成', subtitle: d.status === 'done' ? '已完成' : '', tone: 'success' }
    case 'approval_pending':
      return {
        icon: '⏸',
        title: '审批挂起',
        subtitle: d.tools?.length ? d.tools.join('、') : d.tool ?? d.request_id ?? '',
        tone: 'warning',
      }
    case 'approval_resolved':
      return { icon: '✓', title: `审批完成（${d.decision ?? ''}）`, subtitle: d.reason ?? '', tone: 'success' }
    default:
      return { icon: '•', title: ev.type, subtitle: '', tone: 'info' }
  }
}

// 把本轮实时区（用户消息 + 过程事件 + assistant 增量）合并进永久时间轴并清空
function commitLive() {
  if (liveUser.value) timeline.value.push({ kind: 'message', role: 'user', content: liveUser.value })
  for (const ev of liveEvents.value) timeline.value.push({ kind: 'event', event: ev })
  if (liveAssistant.value) {
    timeline.value.push({ kind: 'message', role: 'assistant', content: liveAssistant.value })
  }
  liveUser.value = ''
  liveEvents.value = []
  liveAssistant.value = ''
}

async function send() {
  if (!canSend.value) return
  if (!currentConv.value) currentConv.value = uuid()

  const userText = input.value.trim()
  input.value = ''
  liveUser.value = userText
  liveAssistant.value = ''
  liveEvents.value = []
  await scrollBottom()

  streaming.value = true
  abortCtrl.value = new AbortController()
  const gen = ++streamGen // 本流的代次：切会话/新对话后作废旧流回调

  await chatApi.streamChat(
    {
      model: selectedAlias.value,
      alias: selectedAlias.value,
      messages: [{ role: 'user', content: userText }],
      conversation_id: currentConv.value,
    },
    {
      signal: abortCtrl.value.signal,
      onMessage: (data) => {
        if (gen !== streamGen) return // 已切会话：丢弃旧流增量
        let obj: unknown
        try {
          obj = JSON.parse(data)
        } catch {
          // 非 JSON 帧，直接当文本增量兜底
          liveAssistant.value += data
          scrollBottom()
          return
        }
        const o = obj as {
          choices?: { delta?: { content?: string } }[]
          type?: string
          event?: TraceEvent
          payload?: { request_id?: string }
          error?: string
        }
        if (o.type === 'trace_event' && o.event) {
          // 实时过程帧：与 trace 落盘事件同构，过滤轮次边界后入时间轴
          if (o.event.type !== 'chat_start' && o.event.type !== 'chat_end') {
            liveEvents.value.push(o.event)
            scrollBottom()
          }
          return
        }
        if (o.type === '__interrupt__') {
          const rid = o.payload?.request_id
          liveAssistant.value += `\n\n⏸️ 触发工具审批（request_id=${rid ?? '?'}），请到「工具审批」处理后刷新本会话查看结果。`
          toast.info('该操作需要人工审批')
          scrollBottom()
          return
        }
        if (o.type === 'error') {
          // runtime 图 run 中途异常（模型配置缺失/供应商报错等）：显式展示，不静默吞掉
          liveAssistant.value += `\n\n[出错] ${o.error ?? '未知错误'}`
          toast.error('对话出错')
          scrollBottom()
          return
        }
        const delta = o.choices?.[0]?.delta?.content
        if (delta) {
          liveAssistant.value += delta
          scrollBottom()
        }
      },
      onError: (err) => {
        if (gen !== streamGen) return // 已切会话：作废旧流（含 abort 后的 AbortError）
        liveAssistant.value += `\n\n[出错] ${err instanceof Error ? err.message : String(err)}`
        commitLive()
        streaming.value = false
        abortCtrl.value = null
        toast.error('对话出错')
        scrollBottom()
      },
      onDone: async () => {
        if (gen !== streamGen) return // 已切会话：旧流结束不再提交/刷新
        commitLive()
        streaming.value = false
        abortCtrl.value = null
        await loadConversations()
        await scrollBottom()
      },
    },
  )
}

function stop() {
  abortCtrl.value?.abort()
  abortCtrl.value = null
  commitLive() // 停止时把已产生的部分内容并入时间轴，避免丢失
  streaming.value = false
}

onMounted(async () => {
  await Promise.all([loadAgents(), loadConversations()])
  newConversation()
})
</script>

<template>
  <div class="chat">
    <!-- 会话侧栏 -->
    <aside class="conv-list">
      <div class="conv-header">
        <AppButton size="sm" @click="newConversation">＋ 新对话</AppButton>
      </div>
      <div class="conv-items">
        <div
          v-for="c in conversations"
          :key="c.conversation_id"
          class="conv-item"
          :class="{ active: c.conversation_id === currentConv }"
          @click="openConversation(c.conversation_id)"
        >
          <div class="conv-summary">{{ c.last_summary || '（空会话）' }}</div>
          <div class="conv-id mono">{{ (c.conversation_id || '').slice(0, 8) }}</div>
        </div>
        <AppEmpty v-if="!conversations.length" icon="◈" title="暂无历史会话" description="开始第一段对话吧。" />
      </div>
    </aside>

    <!-- 主对话区 -->
    <main class="chat-main">
      <div class="chat-toolbar">
        <span class="text-weak">Agent</span>
        <AppSelect v-model="selectedAlias" :options="agentOptions" width="240px" placeholder="选择 Agent" />
        <span v-if="!agents.length" class="text-weak">请先创建 Agent</span>
        <div class="spacer" />
        <span class="text-weak mono conv-tag">#{{ currentConv.slice(0, 8) }}</span>
      </div>

      <div ref="bodyRef" class="chat-body">
        <div v-if="!timeline.length && !liveUser && !streaming" class="chat-welcome">
          <div class="welcome-logo">◢</div>
          <div class="welcome-title">与你的 Agent 对话</div>
          <div class="welcome-sub">选择一个 Agent，输入消息开始。支持 Markdown 渲染、流式输出与过程时间轴。</div>
        </div>

        <!-- 统一时间轴：消息气泡 + 过程事件卡 -->
        <template v-for="(entry, i) in timeline" :key="'t' + i">
          <div v-if="entry.kind === 'message'" class="msg" :class="`msg-${entry.role}`">
            <div class="msg-role">
              {{ entry.role === 'user' ? '我' : entry.role === 'assistant' ? 'Agent' : entry.role }}
            </div>
            <div v-if="entry.role === 'assistant'" class="msg-content md" v-html="md(entry.content)"></div>
            <div v-else class="msg-content">{{ entry.content }}</div>
          </div>
          <div v-else class="proc-card" :class="`proc-${eventMeta(entry.event).tone}`">
            <div class="proc-icon">{{ eventMeta(entry.event).icon }}</div>
            <div class="proc-body">
              <div class="proc-title">{{ eventMeta(entry.event).title }}</div>
              <div v-if="eventMeta(entry.event).subtitle" class="proc-sub">{{ eventMeta(entry.event).subtitle }}</div>
            </div>
          </div>
        </template>

        <!-- 本轮实时区：用户消息 + 过程事件 + 流式 assistant -->
        <template v-if="liveUser || liveEvents.length || liveAssistant || streaming">
          <div class="msg msg-user">
            <div class="msg-role">我</div>
            <div class="msg-content">{{ liveUser }}</div>
          </div>
          <div v-for="(ev, i) in liveEvents" :key="'le' + i" class="proc-card" :class="`proc-${eventMeta(ev).tone}`">
            <div class="proc-icon">{{ eventMeta(ev).icon }}</div>
            <div class="proc-body">
              <div class="proc-title">{{ eventMeta(ev).title }}</div>
              <div v-if="eventMeta(ev).subtitle" class="proc-sub">{{ eventMeta(ev).subtitle }}</div>
            </div>
          </div>
          <div v-if="liveAssistant || streaming" class="msg msg-assistant">
            <div class="msg-role">Agent</div>
            <div class="msg-content md" v-html="md(liveAssistant + (streaming ? ' ▍' : ''))"></div>
          </div>
        </template>
      </div>

      <div class="chat-input">
        <textarea
          v-model="input"
          class="textarea"
          rows="2"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <AppButton v-if="!streaming" :disabled="!canSend" @click="send">发送</AppButton>
        <AppButton v-else variant="danger" @click="stop">停止</AppButton>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  height: calc(100vh - 34px - 34px - 72px);
  min-height: 480px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--bg-deep);
}

/* ---------- 会话侧栏 ---------- */
.conv-list {
  width: 260px;
  border-right: 1px solid var(--border);
  background: rgba(10, 14, 22, 0.6);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.conv-header {
  padding: 14px;
  border-bottom: 1px solid var(--border);
}
.conv-items {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.conv-item {
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s var(--ease-out);
  margin-bottom: 4px;
}
.conv-item:hover {
  background: var(--bg-raised);
}
.conv-item.active {
  background: linear-gradient(90deg, var(--accent-glow), transparent 90%);
  border-left: 2px solid var(--accent);
}
.conv-summary {
  font-size: 13px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-item.active .conv-summary {
  color: var(--text-primary);
}
.conv-id {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 3px;
}

/* ---------- 主区 ---------- */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  background: rgba(16, 21, 31, 0.6);
}
.conv-tag {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--bg-raised);
  border: 1px solid var(--border);
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px;
}

/* 欢迎态 */
.chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 6px;
  color: var(--text-muted);
}
.welcome-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--gradient-aurora);
  box-shadow: var(--glow-accent);
  color: #0a0e14;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
}
.welcome-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.welcome-sub {
  font-size: 13px;
}

/* 消息气泡 */
.msg {
  margin-bottom: 20px;
  max-width: 860px;
}
.msg-role {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
  padding: 12px 16px;
  border-radius: var(--radius-sm);
  background: var(--bg-raised);
  border: 1px solid var(--border);
  line-height: 1.65;
  font-size: 14px;
  color: var(--text-primary);
}
.msg-user {
  margin-left: auto;
}
.msg-user .msg-role {
  text-align: right;
}
.msg-user .msg-content {
  background: linear-gradient(135deg, rgba(245, 168, 61, 0.16), rgba(224, 99, 124, 0.1));
  border-color: rgba(245, 168, 61, 0.35);
  border-radius: var(--radius-sm) var(--radius-sm) 4px var(--radius-sm);
}
.msg-tool .msg-content {
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: var(--bg-deep);
  color: var(--text-secondary);
}

/* 过程事件卡（工具/子任务/审批） */
.proc-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 860px;
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  background: rgba(10, 14, 22, 0.5);
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-muted);
}
.proc-icon {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  border-radius: 6px;
  background: var(--bg-raised);
  margin-top: 1px;
}
.proc-body {
  min-width: 0;
}
.proc-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}
.proc-sub {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.proc-info {
  border-left-color: var(--info);
}
.proc-info .proc-icon {
  color: var(--info);
}
.proc-success {
  border-left-color: var(--success);
}
.proc-success .proc-icon {
  color: var(--success);
}
.proc-warning {
  border-left-color: var(--warning);
}
.proc-warning .proc-icon {
  color: var(--warning);
}

/* Markdown 样式 */
.md :deep(p) {
  margin: 0 0 10px;
}
.md :deep(p:last-child) {
  margin-bottom: 0;
}
.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4) {
  margin: 14px 0 8px;
  color: var(--text-primary);
  line-height: 1.3;
}
.md :deep(h1) {
  font-size: 18px;
}
.md :deep(h2) {
  font-size: 16px;
}
.md :deep(h3) {
  font-size: 15px;
}
.md :deep(h4) {
  font-size: 14px;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 0 0 10px;
  padding-left: 22px;
}
.md :deep(li) {
  margin: 3px 0;
}
.md :deep(pre) {
  background: var(--bg-abyss);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  overflow-x: auto;
  margin: 10px 0;
}
.md :deep(pre code) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--text-secondary);
  background: none;
  padding: 0;
}
.md :deep(code) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: rgba(139, 124, 246, 0.14);
  color: var(--accent-3);
  padding: 2px 6px;
  border-radius: 6px;
}
.md :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--accent-2);
  background: rgba(77, 208, 225, 0.08);
  border-radius: 0 8px 8px 0;
  color: var(--text-secondary);
}
.md :deep(a) {
  color: var(--accent-2);
  text-decoration: none;
}
.md :deep(a:hover) {
  text-decoration: underline;
}
.md :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 14px 0;
}
.md :deep(strong) {
  color: var(--text-primary);
}

/* ---------- 输入区 ---------- */
.chat-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  background: rgba(16, 21, 31, 0.6);
}
.chat-input .textarea {
  flex: 1;
  min-height: 46px;
  resize: none;
}

@media (max-width: 860px) {
  .chat {
    height: calc(100vh - 56px - 40px - 68px);
  }
  .conv-list {
    display: none;
  }
}
</style>