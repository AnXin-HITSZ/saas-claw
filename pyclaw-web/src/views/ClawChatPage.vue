<template>
  <div class="chat-page">
    <!-- Header -->
    <div class="chat-header">
      <button class="btn-back" @click="$router.push(`/workspace/claws/${clawId}`)">← Claw 详情</button>
      <h1>{{ claw?.name || "对话" }}</h1>
      <span v-if="claw" class="status-tag" :class="claw.status">{{ claw.status }}</span>
      <div class="role-picker" v-if="roles.length > 1">
        <AppSelect
          v-model="selectedRoleKey"
          class="role-select"
          :options="roles.map(r => ({value:r.roleKey, label: r.defaultRole ? r.displayName + ' · 默认' : r.displayName}))"
        />
      </div>
    </div>

    <!-- Main area -->
    <div class="chat-body">
      <!-- Session sidebar -->
      <aside class="session-sidebar">
        <button class="btn-new-session" @click="newSession">
          <span class="plus-icon">+</span> 新建对话
        </button>
        <div v-if="sessions.length" class="session-list">
          <TransitionGroup name="session">
            <div v-for="s in sessions" :key="s.sessionId"
                 class="session-item" :class="{ active: s.sessionId === activeSessionId }"
                 @click="selectSession(s.sessionId)">
              <span class="session-indicator"></span>
              <div class="session-name">{{ s.agentKey || '会话' }}</div>
              <div class="session-meta">{{ s.messageCount }} 条 · {{ formatShort(s.lastActiveAt) }}</div>
            </div>
          </TransitionGroup>
        </div>
        <p v-else class="no-data">暂无会话</p>
      </aside>

      <!-- Messages -->
      <div class="chat-main">
        <div ref="messagesEl" class="messages-container" @scroll="onScroll">
          <div v-if="messages.length === 0 && !sending && !loadingMessages" class="empty-chat">
            <div class="empty-chat-icon">&#x1F4AC;</div>
            <p>开始与 {{ claw?.name || 'Claw' }} 对话</p>
            <p class="empty-hint">输入 @ 指定 Agent，或直接输入消息使用默认 Agent</p>
          </div>

          <!-- Loading skeleton -->
          <div v-if="loadingMessages" class="messages-skeleton">
            <div v-for="i in 4" :key="i" class="skeleton-row" :class="i % 2 === 0 ? 'user' : 'assistant'">
              <AppSkeleton variant="rect" :height="14" width="60px" />
              <AppSkeleton variant="rect" :height="i % 2 === 0 ? 48 : 64" :width="i % 2 === 0 ? '62%' : '74%'" />
            </div>
          </div>

          <!-- Main timeline messages (visibleInThread=true) -->
          <TransitionGroup name="msg">
            <template v-for="(m, i) in visibleMessages" :key="m._key || i">
              <div class="message-wrapper" :class="m.role">
                <div class="message">
                  <div class="message-role">{{ roleLabelFor(m) }}</div>
                  <div class="message-bubble">{{ m.content }}</div>
                  <!-- Folded sub-events -->
                  <div v-if="getChildren(m._id).length" class="child-events">
                    <FoldedAgentCall
                      v-for="child in getChildren(m._id)"
                      :key="child._key"
                      :event="child"
                      :claw-id="clawId"
                      @approve="approveChildApproval(child)"
                      @reject="rejectChildApproval(child, $event)"
                    />
                  </div>
                </div>
              </div>
            </template>
          </TransitionGroup>

          <div v-if="sending" class="message-wrapper assistant">
            <div class="message">
              <div class="message-role">{{ roleLabel }}</div>
              <div class="message-bubble thinking">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              </div>
            </div>
          </div>
        </div>

        <!-- Tool approval modal (legacy modal fallback) -->
        <AppModal
          :show="showApprovalModal && !!pendingApproval"
          title="需要你确认后继续执行"
          @close="closeApprovalModal"
        >
          <div class="approval-risk-row">
            <AppTag :tone="pendingApproval && pendingApproval.risk === 'high' ? 'danger' : 'warning'" :pulse="!!pendingApproval && pendingApproval.risk === 'high'">
              {{ pendingApproval ? pendingApproval.risk : '' }}
            </AppTag>
          </div>
          <div class="approval-body">
            <div v-if="pendingApproval?.callingAgentInstanceId" class="approval-row">
              <span class="approval-label">调用来源</span>
              <span class="approval-value">由 {{ pendingApproval.callingRoleKey || 'Agent' }} 调用</span>
            </div>
            <div class="approval-row">
              <span class="approval-label">执行 Agent</span>
              <span class="approval-value">{{ pendingApproval?.executingRoleKey || pendingApproval?.roleKey || 'Agent' }}</span>
            </div>
            <div class="approval-row">
              <span class="approval-label">工具名称</span>
              <span class="approval-value">{{ pendingApproval?.toolName }}</span>
            </div>
            <div class="approval-row">
              <span class="approval-label">执行意图</span>
              <span class="approval-value">{{ pendingApproval?.intent || '（未提供意图摘要）' }}</span>
            </div>
            <div class="approval-row">
              <span class="approval-label">参数摘要</span>
              <pre class="approval-args">{{ formatArgumentsPreview(pendingApproval?.argumentsPreview) }}</pre>
            </div>
            <div class="approval-row">
              <span class="approval-label">过期时间</span>
              <span class="approval-value">{{ formatShort(pendingApproval?.expiresAt) }}</span>
            </div>
            <div class="approval-row">
              <span class="approval-label">拒绝原因（可选）</span>
              <textarea
                v-model="rejectReasonInput"
                class="approval-reason-input"
                rows="2"
                placeholder="例如：路径不对、内容不合适、暂不执行..."
                :disabled="resolvingApproval"
              />
            </div>
            <div v-if="approvalError" class="approval-error">{{ approvalError }}</div>
          </div>
          <template #actions>
            <AppButton variant="ghost" :disabled="resolvingApproval" @click="rejectApproval">拒绝执行</AppButton>
            <AppButton variant="primary" :loading="resolvingApproval" loading-text="处理中..." @click="approveApproval">同意执行</AppButton>
          </template>
        </AppModal>

        <!-- Scroll anchor hint -->
        <div v-if="showScrollHint" class="scroll-hint" @click="scrollToBottom">
          ↓ 新消息
        </div>

        <!-- Input with @Agent picker -->
        <div class="chat-input-area">
          <div class="input-wrapper">
            <AgentMentionPicker
              v-if="showMentionPicker"
              :agents="roles"
              :filter="mentionFilter"
              @select="selectMentionedAgent"
              @close="showMentionPicker = false"
            />
            <textarea ref="inputEl" v-model="prompt" :disabled="sending"
                      placeholder="输入消息，输入 @ 选择 Agent..."
                      rows="2"
                      @keydown.enter.exact.prevent="sendMessage"
                      @keydown="onInputKeydown"
                      @input="onInput" />
          </div>
          <button class="btn-send" :disabled="!prompt.trim() || sending"
                  @click="sendMessage">
            <AppSpinner v-if="sending" size="sm" class="send-spinner" />
            <span v-else class="send-icon">↑</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api/client.js";
