<template>
  <div class="chat-page">
    <!-- Header -->
    <div class="chat-header">
      <button class="btn-back" @click="$router.push(`/workspace/claws/${clawId}`)">← Claw 详情</button>
      <h1>{{ claw?.name || "对话" }}</h1>
      <span v-if="claw" class="status-tag" :class="claw.status">{{ claw.status }}</span>
      <div class="role-picker" v-if="roles.length > 1">
        <select v-model="selectedAgentInstanceId" class="role-select" @change="onAgentSelected">
          <option v-for="r in roles" :key="r.id || r.roleKey" :value="r.id">
            {{ r.displayName }}{{ r.defaultRole ? ' · 默认' : '' }}{{ r.sourceType === 'package' ? ' (市场)' : '' }}
          </option>
        </select>
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
          <div v-if="messages.length === 0 && !sending" class="empty-chat">
            <div class="empty-chat-icon">&#x1F4AC;</div>
            <p>开始与 {{ claw?.name || 'Claw' }} 对话</p>
            <p class="empty-hint">选择一个角色，输入你的第一条消息</p>
          </div>

          <TransitionGroup name="msg">
            <div v-for="(m, i) in messages" :key="i" class="message-wrapper" :class="m.role">
              <div class="message">
                <div class="message-role">{{ m.role === 'user' ? '你' : roleLabel }}</div>
                <div class="message-bubble">{{ m.content }}</div>
              </div>
            </div>
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

        <!-- Tool approval modal -->
        <div v-if="showApprovalModal && pendingApproval" class="approval-mask" @click.self="closeApprovalModal">
          <div class="approval-modal">
            <div class="approval-header">
              <h3>需要你确认后继续执行</h3>
              <span class="approval-risk" :class="pendingApproval.risk">{{ pendingApproval.risk }}</span>
            </div>
            <div class="approval-body">
              <div class="approval-row">
                <span class="approval-label">工具名称</span>
                <span class="approval-value">{{ pendingApproval.toolName }}</span>
              </div>
              <div class="approval-row">
                <span class="approval-label">执行意图</span>
                <span class="approval-value">{{ pendingApproval.intent || '（未提供意图摘要）' }}</span>
              </div>
              <div class="approval-row">
                <span class="approval-label">参数摘要</span>
                <pre class="approval-args">{{ formatArgumentsPreview(pendingApproval.argumentsPreview) }}</pre>
              </div>
              <div class="approval-row">
                <span class="approval-label">过期时间</span>
                <span class="approval-value">{{ formatShort(pendingApproval.expiresAt) }}</span>
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
            <div class="approval-actions">
              <button class="btn-secondary" :disabled="resolvingApproval" @click="rejectApproval">拒绝执行</button>
              <button class="btn-primary" :disabled="resolvingApproval" @click="approveApproval">同意执行</button>
            </div>
          </div>
        </div>

        <!-- Scroll anchor hint -->
        <div v-if="showScrollHint" class="scroll-hint" @click="scrollToBottom">
          ↓ 新消息
        </div>

        <!-- Error -->
        <div v-if="error" class="chat-error">{{ error }}</div>

        <!-- Input -->
        <div class="chat-input-area">
          <textarea ref="inputEl" v-model="prompt" :disabled="sending"
                    placeholder="输入消息..." rows="2"
                    @keydown.enter.exact.prevent="sendMessage"
                    @input="autoResize" />
          <button class="btn-send" :disabled="!prompt.trim() || sending"
                  @click="sendMessage">
            <span v-if="!sending">发送</span>
            <span v-else class="sending-spinner"></span>
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

const route = useRoute();
const clawId = ref(route.params.id);
const claw = ref(null);
const roles = ref([]);
const sessions = ref([]);
const activeSessionId = ref(null);
const messages = ref([]);
const prompt = ref("");
const sending = ref(false);
const error = ref("");
const selectedRoleKey = ref("");
const conversationId = ref(null);
const selectedAgentInstanceId = ref("");
const messagesEl = ref(null);
const inputEl = ref(null);
const showScrollHint = ref(false);
const pendingApproval = ref(null);
const showApprovalModal = ref(false);
const resolvingApproval = ref(false);
const approvalError = ref("");
const rejectReasonInput = ref("");

const roleLabel = computed(() => {
  const r = roles.value.find(r => r.roleKey === selectedRoleKey.value);
  return r ? r.displayName : "Agent";
});

