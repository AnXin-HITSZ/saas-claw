<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { agentApi, chatApi, ApiError } from '@/api'
import type { Agent, ChatMessage, ConversationMeta } from '@/types/api'
import { useToast } from '@/composables/useToast'

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

const canSend = computed(
  () => !!selectedAlias.value && !!input.value.trim() && !streaming.value,
)

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
    <div class="conv-list">
      <button class="btn btn-primary" style="margin: 12px; width: calc(100% - 24px)" @click="newConversation">
        + 新对话
      </button>
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
        <div v-if="!conversations.length" class="empty" style="padding: 20px">暂无历史会话</div>
      </div>
    </div>

    <!-- 主对话区 -->
    <div class="chat-main">
      <div class="chat-toolbar">
        <label class="text-weak">Agent：</label>
        <select v-model="selectedAlias" class="select" style="width: 220px">
          <option v-for="a in agents" :key="a.id" :value="a.alias">
            {{ a.name }}（{{ a.alias }}）
          </option>
        </select>
        <span v-if="!agents.length" class="text-weak">请先创建 Agent</span>
        <div class="spacer" />
        <span class="text-weak mono">{{ currentConv.slice(0, 8) }}</span>
      </div>

      <div ref="bodyRef" class="chat-body">
        <div v-if="!messages.length" class="empty">开始你的对话吧。</div>
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="`msg-${m.role}`">
          <div class="msg-role">{{ m.role === 'user' ? '我' : m.role === 'assistant' ? 'Agent' : m.role }}</div>
          <div class="msg-content">{{ m.content || (streaming && i === messages.length - 1 ? '▍' : '') }}</div>
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
        <button v-if="!streaming" class="btn btn-primary" :disabled="!canSend" @click="send">发送</button>
        <button v-else class="btn btn-danger" @click="stop">停止</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  height: 100%;
}
.conv-list {
  width: 260px;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.conv-items {
  flex: 1;
  overflow-y: auto;
}
.conv-item {
  padding: 10px 14px;
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.conv-item:hover {
  background: #fafbfc;
}
.conv-item.active {
  background: #eaf1ff;
}
.conv-summary {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-id {
  font-size: 11px;
  color: var(--color-text-weak);
  margin-top: 2px;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: var(--color-bg);
}
.msg {
  margin-bottom: 18px;
  max-width: 780px;
}
.msg-role {
  font-size: 12px;
  color: var(--color-text-weak);
  margin-bottom: 4px;
}
.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  line-height: 1.6;
}
.msg-user {
  margin-left: auto;
}
.msg-user .msg-role {
  text-align: right;
}
.msg-user .msg-content {
  background: #eaf1ff;
  border-color: #d3e1ff;
}
.chat-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
}
.chat-input .textarea {
  flex: 1;
}
</style>