import { useToast } from "../composables/useToast.js";
import AppModal from "../components/ui/AppModal.vue";
import AppTag from "../components/ui/AppTag.vue";
import AppButton from "../components/ui/AppButton.vue";
import AppSpinner from "../components/ui/AppSpinner.vue";
import AppSelect from "../components/ui/AppSelect.vue";
import AppSkeleton from "../components/ui/AppSkeleton.vue";
import AgentMentionPicker from "../components/chat/AgentMentionPicker.vue";
import FoldedAgentCall from "../components/chat/FoldedAgentCall.vue";

const { toast } = useToast();

const route = useRoute();
const clawId = ref(route.params.id);
const claw = ref(null);
const roles = ref([]);
const sessions = ref([]);
const activeSessionId = ref(null);
const messages = ref([]);
const prompt = ref("");
const sending = ref(false);
const selectedRoleKey = ref("");
const selectedAgentInstanceId = ref("");
const conversationId = ref(null);
const messagesEl = ref(null);
const inputEl = ref(null);
const showScrollHint = ref(false);
const pendingApproval = ref(null);
const showApprovalModal = ref(false);
const resolvingApproval = ref(false);
const approvalError = ref("");
const rejectReasonInput = ref("");
const loadingMessages = ref(false);

// @Agent mention state
const showMentionPicker = ref(false);
const mentionFilter = ref("");
const mentionStartPos = ref(-1);