async function load() {
  try {
    const [c, s] = await Promise.all([
      api.get(`/api/claws/${clawId.value}`),
      api.get(`/api/claws/${clawId.value}/chat/sessions`),
    ]);
    claw.value = c;
    roles.value = (c.roles || []).filter(r => r.enabled);
    sessions.value = s || [];
    if (!selectedAgentInstanceId.value && roles.value.length) {
      const def = roles.value.find(r => r.defaultRole) || roles.value[0];
      selectedAgentInstanceId.value = def.id;
      selectedRoleKey.value = def.roleKey;
    }
  } catch (e) {
    error.value = "加载 Claw 失败: " + e.message;
  }
}

function onAgentSelected() {
  const selected = roles.value.find(r => r.id === selectedAgentInstanceId.value);
  if (selected) {
    selectedRoleKey.value = selected.roleKey;
  }
}

function newSession() {
  conversationId.value = null;
  activeSessionId.value = null;
  messages.value = [];
  error.value = "";
}

async function selectSession(sid) {
  activeSessionId.value = sid;
  messages.value = [];
  try {
    const data = await api.get(`/api/sessions/${sid}`);
    messages.value = (data.messages || []).map(m => ({
      role: m.role,
      content: m.content,
    }));
    await nextTick();
    scrollToBottom(false);
  } catch {
    error.value = "加载会话失败";
  }
}

async function sendMessage() {
  const text = prompt.value.trim();
  if (!text || sending.value) return;
  error.value = "";
  sending.value = true;

  messages.value.push({ role: "user", content: text });
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
    error.value = "发送失败: " + e.message;
  } finally {
    sending.value = false;
    await nextTick();
    scrollToBottom(true);
  }
}

async function handleChatResponse(res) {
  if (!res) return;
  activeSessionId.value = res.sessionId || activeSessionId.value;
  if (res.conversationId) conversationId.value = res.conversationId;
  if (res.agentInstanceId) selectedAgentInstanceId.value = res.agentInstanceId;
  if (res.status === "PENDING_APPROVAL") {
    const assistantText = res.text || "该操作需要你确认后继续执行。";
    messages.value.push({ role: "assistant", content: assistantText });
    pendingApproval.value = res.approval || null;
    if (pendingApproval.value) {
      showApprovalModal.value = true;
    }
  } else {
    messages.value.push({ role: "assistant", content: res.text || "(无回复)" });
  }
  try {
    const s = await api.get(`/api/claws/${clawId.value}/chat/sessions`);
    sessions.value = s || [];
  } catch {
    // ignore refresh failure; main content already updated
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

.role-picker { margin-left: auto; }
.role-select {
  padding: 6px 12px; background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: var(--radius-sm); color: var(--text-primary); font-size: 13px;
  transition: border-color 0.2s var(--ease-out);
}
.role-select:focus { outline: none; border-color: var(--accent); }

/* Body */
.chat-body { display: flex; flex: 1; overflow: hidden; }

/* Session sidebar */
.session-sidebar { width: 220px; border-right: 1px solid var(--border); padding: 14px; overflow-y: auto; flex-shrink: 0; }

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
  padding: 8px 10px; border-radius: var(--radius-sm); font-size: 13px; cursor: pointer;
  transition: all 0.2s var(--ease-out);
}
.session-item:hover { background: var(--bg-hover); }
.session-item.active { background: var(--accent-glow); }
.session-name { font-weight: 600; font-size: 12px; }
.session-meta { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

/* Session transition */
.session-enter-active { transition: all 0.3s var(--ease-spring); }
.session-leave-active { transition: all 0.2s var(--ease-out); }
.session-enter-from { opacity: 0; transform: translateX(-12px); }
.session-leave-to { opacity: 0; transform: translateX(-8px); }

/* Chat main */
.chat-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; position: relative; }

.messages-container { flex: 1; overflow-y: auto; padding: 24px 28px; scroll-behavior: smooth; }

.empty-chat { text-align: center; padding: 72px 24px; }
.empty-chat-icon { font-size: 40px; margin-bottom: 12px; opacity: 0.6; animation: float 3s var(--ease-in-out) infinite; }
.empty-chat p { color: var(--text-secondary); font-size: 15px; }
.empty-hint { color: var(--text-muted) !important; font-size: 13px !important; margin-top: 6px; }
@keyframes float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }

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
  background: var(--accent); color: #0a0e14;
  border-bottom-right-radius: 4px;
}
.message-wrapper.assistant .message-bubble {
  background: var(--bg-surface); border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
}

/* Message entrance animation */
.msg-enter-active { transition: all 0.35s var(--ease-spring); }
.msg-leave-active { transition: all 0.15s var(--ease-out); }
.msg-enter-from { opacity: 0; transform: translateY(16px) scale(0.97); }
.msg-leave-to { opacity: 0; }

