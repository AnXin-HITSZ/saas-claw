<template>
  <div class="page">
    <div class="page-header">
      <h1>Provider 管理</h1>
      <button class="btn-primary" @click="openCreate">+ 新建 Provider</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="providers.length === 0" class="empty-state">
      <div class="empty-state-icon">⚡</div>
      <h3>还没有 Provider 配置</h3>
      <p>Provider 是 AI 模型的接入点，配置后可被 Agent 和 Claw 使用。</p>
      <button class="btn-primary" @click="showCreate = true">+ 添加第一个 Provider</button>
    </div>
    <div v-else class="provider-grid">
      <div v-for="p in providers" :key="p.id" class="card provider-card">
        <div class="provider-header">
          <h3>{{ p.name }}</h3>
          <span class="provider-status" :class="p.enabled ? 'enabled' : 'disabled'">
            {{ p.enabled ? "启用" : "停用" }}
          </span>
        </div>
        <div class="provider-detail">
          <div>类型: {{ p.providerType }}</div>
          <div>地址: {{ p.baseUrl || "默认" }}</div>
          <div>模型: {{ p.model }}</div>
          <div>模式: {{ p.apiMode }}</div>
          <div>API Key: {{ p.apiKeyConfigured ? "已配置 (..." + p.apiKeyLast4 + ")" : "未配置" }}</div>
          <div v-if="p.ownerUserId">所有者: 个人</div>
          <div v-else>所有者: 平台共享</div>
        </div>
        <div class="provider-actions">
          <button class="btn-sm" @click="openEdit(p)">编辑</button>
          <button class="btn-sm btn-danger" @click="handleDelete(p)">删除</button>
        </div>
      </div>
    </div>

    <!-- Create/Edit Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? "编辑 Provider" : "新建 Provider" }}</h2>
        <form @submit.prevent="handleSave">
          <div class="form-row">
            <div class="form-group">
              <label>名称 *</label>
              <input v-model="form.name" required placeholder="deepseek-prod" />
            </div>
            <div class="form-group">
              <label>Provider 类型 *</label>
              <select v-model="form.providerType" required>
                <option value="openai">openai</option>
                <option value="openai-compatible">openai-compatible</option>
              </select>
            </div>
          </div>
          <div class="form-group">
            <label>Base URL</label>
            <input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>模型 *</label>
              <input v-model="form.model" required placeholder="deepseek-chat" />
            </div>
            <div class="form-group">
              <label>API 模式 *</label>
              <select v-model="form.apiMode" required>
                <option value="chat_completions">chat_completions</option>
                <option value="responses">responses</option>
              </select>
            </div>
          </div>
          <div class="form-group">
            <label>API Key（创建后可编辑，只展示后四位）</label>
            <input v-model="form.apiKey" type="password" placeholder="sk-..." />
            <span v-if="editing" class="hint">留空则不修改已有的 Key</span>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Secret Ref（可选）</label>
              <input v-model="form.secretRef" placeholder="K8s Secret 名称" />
            </div>
            <div class="form-group checkbox-group">
              <label class="checkbox-label">
                <input type="checkbox" v-model="form.enabled" /> 启用
              </label>
              <label v-if="isAdmin" class="checkbox-label">
                <input type="checkbox" v-model="form.shared" /> 共享给所有用户
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
import { useAuth } from "../composables/useAuth.js";

const { isAdmin } = useAuth();
const providers = ref([]);
const loading = ref(true);
const showModal = ref(false);
const editing = ref(null);
const form = ref({});

async function load() {
  loading.value = true;
  try {
    providers.value = await api.get("/api/providers");
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = {
    name: "", providerType: "openai-compatible", baseUrl: "", model: "",
    apiMode: "chat_completions", apiKey: "", secretRef: "", enabled: true, shared: false, clearApiKey: false,
  };
  showModal.value = true;
}

function openEdit(p) {
  editing.value = p;
  form.value = {
    name: p.name, providerType: p.providerType, baseUrl: p.baseUrl || "",
    model: p.model, apiMode: p.apiMode, apiKey: "", secretRef: p.secretRef || "",
    enabled: p.enabled, shared: p.shared, clearApiKey: false,
  };
  showModal.value = true;
}

async function handleSave() {
  try {
    const body = {
      name: form.value.name,
      providerType: form.value.providerType,
      baseUrl: form.value.baseUrl || undefined,
      model: form.value.model,
      apiMode: form.value.apiMode,
      apiKey: form.value.apiKey || undefined,
      secretRef: form.value.secretRef || undefined,
      enabled: form.value.enabled,
      shared: form.value.shared,
      clearApiKey: form.value.clearApiKey,
    };
    if (editing.value) {
      await api.put(`/api/providers/${editing.value.id}`, body);
    } else {
      await api.post("/api/providers", body);
    }
    showModal.value = false;
    await load();
  } catch (e) {
    alert("保存失败: " + e.message);
  }
}

async function handleDelete(p) {
  if (!confirm(`确定删除 Provider "${p.name}"？`)) return;
  try {
    await api.delete(`/api/providers/${p.id}`);
    await load();
  } catch (e) {
    alert("删除失败: " + e.message);
  }
}

onMounted(load);
</script>

<style scoped>
.page { max-width: 1200px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-header h1 { font-size: 24px; }
.provider-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
.provider-card { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 10px; padding: 20px; }
.provider-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.provider-header h3 { font-size: 16px; }
.provider-status { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.provider-status.enabled { background: rgba(63,185,80,0.15); color: var(--success); }
.provider-status.disabled { background: rgba(110,118,129,0.15); color: var(--text-muted); }
.provider-detail { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-secondary); margin-bottom: 12px; }
.provider-actions { display: flex; gap: 8px; }
.btn-sm { padding: 4px 12px; font-size: 12px; border-radius: 4px; border: 1px solid var(--border-color); background: transparent; color: var(--text-secondary); }
.btn-danger { color: var(--danger); border-color: var(--danger); }
.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 8px 20px; font-size: 14px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.loading, .empty { text-align: center; padding: 48px; color: var(--text-secondary); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 12px; padding: 32px; width: 520px; max-width: 90vw; }
.modal h2 { margin-bottom: 20px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--text-secondary); }
.form-group input, .form-group select {
  width: 100%; padding: 8px 12px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; color: var(--text-primary); font-size: 14px;
}
.form-group input:focus, .form-group select:focus { outline: none; border-color: var(--accent); }
.checkbox-group { display: flex; flex-direction: column; gap: 8px; }
.checkbox-label { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--text-primary); cursor: pointer; }
.checkbox-label input { width: auto; }
.hint { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