const roleLabel = computed(() => {
  const r = roles.value.find(r => r.roleKey === selectedRoleKey.value);
  return r ? r.displayName : "Agent";
});

// Visible messages — only those with visibleInThread=true
const visibleMessages = computed(() => {
  return messages.value
    .filter(m => m.visibleInThread !== false)
    .map((m, i) => ({ ...m, _key: m.id || `msg-${i}`, _id: m.id || `msg-${i}` }));
});

// Get children for a parent message
function getChildren(parentId) {
  if (!parentId) return [];
  return messages.value
    .filter(m => m.parentMessageId === parentId)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((m, i) => ({ ...m, _key: m.id || `child-${parentId}-${i}` }));
}

function roleLabelFor(m) {
  if (m.role === 'user') return '你';
  if (m.roleKey) {
    const r = roles.value.find(r => r.roleKey === m.roleKey);
    if (r) return r.displayName;
  }
  return m.role === 'assistant' ? 'Agent' : m.role;
}

// @Agent mention logic
function onInputKeydown(e) {
  if (e.key === '@') {
    mentionStartPos.value = e.target.selectionStart;
    showMentionPicker.value = true;
    mentionFilter.value = "";
  }
}

function onInput() {
  autoResize();
  if (!showMentionPicker.value) return;
  const el = inputEl.value;
  if (!el) return;
  const cursorPos = el.selectionStart;
  if (cursorPos <= mentionStartPos.value) {
    showMentionPicker.value = false;
    return;
  }
  const filter = prompt.value.substring(mentionStartPos.value + 1, cursorPos);
  if (filter.includes(' ')) {
    showMentionPicker.value = false;
    return;
  }
  mentionFilter.value = filter.toLowerCase();
}

function selectMentionedAgent(agent) {
  const before = prompt.value.substring(0, mentionStartPos.value);
  const after = prompt.value.substring(inputEl.value?.selectionStart || mentionStartPos.value + 1);
  prompt.value = before + '@' + agent.roleKey + ' ' + after;
  selectedAgentInstanceId.value = agent.agentInstanceId;
  selectedRoleKey.value = agent.roleKey;
  showMentionPicker.value = false;
  nextTick(() => inputEl.value?.focus());
}

async function load() {
  try {
    const [c, s] = await Promise.all([
      api.get(`/api/claws/${clawId.value}`),
      api.get(`/api/claws/${clawId.value}/chat/sessions`),
    ]);
    claw.value = c;
    roles.value = (c.roles || []).map(r => ({
      ...r,
      agentInstanceId: r.id || r.agentInstanceId,
    })).filter(r => r.enabled);
    sessions.value = s || [];
    if (!selectedRoleKey.value && roles.value.length) {
      const def = roles.value.find(r => r.defaultRole) || roles.value[0];
      selectedRoleKey.value = def.roleKey;
      selectedAgentInstanceId.value = def.agentInstanceId;
    }
  } catch (e) {
    toast.error("加载 Claw 失败: " + e.message);
  }
}

function newSession() {
  activeSessionId.value = null;
  conversationId.value = null;
  messages.value = [];
}

