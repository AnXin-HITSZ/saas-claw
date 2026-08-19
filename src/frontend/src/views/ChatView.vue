<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { agentApi, chatApi, ApiError } from '@/api'
import type { Agent, ChatMessage, ConversationMeta } from '@/types/api'
import { useToast } from '@/composables/useToast'
import AppButton from '@/components/ui/AppButton.vue'
import AppSelect, { type SelectOption } from '@/components/ui/AppSelect.vue'
import renderMarkdown from '@/utils/markdown'

const toast = useToast()

const agents = ref<Agent[]>([])
const selectedAlias = ref<string>('')

const conversations = ref<ConversationMeta[]>([])
const currentConv = ref<string>('')

const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const abortCtrl = ref<AbortController | null>(null)
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
    conversations.value = res.list || []
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载会话失败')
  }
}

async function openConversation(id: string) {
  currentConv.value = id
  messages.value = []
  try {
    const res = await chatApi.getMessages(id)
    messages.value = res.messages.map((m) => ({ role: normRole(m.role), content: m.content }))
    await scrollBottom()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载消息失败')
  }
}

function newConversation() {
  currentConv.value = uuid()
  messages.value = []
  input.value = ''
}

async function send() {
  if (!canSend.value) return
  if (!currentConv.value) currentConv.value = uuid()

  const userText = input.value.trim()
  input.value = ''
  messages.value.push({ role: 'user', content: userText })
  const assistant: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(assistant)
  await scrollBottom()

  streaming.value = true
  abortCtrl.value = new AbortController()

  await chatApi.streamChat(
    {
      model: selectedAlias.value,
      messages: [{ role: 'user', content: userText }],
      conversation_id: currentConv.value,
    },
    {
      signal: abortCtrl.value.signal,
      onMessage: (data) => {
        let obj: unknown
        try {
          obj = JSON.parse(data)
        } catch {
          // 非 JSON 帧，直接当文本增量兜底
          assistant.content += data
          scrollBottom()
          return
        }
        const o = obj as {
          choices?: { delta?: { content?: string } }[]
          type?: string
          payload?: { request_id?: string }
        }
        if (o.type === '__interrupt__') {
          const rid = o.payload?.request_id
          assistant.content += `\n\n⏸️ 触发工具审批（request_id=${rid ?? '?'}），请到「工具审批」处理后继续。`
          toast.info('该操作需要人工审批')
          scrollBottom()
          return
        }
        const delta = o.choices?.[0]?.delta?.content
        if (delta) {
          assistant.content += delta
          scrollBottom()
        }
      },
      onError: (err) => {
        assistant.content += `\n\n[出错] ${err instanceof Error ? err.message : String(err)}`
        toast.error('对话出错')
      },
      onDone: async () => {
        streaming.value = false
        abortCtrl.value = null
        await loadConversations()
      },
    },
  )
}

function stop() {
  abortCtrl.value?.abort()
  streaming.value = false
  abortCtrl.value = null
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
          <div class="conv-id mono">{{ c.conversation_id.slice(0, 8) }}</div>
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
        <div v-if="!messages.length" class="chat-welcome">
          <div class="welcome-logo">◢</div>
          <div class="welcome-title">与你的 Agent 对话</div>
          <div class="welcome-sub">选择一个 Agent，输入消息开始。支持 Markdown 渲染与流式输出。</div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="`msg-${m.role}`">
          <div class="msg-role">
            {{ m.role === 'user' ? '我' : m.role === 'assistant' ? 'Agent' : m.role }}
          </div>
          <div v-if="m.role === 'assistant'" class="msg-content md" v-html="md(m.content)"></div>
          <div v-else class="msg-content">{{ m.content || (streaming && i === messages.length - 1 ? '▍' : '') }}</div>
        </div>
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