<template>
  <div class="page">
    <div class="page-header">
      <h1>Agent 配置</h1>
      <button class="btn-primary" @click="openCreate">+ 新建 Agent</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="agents.length === 0" class="empty-state">
      <div class="empty-state-icon">🤖</div>
      <h3>还没有 Agent 配置</h3>
      <p>Agent 定义了 Claw 的行为方式——模型、系统提示词、工具策略和审批模式。</p>
      <button class="btn-primary" @click="openCreate">+ 创建第一个 Agent</button>
    </div>
    <div v-else class="agent-grid">
      <div v-for="agent in agents" :key="agent.id" class="card agent-card">
        <div class="agent-header">
          <h3>{{ agent.name }}</h3>
          <span class="agent-status" :class="agent.enabled ? 'enabled' : 'disabled'">
            {{ agent.enabled ? "启用" : "停用" }}
          </span>
        </div>
        <p class="agent-key">{{ agent.agentKey }}</p>
        <p class="agent-desc">{{ agent.description || "暂无描述" }}</p>
        <div class="agent-detail">
          <span>Provider: {{ agent.provider || "默认" }}</span>
          <span>Model: {{ agent.model || "默认" }}</span>
          <span>Tool Profile: {{ agent.toolPolicy?.profile || "messaging" }}</span>
        </div>
        <p v-if="agent.systemPrompt" class="agent-prompt">System Prompt: {{ truncate(agent.systemPrompt, 100) }}</p>
        <div class="agent-actions">
          <button class="btn-sm" @click="openEdit(agent)">编辑</button>
          <button class="btn-sm btn-publish" @click="openPublish(agent)">发布</button>
          <button class="btn-sm btn-danger" @click="handleDelete(agent)">删除</button>
        </div>
        <p v-if="agent.publishStatus" class="publish-status">
          已发布: {{ agent.publishStatus }}
        </p>
      </div>
    </div>

    <!-- Create/Edit Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? "编辑 Agent" : "新建 Agent" }}</h2>
        <form @submit.prevent="handleSave">
          <div class="form-row">
            <div class="form-group">
              <label class="label-with-help">
                <span>Agent Key *</span>
                <span
                  class="help-dot"
                  tabindex="0"
                  role="img"
                  aria-label="Agent Key 是 Agent 本体的唯一标识，用于运行时查找这套模型、提示词和工具配置。"
                  data-tooltip="Agent 本体的唯一标识。它定义“这个 Agent 是谁”，会绑定模型、System Prompt、工具策略和工作目录。"
                >?</span>
              </label>
              <input v-model="form.agentKey" required placeholder="my-agent" />
            </div>
            <div class="form-group">
              <label>名称 *</label>
              <input v-model="form.name" required placeholder="我的 Agent" />
            </div>
          </div>
          <div class="form-group">
            <label>描述</label>
            <input v-model="form.description" placeholder="简要描述 Agent 的用途" />
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Provider</label>
              <select v-model="form.providerId">
                <option value="">默认</option>
                <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }} ({{ p.model }})</option>
              </select>
            </div>
            <div class="form-group">
              <label>Model（可选，覆盖 Provider 默认）</label>
              <input v-model="form.model" placeholder="如 deepseek-chat" />
            </div>
          </div>
          <div class="form-group">
            <label>System Prompt</label>
            <textarea v-model="form.systemPrompt" rows="4" placeholder="给 Agent 的系统提示词..." />
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Tool Profile</label>
              <select v-model="form.toolProfile">
                <option value="minimal">minimal</option>
                <option value="readonly">readonly</option>
                <option value="coding">coding</option>
                <option value="messaging">messaging</option>
                <option value="full">full</option>
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>工作目录</label>
              <input v-model="form.workspaceDir" placeholder="/workspace" />
            </div>
            <div class="form-group"></div>
          </div>
          <div class="form-row single-switch-row">
            <div class="form-group switch-field">
              <label class="switch-line">
                <span class="switch-label">启用</span>
                <input class="switch-input" type="checkbox" v-model="form.enabled" />
                <span class="switch-track"></span>
              </label>
            </div>
          </div>
          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="showModal = false">取消</button>
            <button type="submit" class="btn-primary">保存</button>
          </div>
        </form>
      </div>
    </div>

    <!-- Publish Modal -->
    <div v-if="showPublishModal" class="modal-overlay" @click.self="showPublishModal = false">
      <div class="modal">
        <h2>发布 Agent: {{ publishTarget?.name }}</h2>
        <form @submit.prevent="handlePublish">
          <div class="form-row">
            <div class="form-group">
              <label>Package Key *</label>
              <input v-model="publishForm.packageKey" required placeholder="my-agent-package" />
            </div>
            <div class="form-group">
              <label>版本 *</label>
              <input v-model="publishForm.version" required placeholder="1.0.0" />
            </div>
          </div>
          <div class="form-group">
            <label>可见性</label>
            <select v-model="publishForm.visibility">
              <option value="private">private — 仅自己可见</option>
              <option value="unlisted">unlisted — 有链接可见</option>
              <option value="public">public — 市场可见</option>
            </select>
          </div>
          <div class="form-group">
            <label>简介</label>
            <input v-model="publishForm.summary" placeholder="简要描述 Agent 的用途" />
          </div>
          <div class="form-group">
            <label>更新日志</label>
            <input v-model="publishForm.changelog" placeholder="本次发布的变更说明" />
          </div>
          <p v-if="publishError" class="chat-error">{{ publishError }}</p>
          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="showPublishModal = false">取消</button>
            <button type="submit" class="btn-primary" :disabled="publishLoading">{{ publishLoading ? '发布中...' : '发布' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { api } from "../api/client.js";

const agents = ref([]);
const providers = ref([]);
const loading = ref(true);
const showModal = ref(false);
const editing = ref(null);
const form = ref({});

async function load() {
  loading.value = true;
  try {
    const [a, p] = await Promise.all([
      api.get("/api/agents"),
      api.get("/api/providers/options").catch(() => []),
    ]);
    agents.value = a;
    providers.value = p;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = {
    agentKey: "", name: "", description: "", providerId: "", model: "",
    systemPrompt: "", toolProfile: "messaging",
    workspaceDir: "", enabled: true,
  };
  showModal.value = true;
}

function openEdit(agent) {
  editing.value = agent;
  form.value = {
    agentKey: agent.agentKey,
    name: agent.name,
    description: agent.description || "",
    providerId: agent.providerId || "",
    model: agent.model || "",
    systemPrompt: agent.systemPrompt || "",
    toolProfile: agent.toolPolicy?.profile || "messaging",
    workspaceDir: agent.workspaceDir || "",
    enabled: agent.enabled,
  };
  showModal.value = true;
}

async function handleSave() {
  try {
    const body = {
      agentKey: form.value.agentKey,
      name: form.value.name,
      description: form.value.description || undefined,
      providerId: form.value.providerId || undefined,
      model: form.value.model || undefined,
      systemPrompt: form.value.systemPrompt || undefined,
      workspaceDir: form.value.workspaceDir || undefined,
      enabled: form.value.enabled,
      toolPolicy: {
        profile: form.value.toolProfile,
        toolsDeny: [],
        toolsAlsoAllow: [],
        readonly: false,
      },
    };
    if (editing.value) {
      await api.put(`/api/agents/${editing.value.id}`, body);
    } else {
      await api.post("/api/agents", body);
    }
    showModal.value = false;
    await load();
  } catch (e) {
    alert("保存失败: " + e.message);
  }
}

async function handleDelete(agent) {
  if (!confirm(`确定删除 Agent "${agent.name}"？`)) return;
  try {
    await api.delete(`/api/agents/${agent.id}`);
    await load();
  } catch (e) {
    alert("删除失败: " + e.message);
  }
}

const showPublishModal = ref(false);
const publishTarget = ref(null);
const publishLoading = ref(false);
const publishError = ref("");
const publishForm = ref({ packageKey: "", version: "", visibility: "public", summary: "", changelog: "" });

function openPublish(agent) {
  publishTarget.value = agent;
  publishForm.value = {
    packageKey: agent.agentKey,
    version: "1.0.0",
    visibility: "public",
    summary: agent.description || "",
    changelog: "",
  };
  publishError.value = "";
  showPublishModal.value = true;
}

async function handlePublish() {
  publishLoading.value = true;
  publishError.value = "";
  try {
    await api.post(`/api/agents/${publishTarget.value.id}/publish`, {
      packageKey: publishForm.value.packageKey,
      version: publishForm.value.version,
      visibility: publishForm.value.visibility,
      summary: publishForm.value.summary || undefined,
      changelog: publishForm.value.changelog || undefined,
    });
    showPublishModal.value = false;
    await load();
  } catch (e) {
    publishError.value = e.message;
  } finally {
    publishLoading.value = false;
  }
}

function truncate(text, max) {
  if (!text) return "";
  return text.length > max ? text.slice(0, max) + "..." : text;
}

onMounted(load);
</script>

<style scoped>
.page { max-width: 1200px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-header h1 { font-size: 24px; }
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 20px; }
.agent-card { cursor: default; }
.agent-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.agent-header h3 { font-size: 16px; }
.agent-status { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.agent-status.enabled { background: rgba(63,185,80,0.15); color: var(--success); }
.agent-status.disabled { background: rgba(110,118,129,0.15); color: var(--text-muted); }
.agent-key { font-family: monospace; font-size: 12px; color: var(--accent); margin-bottom: 8px; }
.agent-desc { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; }
.agent-detail { display: flex; gap: 16px; font-size: 12px; color: var(--text-muted); margin-bottom: 8px; }
.agent-prompt { font-size: 12px; color: var(--text-muted); background: var(--bg-primary); padding: 8px; border-radius: 4px; margin-bottom: 12px; font-family: monospace; }
.agent-actions { display: flex; gap: 8px; }
.btn-sm { padding: 4px 12px; font-size: 12px; border-radius: 4px; border: 1px solid var(--border-color); background: transparent; color: var(--text-secondary); }
.btn-danger { color: var(--danger); border-color: var(--danger); }
.btn-publish { color: var(--accent); border-color: var(--accent); }
.publish-status { font-size: 11px; color: var(--success); margin-top: 4px; }
.chat-error { color: var(--danger); font-size: 13px; margin-top: 8px; }
.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 8px 20px; font-size: 14px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.loading, .empty { text-align: center; padding: 48px; color: var(--text-secondary); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 32px; width: 640px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h2 { margin-bottom: 20px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--text-secondary); }
.form-group .label-with-help { display: inline-flex; align-items: center; gap: 6px; }
.help-dot {
  position: relative;
  width: 16px;
  height: 16px;
  display: inline-grid;
  place-items: center;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  color: var(--text-muted);
  background: var(--bg-primary);
  font-size: 11px;
  line-height: 1;
  cursor: help;
}
.help-dot:hover,
.help-dot:focus-visible {
  color: var(--accent);
  border-color: var(--accent);
  outline: none;
}
.help-dot::after {
  content: attr(data-tooltip);
  position: absolute;
  left: 50%;
  bottom: calc(100% + 8px);
  z-index: 120;
  width: min(300px, 72vw);
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-primary);
  background: var(--bg-surface);
  box-shadow: 0 10px 30px rgba(0,0,0,0.28);
  font-size: 12px;
  line-height: 1.45;
  font-weight: 500;
  white-space: normal;
  transform: translateX(-50%) translateY(4px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s var(--ease-out), transform 0.15s var(--ease-out);
}
.help-dot:hover::after,
.help-dot:focus-visible::after {
  opacity: 1;
  transform: translateX(-50%) translateY(0);
}
.form-group input, .form-group textarea, .form-group select {
  width: 100%; padding: 8px 12px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; color: var(--text-primary); font-size: 14px;
}
.form-group input:focus, .form-group textarea:focus, .form-group select:focus { outline: none; border-color: var(--accent); }
.switch-field { display: flex; align-items: flex-end; }
.switch-field .switch-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  margin: 0;
  cursor: pointer;
}
.switch-field .switch-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  opacity: 0;
  pointer-events: none;
}
.switch-field .switch-track {
  display: block;
  position: relative;
  width: 34px;
  height: 20px;
  flex: 0 0 34px;
  border-radius: 999px;
}
.single-switch-row { grid-template-columns: 1fr 1fr; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