async function selectSession(sid) {
  activeSessionId.value = sid;
  messages.value = [];
  loadingMessages.value = true;
  try {
    const data = await api.get(`/api/sessions/${sid}`);
    messages.value = (data.messages || []).map(m => ({
      id: m.id,
      role: m.role,
      content: m.content,
      visibleInThread: true,
    }));
    await nextTick();
    scrollToBottom(false);
  } catch {
    toast.error("加载会话失败");
  } finally {
    loadingMessages.value = false;
  }
}

async function loadConversationMessages() {
  if (!conversationId.value) return;
  try {
    const data = await api.get(`/api/conversations/${conversationId.value}/messages`);
    messages.value = (data || []).map(m => ({
      id: m.id,
      conversationId: m.conversationId,
      parentMessageId: m.parentMessageId,
      messageType: m.messageType,
      visibleInThread: m.visibleInThread !== false,
      sortOrder: m.sortOrder || 0,
      role: m.role,
      agentInstanceId: m.agentInstanceId,
      agentKey: m.agentKey,
      roleKey: m.roleKey,
      content: m.content,
      metadataJson: m.metadataJson,
      createdAt: m.createdAt,
    }));
    await nextTick();
    scrollToBottom(false);
  } catch {
    // conversation messages may not be available yet
  }
}

async function sendMessage() {
  const text = prompt.value.trim();
  if (!text || sending.value) return;
  sending.value = true;

  messages.value.push({
    id: 'temp-' + Date.now(),
    role: "user",
    content: text,
    roleKey: selectedRoleKey.value,
    visibleInThread: true,
  });
  prompt.value = "";
  if (inputEl.value) inputEl.value.style.height = "auto";
  await nextTick();
  scrollToBottom(true);

  try {
    const res = await api.post(`/api/claws/${clawId.value}/chat/runs`, {
      prompt: text,
      roleKey: selectedRoleKey.value || undefined,
      sessionId: activeSessionId.value || undefined,
      conversationId: conversationId.value || undefined,
      agentInstanceId: selectedAgentInstanceId.value || undefined,
    });
    await handleChatResponse(res);
  } catch (e) {
    toast.error("发送失败: " + e.message);
  } finally {
    sending.value = false;
    await nextTick();
    scrollToBottom(true);
  }
}

async function handleChatResponse(res) {
  if (!res) return;
  activeSessionId.value = res.sessionId || activeSessionId.value;
  if (res.conversationId) {
    conversationId.value = res.conversationId;
    // Load full conversation messages after getting a real conversationId
    await loadConversationMessages();
  }
  if (res.agentInstanceId) selectedAgentInstanceId.value = res.agentInstanceId;
  if (res.status === "PENDING_APPROVAL") {
    const assistantText = res.text || "该操作需要你确认后继续执行。";
    messages.value.push({
      id: 'msg-' + Date.now(),
      role: "assistant",
      content: assistantText,
      roleKey: res.roleKey,
      agentInstanceId: res.agentInstanceId,
      visibleInThread: true,
    });
    pendingApproval.value = res.approval || null;
    if (pendingApproval.value) {
      showApprovalModal.value = true;
    }
  } else if (!res.conversationId) {
    // No conversationId — append inline (legacy session mode)
    messages.value.push({
      id: 'msg-' + Date.now(),
      role: "assistant",
      content: res.text || "(无回复)",
      roleKey: res.roleKey,
      agentInstanceId: res.agentInstanceId,
      visibleInThread: true,
    });
  }
  try {
    const s = await api.get(`/api/claws/${clawId.value}/chat/sessions`);
    sessions.value = s || [];
  } catch {
    // ignore refresh failure
  }
}

async function approveApproval() {
  if (!pendingApproval.value || resolvingApproval.value) return;
  resolvingApproval.value = true;
  approvalError.value = "";
  const approvalId = pendingApproval.value.id;
  const cid = clawId.value;
  try {
    const res = await api.post(`/api/claws/${cid}/chat/approvals/${approvalId}/approve`);
    closeApprovalModal();
    await handleChatResponse(res);
    await nextTick();
    scrollToBottom(true);
  } catch (e) {
    approvalError.value = "审批失败: " + e.message;
  } finally {
    resolvingApproval.value = false;
  }
}