/* Thinking dots */
.thinking { display: flex; align-items: center; gap: 6px; padding: 16px 24px !important; min-width: 60px; }
.dot {
  width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted);
  animation: dot-bounce 1.4s var(--ease-in-out) infinite;
}
.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* Scroll hint */
.scroll-hint {
  position: absolute; bottom: 90px; left: 50%; transform: translateX(-50%);
  padding: 6px 16px; background: var(--accent); color: #0a0e14;
  border-radius: 20px; font-size: 12px; font-weight: 600; cursor: pointer;
  animation: fade-in-up 0.25s var(--ease-out); z-index: 5;
  box-shadow: 0 2px 12px rgba(240,163,58,0.3);
}
@keyframes fade-in-up {
  from { opacity: 0; transform: translateX(-50%) translateY(8px); }
  to { opacity: 1; transform: translateX(-50%) translateY(0); }
}

/* Error */
.chat-error {
  padding: 10px 20px; font-size: 13px; color: var(--danger);
  background: rgba(248,81,73,0.08); border-top: 1px solid rgba(248,81,73,0.2);
  animation: fade-in-up 0.2s var(--ease-out);
}

/* Input area */
.chat-input-area { display: flex; gap: 8px; padding: 16px 20px; border-top: 1px solid var(--border); align-items: flex-end; }
.chat-input-area textarea {
  flex: 1; padding: 10px 14px; background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: var(--radius); color: var(--text-primary); font-size: 14px;
  resize: none; font-family: inherit; line-height: 1.5;
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
  max-height: 160px;
}
.chat-input-area textarea:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }

.btn-send {
  padding: 10px 22px; font-size: 14px; font-weight: 700; color: #0a0e14;
  background: var(--accent); border: none; border-radius: var(--radius);
  transition: all 0.2s var(--ease-out); min-width: 64px; height: 42px;
  display: flex; align-items: center; justify-content: center;
}
.btn-send:hover:not(:disabled) { background: var(--accent-soft); transform: translateY(-1px); box-shadow: var(--shadow-glow); }
.btn-send:active:not(:disabled) { transform: translateY(0); }
.btn-send:disabled { opacity: 0.4; }

/* Sending spinner */
.sending-spinner {
  width: 16px; height: 16px; border: 2px solid transparent;
  border-top-color: #0a0e14; border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.no-data { color: var(--text-muted); font-size: 12px; padding: 16px 8px; text-align: center; }

/* Approval modal */
.approval-mask {
  position: fixed; inset: 0; background: rgba(4,7,12,0.65); backdrop-filter: blur(2px);
  display: flex; align-items: center; justify-content: center; z-index: 20;
  animation: fade-in-up 0.15s var(--ease-out);
}
.approval-modal {
  background: var(--bg-surface); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 20px 22px; width: min(560px, 92vw);
  box-shadow: 0 12px 48px rgba(0,0,0,0.5);
}
.approval-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.approval-header h3 { font-size: 16px; font-weight: 700; flex: 1; }
.approval-risk {
  font-size: 11px; padding: 2px 8px; border-radius: 999px;
  background: rgba(210,153,34,0.14); color: var(--warning); text-transform: lowercase;
}
.approval-risk.high { background: rgba(248,81,73,0.12); color: var(--danger); }
.approval-risk.low { background: rgba(56,139,253,0.14); color: #58a6ff; }
.approval-body { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.approval-row { display: flex; flex-direction: column; gap: 4px; }
.approval-label { color: var(--text-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.3px; }
.approval-value { color: var(--text-primary); font-size: 13px; word-break: break-all; }
.approval-args {
  background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: var(--radius-sm); padding: 10px 12px;
  font-size: 12px; max-height: 220px; overflow: auto;
  white-space: pre-wrap; word-break: break-word;
}
.approval-error {
  padding: 8px 12px; border-radius: var(--radius-sm); font-size: 12px;
  color: var(--danger); background: rgba(248,81,73,0.08);
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
.approval-actions { display: flex; justify-content: flex-end; gap: 8px; }
.approval-actions .btn-primary {
  padding: 8px 18px; font-size: 13px; font-weight: 700; color: #0a0e14;
  background: var(--accent); border: none; border-radius: var(--radius-sm);
}
.approval-actions .btn-primary:disabled { opacity: 0.5; }
.approval-actions .btn-secondary {
  padding: 8px 18px; font-size: 13px; font-weight: 600; color: var(--text-primary);
  background: var(--bg-deep); border: 1px solid var(--border); border-radius: var(--radius-sm);
}
.approval-actions .btn-secondary:disabled { opacity: 0.5; }
</style>
