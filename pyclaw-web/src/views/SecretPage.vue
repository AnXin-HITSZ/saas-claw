<template>
  <div class="page">
    <div class="page-header">
      <h1>Secret 管理</h1>
      <button class="btn-primary" @click="openCreate">新建 Secret</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="error" class="error-msg">{{ error }}</div>

    <div v-else class="secret-grid">
      <div v-for="s in secrets" :key="s.id" class="card">
        <div class="card-header">
          <h3>{{ s.name }}</h3>
          <span class="type-tag">{{ s.type }}</span>
          <span class="scope-tag">{{ s.scope === 'claw' ? 'Claw' : '用户' }}</span>
          <span v-if="!s.enabled" class="disabled-tag">已禁用</span>
        </div>
        <div class="card-body">
          <div v-if="s.kubernetesSecretName" class="k8s-ref">K8s: {{ s.kubernetesSecretName }}</div>
          <div v-if="s.clawId" class="claw-ref">Claw: {{ s.clawId }}</div>
          <div v-if="s.maskedValues" class="masked-values">
            <div v-for="(v, k) in s.maskedValues" :key="k" class="kv-pair">
              <span class="kv-key">{{ k }}</span>: <code>{{ v }}</code>
            </div>
          </div>
        </div>
        <div class="card-actions">
          <button class="btn-secondary" @click="syncSecret(s.id)">同步到 K8s</button>
          <button class="btn-secondary" @click="confirmDelete(s.id)">删除</button>
        </div>
      </div>
      <div v-if="!secrets.length" class="no-data">暂无 Secret</div>
    </div>

    <!-- Create Modal -->
    <div v-if="showCreate" class="modal-overlay" @click.self="showCreate = false">
      <div class="modal">
        <h2>新建 Secret</h2>
        <form @submit.prevent="handleCreate">
          <div class="form-group">
            <label>名称 *</label>
            <input v-model="createForm.name" required />
          </div>
          <div class="form-group">
            <label>类型</label>
            <select v-model="createForm.type">
              <option value="provider">Provider</option>
              <option value="feishu">飞书</option>
              <option value="custom">自定义</option>
            </select>
          </div>
          <div class="form-group">
            <label>范围</label>
            <select v-model="createForm.scope">
              <option value="user">用户级</option>
              <option value="claw">Claw 级</option>
            </select>
          </div>
          <div v-if="createForm.scope === 'claw'" class="form-group">
            <label>Claw *</label>
            <select v-model="createForm.clawId" required>
              <option value="">选择 Claw</option>
              <option v-for="claw in claws" :key="claw.id" :value="claw.id">{{ claw.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label>键值对 (每行: KEY=VALUE)</label>
            <textarea v-model="createForm.valuesText" rows="5" placeholder="DEEPSEEK_API_KEY=sk-..." />
          </div>
          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="showCreate = false">取消</button>
            <button type="submit" class="btn-primary">创建</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { api } from "../api/client.js";

const secrets = ref([]);
const claws = ref([]);
const loading = ref(true);
const error = ref("");
const showCreate = ref(false);
const createForm = ref({ name: "", type: "provider", scope: "user", clawId: "", valuesText: "" });

async function load() {
  loading.value = true;
  try {
    const [s, c] = await Promise.all([
      api.get("/api/secrets"),
      api.get("/api/claws"),
    ]);
    secrets.value = s || [];
    claws.value = c || [];
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  createForm.value = { name: "", type: "provider", scope: "user", clawId: "", valuesText: "" };
  showCreate.value = true;
}

async function handleCreate() {
  try {
    const values = {};
    const lines = (createForm.value.valuesText || "").split("\n").filter(l => l.trim());
    for (const line of lines) {
      const idx = line.indexOf("=");
      if (idx > 0) {
        values[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
      }
    }
    await api.post("/api/secrets", {
      name: createForm.value.name,
      type: createForm.value.type,
      scope: createForm.value.scope,
      clawId: createForm.value.clawId || undefined,
      values,
    });
    showCreate.value = false;
    await load();
  } catch (e) {
    alert("创建失败: " + e.message);
  }
}

async function syncSecret(id) {
  try {
    await api.post(`/api/secrets/${id}/sync`);
    alert("同步成功");
    await load();
  } catch (e) {
    alert("同步失败: " + e.message);
  }
}

async function confirmDelete(id) {
  if (!confirm("确定删除此 Secret?")) return;
  try {
    await api.delete(`/api/secrets/${id}`);
    await load();
  } catch (e) {
    alert("删除失败: " + e.message);
  }
}

onMounted(load);
</script>

<style scoped>
.page { max-width: 900px; }
.page-header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
.page-header h1 { font-size: 22px; flex: 1; }
.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 6px 14px; font-size: 12px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.secret-grid { display: flex; flex-direction: column; gap: 16px; }
.card { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 10px; padding: 20px; }
.card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.card-header h3 { font-size: 15px; flex: 1; }
.type-tag, .scope-tag { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: var(--bg-tertiary); color: var(--text-secondary); }
.disabled-tag { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: rgba(248,81,73,0.15); color: var(--danger); }
.card-body { font-size: 13px; }
.k8s-ref, .claw-ref { color: var(--text-muted); font-size: 12px; margin-bottom: 4px; }
.masked-values { margin-top: 8px; }
.kv-pair { margin-bottom: 2px; }
.kv-key { color: var(--text-secondary); }
.kv-pair code { font-family: monospace; font-size: 12px; color: var(--text-muted); }
.card-actions { display: flex; gap: 8px; margin-top: 12px; }
.no-data { color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px; }
.loading, .error-msg { text-align: center; padding: 48px; color: var(--text-secondary); }
.error-msg { color: var(--danger); }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 12px; padding: 32px; width: 480px; max-width: 90vw; }
.modal h2 { margin-bottom: 20px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--text-secondary); }
.form-group input, .form-group textarea, .form-group select {
  width: 100%; padding: 8px 12px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; color: var(--text-primary); font-size: 14px;
}
.form-group input:focus, .form-group textarea:focus, .form-group select:focus { outline: none; border-color: var(--accent); }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