async function rejectApproval() {
  if (!pendingApproval.value || resolvingApproval.value) return;
  resolvingApproval.value = true;
  approvalError.value = "";
  const approvalId = pendingApproval.value.id;
  const cid = clawId.value;
  const reason = rejectReasonInput.value.trim();
  try {
    const res = await api.post(`/api/claws/${cid}/chat/approvals/${approvalId}/reject`, {
      reason: reason || undefined,
    });
    closeApprovalModal();
    await handleChatResponse(res);
    await nextTick();
    scrollToBottom(true);
  } catch (e) {
    approvalError.value = "拒绝失败: " + e.message;
  } finally {
    resolvingApproval.value = false;
  }
}

// Inline approval from folded events
async function approveChildApproval(event) {
  try {
    const meta = parseMetadata(event.metadataJson);
    const approvalId = meta?.approvalId || event.id;
    const res = await api.post(`/api/claws/${clawId.value}/chat/approvals/${approvalId}/approve`);
    await handleChatResponse(res);
    await nextTick();
    scrollToBottom(true);
  } catch (e) {
    toast.error("审批失败: " + e.message);
  }
}

async function rejectChildApproval(event, reason) {
  try {
    const meta = parseMetadata(event.metadataJson);
    const approvalId = meta?.approvalId || event.id;
    const res = await api.post(`/api/claws/${clawId.value}/chat/approvals/${approvalId}/reject`, {
      reason: reason || undefined,
    });
    await handleChatResponse(res);
    await nextTick();
    scrollToBottom(true);
  } catch (e) {
    toast.error("拒绝失败: " + e.message);
  }
}

function parseMetadata(json) {
  if (!json) return {};
  try { return JSON.parse(json); } catch { return {}; }
}

function closeApprovalModal() {
  showApprovalModal.value = false;
  pendingApproval.value = null;
  approvalError.value = "";
  rejectReasonInput.value = "";
}

function formatArgumentsPreview(preview) {
  if (!preview) return "(无参数)";
  try {
    return JSON.stringify(preview, null, 2);
  } catch {
    return String(preview);
  }
}

function autoResize() {
  const el = inputEl.value;
  if (!el) return;
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 160) + "px";
}

function scrollToBottom(smooth) {
  if (!messagesEl.value) return;
  messagesEl.value.scrollTo({
    top: messagesEl.value.scrollHeight,
    behavior: smooth ? "smooth" : "auto",
  });
}

function onScroll() {
  if (!messagesEl.value) return;
  const el = messagesEl.value;
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
  showScrollHint.value = dist > 120;
}

function formatShort(s) {
  if (!s) return "—";
  return new Date(s).toLocaleString("zh-CN");
}

