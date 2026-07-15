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
          <button class="btn-sm btn-danger" @click="handleDelete(agent)">删除</button>
        </div>
      </div>
    </div>

    <!-- Create/Edit Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? "编辑 Agent" : "新建 Agent" }}</h2>
        <form @submit.prevent="handleSave">
          <div class="form-row">
            <div class="form-group">
              <label>Agent Key *</label>
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
            <div class="form-group">
              <label>Shell 审批</label>
              <select v-model="form.shellApproval">
                <option value="deny">deny</option>
                <option value="require">require</option>
                <option value="auto">auto</option>
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>工作目录</label>
              <input v-model="form.workspaceDir" placeholder="/workspace" />
            </div>
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
    systemPrompt: "", toolProfile: "messaging", shellApproval: "deny",
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
    shellApproval: agent.toolPolicy?.shellApproval || "deny",
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
        shellApproval: form.value.shellApproval,
        toolsDeny: [],
        toolsAlsoAllow: [],
        workspaceOnly: true,
        readonly: false,
        webAccess: false,
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
.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 8px 20px; font-size: 14px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.loading, .empty { text-align: center; padding: 48px; color: var(--text-secondary); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 32px; width: 640px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h2 { margin-bottom: 20px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--text-secondary); }
.form-group input, .form-group textarea, .form-group select {
  width: 100%; padding: 8px 12px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; color: var(--text-primary); font-size: 14px;
}
.form-group input:focus, .form-group textarea:focus, .form-group select:focus { outline: none; border-color: var(--accent); }
.switch-field { display: flex; align-items: flex-end; }
.switch-field .switch-line { width: 100%; margin: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