onMounted(load);
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: calc(100vh - var(--topbar-height) - var(--content-gutter) * 2); }
.chat-header { display: flex; align-items: center; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.chat-header h1 { font-size: 20px; font-weight: 700; letter-spacing: -0.3px; }

.status-tag {
  font-size: 11px; padding: 3px 10px; border-radius: 999px; font-weight: 600;
  background: var(--accent-glow); color: var(--accent);
}
.status-tag.inactive, .status-tag.disabled { background: rgba(255, 92, 92, 0.1); color: var(--danger); }

.role-picker { margin-left: auto; }
.role-select { width: auto; min-width: 160px; }

/* Body */
.chat-body { display: flex; flex: 1; overflow: hidden; }

/* Session sidebar */
.session-sidebar {
  width: 220px; padding: 14px; overflow-y: auto; flex-shrink: 0;
  background: rgba(8, 11, 17, 0.6);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-right: 1px solid var(--border);
}

.btn-new-session {
  width: 100%; padding: 9px; font-size: 13px; font-weight: 600; color: var(--accent);
  background: var(--accent-glow); border: 1px dashed var(--accent);
  border-radius: var(--radius-sm); margin-bottom: 12px;
  transition: all 0.2s var(--ease-out);
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.btn-new-session:hover { background: var(--accent-glow-strong); }
.plus-icon { font-size: 16px; font-weight: 700; }

.session-list { display: flex; flex-direction: column; gap: 2px; }

.session-item {
  position: relative; padding: 8px 12px; border-radius: var(--radius-sm);
  font-size: 13px; cursor: pointer;
  transition: all 0.2s var(--ease-out);
}
.session-item:hover { background: rgba(255, 255, 255, 0.04); }
.session-item.active {
  color: var(--accent);
  background: linear-gradient(90deg, var(--accent-glow), transparent 80%);
}
.session-indicator {
  position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; border-radius: 3px;
  background: var(--gradient-aurora);
  opacity: 0; transform: scaleY(0.4);
  transition: opacity 0.2s var(--ease-out), transform 0.25s var(--ease-spring);
}
.session-item.active .session-indicator { opacity: 1; transform: scaleY(1); }
.session-name { font-weight: 600; font-size: 12px; }
.session-meta { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

/* Session transition */
.session-enter-active { transition: all 0.3s var(--ease-spring); }
.session-leave-active { transition: all 0.2s var(--ease-out); }
.session-enter-from { opacity: 0; transform: translateX(-12px); }
.session-leave-to { opacity: 0; transform: translateX(-8px); }

/* Chat main */
.chat-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; position: relative; }

.messages-container { flex: 1; overflow-y: auto; padding: 24px 28px 96px; scroll-behavior: smooth; }

.empty-chat { text-align: center; padding: 72px 24px; }
.empty-chat-icon { font-size: 40px; margin-bottom: 12px; opacity: 0.6; animation: float 3s var(--ease-in-out) infinite; }
.empty-chat p { color: var(--text-secondary); font-size: 15px; }
.empty-hint { color: var(--text-muted) !important; font-size: 13px !important; margin-top: 6px; }
@keyframes float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }

/* Loading skeleton */
.messages-skeleton { display: flex; flex-direction: column; gap: 20px; padding: 8px 0; }
.skeleton-row { display: flex; flex-direction: column; gap: 6px; max-width: 78%; }
.skeleton-row.user { align-self: flex-end; align-items: flex-end; }
.skeleton-row.assistant { align-self: flex-start; align-items: flex-start; }

/* Message */
.message-wrapper { margin-bottom: 20px; display: flex; }
.message-wrapper.user { justify-content: flex-end; }
.message-wrapper.assistant { justify-content: flex-start; }

.message { max-width: 78%; }
.message-role { font-size: 11px; font-weight: 600; margin-bottom: 4px; letter-spacing: 0.3px; }
.message-wrapper.user .message-role { text-align: right; color: var(--accent); }
.message-wrapper.assistant .message-role { color: var(--text-muted); }

.message-bubble {
  padding: 12px 16px; border-radius: var(--radius); font-size: 14px; line-height: 1.65;
  white-space: pre-wrap; word-break: break-word;
}
.message-wrapper.user .message-bubble {
  background: var(--gradient-aurora); color: #0a0e14;
  border-bottom-right-radius: 4px;
  box-shadow: 0 6px 20px rgba(245, 168, 61, 0.18);
}
.message-wrapper.assistant .message-bubble {
  position: relative;
  background: var(--bg-raised);
  border-bottom-left-radius: 4px;
}
.message-wrapper.assistant .message-bubble::before {
  content: ""; position: absolute; inset: 0; border-radius: inherit;
  padding: 1px; background: var(--gradient-aurora);
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor; mask-composite: exclude;
  opacity: 0.45; pointer-events: none;
}

/* Child events (folded) */
.child-events { margin-top: 8px; margin-left: 4px; }

/* Message entrance animation */
.msg-enter-active { transition: all 0.35s var(--ease-spring); }
.msg-leave-active { transition: all 0.15s var(--ease-out); }
.msg-enter-from { opacity: 0; transform: translateY(16px) scale(0.97); }
.msg-leave-to { opacity: 0; }

/* Thinking dots */
.thinking { display: flex; align-items: center; gap: 6px; padding: 16px 24px !important; min-width: 60px; }
.dot {
  width: 7px; height: 7px; border-radius: 50%; background: var(--accent);
  animation: dot-breathe 1.4s var(--ease-in-out) infinite;
}
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-breathe {
  0%, 100% { transform: scale(0.6); opacity: 0.35; box-shadow: 0 0 0 0 rgba(245, 168, 61, 0); }
  50% { transform: scale(1); opacity: 1; box-shadow: 0 0 8px rgba(245, 168, 61, 0.5); }
}

/* Scroll hint */
.scroll-hint {
  position: absolute; bottom: 96px; left: 50%; transform: translateX(-50%);
  padding: 6px 16px; background: var(--gradient-aurora); color: #0a0e14;
  border-radius: 20px; font-size: 12px; font-weight: 600; cursor: pointer;
  animation: fade-in-up 0.25s var(--ease-out); z-index: 5;
  box-shadow: 0 2px 12px rgba(245, 168, 61, 0.3);
}
@keyframes fade-in-up {
  from { opacity: 0; transform: translateX(-50%) translateY(8px); }
  to { opacity: 1; transform: translateX(-50%) translateY(0); }
}

/* Input area */
.chat-input-area {
  position: absolute; bottom: 16px; left: 28px; right: 28px;
  display: flex; gap: 10px; padding: 10px;
  background: rgba(8, 11, 17, 0.72);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  border: 1px solid var(--border-light);
  border-radius: 16px; align-items: flex-end;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
}
.chat-input-area:focus-within {
  border-color: var(--accent);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35), var(--glow-accent);
}
.input-wrapper { flex: 1; position: relative; }
.chat-input-area textarea {
  width: 100%; padding: 10px 14px; background: transparent; border: none;
  color: var(--text-primary); font-size: 14px;
  resize: none; font-family: inherit; line-height: 1.5;
  max-height: 160px; box-sizing: border-box;
}
.chat-input-area textarea:focus { outline: none; }
.chat-input-area textarea::placeholder { color: var(--text-muted); }

.btn-send {
  width: 42px; height: 42px; flex-shrink: 0;
  font-size: 18px; font-weight: 700; color: #0a0e14;
  background: var(--gradient-aurora); border: none; border-radius: 50%;
  transition: all 0.2s var(--ease-out);
  display: flex; align-items: center; justify-content: center;
}
.btn-send:hover:not(:disabled) { transform: translateY(-1px); box-shadow: var(--glow-accent); }
.btn-send:active:not(:disabled) { transform: translateY(0); }
.btn-send:disabled { opacity: 0.4; cursor: not-allowed; }
.send-icon { line-height: 1; }
.send-spinner { color: #0a0e14; }

.no-data { color: var(--text-muted); font-size: 12px; padding: 16px 8px; text-align: center; }

/* Approval modal */
.approval-risk-row { margin-bottom: 14px; }
.approval-body { display: flex; flex-direction: column; gap: 10px; margin-bottom: 4px; }
.approval-row { display: flex; flex-direction: column; gap: 4px; }
.approval-label { color: var(--text-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.3px; }
.approval-value { color: var(--text-primary); font-size: 13px; word-break: break-all; }
.approval-args {
  background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: var(--radius-sm); padding: 10px 12px;
  font-size: 12px; max-height: 220px; overflow: auto;
  white-space: pre-wrap; word-break: break-word; margin: 0;
}
.approval-error {
  padding: 8px 12px; border-radius: var(--radius-sm); font-size: 12px;
  color: var(--danger); background: rgba(248, 81, 73, 0.08);
}
.approval-reason-input {
  background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: var(--radius-sm); padding: 8px 10px;
  color: var(--text-primary); font-size: 13px; font-family: inherit;
  line-height: 1.5; resize: vertical; min-height: 56px;
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
}
.approval-reason-input:focus {
  outline: none; border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-glow);
}
.approval-reason-input:disabled { opacity: 0.5; }
</style>
