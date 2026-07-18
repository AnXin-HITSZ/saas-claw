<template>
  <div class="page">
    <div class="page-header">
      <h1>Agent 市场</h1>
      <div class="header-actions">
        <input v-model="query" class="search-input" placeholder="搜索 Agent..." @input="filter" />
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="filtered.length === 0" class="empty-state">
      <div class="empty-state-icon">📦</div>
      <h3>{{ query ? '没有匹配的 Agent' : '市场还没有 Agent' }}</h3>
      <p>{{ query ? '尝试其他关键字。' : '在 Agent 配置页发布你的第一个 Agent。' }}</p>
      <button class="btn-primary" @click="$router.push('/workspace/agents')">前往 Agent 配置</button>
    </div>
    <div v-else class="market-grid">
      <div v-for="pkg in filtered" :key="pkg.id" class="card market-card">
        <div class="market-header">
          <h3>{{ pkg.name }}</h3>
          <span class="visibility-tag">{{ pkg.visibility }}</span>
        </div>
        <p class="market-key">{{ pkg.packageKey }}</p>
        <p class="market-summary">{{ pkg.summary || '暂无简介' }}</p>
        <div class="market-detail">
          <span>安装: {{ pkg.installCount }}</span>
          <span>更新: {{ formatShort(pkg.updatedAt) }}</span>
        </div>
        <div class="market-actions">
          <button class="btn-sm" @click="openDetail(pkg)">详情</button>
          <button class="btn-sm btn-primary-sm" @click="openInstall(pkg)">安装到 Claw</button>
        </div>
      </div>
    </div>

    <!-- Detail modal -->
    <div v-if="showDetail && detailPkg" class="modal-overlay" @click.self="showDetail = false">
      <div class="modal">
        <h2>{{ detailPkg.name }}</h2>
        <div class="detail-meta">
          <span>packageKey: {{ detailPkg.packageKey }}</span>
          <span>可见性: {{ detailPkg.visibility }}</span>
          <span>安装次数: {{ detailPkg.installCount }}</span>
        </div>
        <p class="detail-desc">{{ detailPkg.description || detailPkg.summary || '暂无描述' }}</p>
        <div v-if="versions.length" class="versions-section">
          <h4>版本</h4>
          <div v-for="v in versions" :key="v.id" class="version-row">
            <span class="version-tag">{{ v.version }}</span>
            <span class="version-status">{{ v.status }}</span>
            <span>Profile: {{ v.defaultProfile }}</span>
            <span v-if="v.changelog" class="version-changelog">{{ v.changelog }}</span>
            <button class="btn-sm btn-primary-sm" @click="installVersion(v.id)">安装此版本</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showDetail = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- Install modal -->
    <div v-if="showInstall" class="modal-overlay" @click.self="showInstall = false">
      <div class="modal">
        <h2>安装 Agent 到 Claw</h2>
        <div v-if="installLoading" class="loading">安装中...</div>
        <form v-else @submit.prevent="doInstall">
          <div class="form-group">
            <label>选择 Claw *</label>
            <select v-model="installForm.clawId" required>
              <option value="">请选择</option>
              <option v-for="c in claws" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label>Role Key（可选，默认使用 packageKey）</label>
            <input v-model="installForm.roleKey" :placeholder="installTarget?.packageKey || ''" />
          </div>
          <div class="form-group">
            <label>显示名称（可选，默认使用 package name）</label>
            <input v-model="installForm.displayName" :placeholder="installTarget?.name || ''" />
          </div>
          <div class="form-group">
            <label>Local Profile（可选，默认使用 package 推荐）</label>
            <select v-model="installForm.localProfile">
              <option value="">使用 Package 默认</option>
              <option value="minimal">minimal</option>
              <option value="readonly">readonly</option>
              <option value="coding">coding</option>
              <option value="messaging">messaging</option>
              <option value="full">full</option>
            </select>
          </div>
          <p v-if="installError" class="chat-error">{{ installError }}</p>
          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="showInstall = false">取消</button>
            <button type="submit" class="btn-primary" :disabled="!installForm.clawId">确认安装</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from "vue";
import { api } from "../api/client.js";

const packages = ref([]);
const loading = ref(true);
const query = ref("");
const filtered = ref([]);

const showDetail = ref(false);
const detailPkg = ref(null);
const versions = ref([]);

const showInstall = ref(false);
const installTarget = ref(null);
const installVersionId = ref("");
const installLoading = ref(false);
const installError = ref("");
const claws = ref([]);
const installForm = ref({ clawId: "", roleKey: "", displayName: "", localProfile: "" });

async function load() {
  loading.value = true;
  try {
    packages.value = await api.get("/api/agent-packages");
    filtered.value = packages.value;
  } finally {
    loading.value = false;
  }
}

function filter() {
  const q = query.value.toLowerCase();
  filtered.value = q
    ? packages.value.filter(p => (p.name + p.packageKey + (p.summary || "")).toLowerCase().includes(q))
    : packages.value;
}

async function openDetail(pkg) {
  detailPkg.value = pkg;
  showDetail.value = true;
  try {
    versions.value = await api.get(`/api/agent-packages/${pkg.id}/versions`);
  } catch { versions.value = []; }
}

function openInstall(pkg, verId) {
  installTarget.value = pkg;
  installVersionId.value = verId || pkg.latestVersionId || "";
  installError.value = "";
  installForm.value = { clawId: "", roleKey: "", displayName: "", localProfile: "" };
  showInstall.value = true;
  loadClaws();
}

function installVersion(verId) {
  openInstall(detailPkg.value, verId);
}

async function loadClaws() {
  try { claws.value = await api.get("/api/claws"); } catch { claws.value = []; }
}

async function doInstall() {
  installLoading.value = true;
  installError.value = "";
  try {
    await api.post(`/api/claws/${installForm.value.clawId}/agents/install`, {
      packageVersionId: installVersionId.value,
      roleKey: installForm.value.roleKey || undefined,
      displayName: installForm.value.displayName || undefined,
      localProfile: installForm.value.localProfile || undefined,
    });
    showInstall.value = false;
    await api.get(`/api/agent-packages`).then(d => { packages.value = d; filter(); });
  } catch (e) {
    installError.value = e.message;
  } finally {
    installLoading.value = false;
  }
}

function formatShort(val) {
  if (!val) return "";
  return val.slice(0, 10);
}

onMounted(load);
</script>

<style scoped>
.page { max-width: 1200px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; flex-wrap: wrap; gap: 12px; }
.page-header h1 { font-size: 24px; }
.header-actions { display: flex; gap: 8px; }
.search-input {
  padding: 8px 14px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 8px; color: var(--text-primary); font-size: 14px; min-width: 240px;
}
.market-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
.market-card { cursor: default; }
.market-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.market-header h3 { font-size: 16px; }
.market-key { font-family: monospace; font-size: 12px; color: var(--accent); margin-bottom: 6px; }
.market-summary { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; min-height: 36px; }
.market-detail { display: flex; gap: 12px; font-size: 12px; color: var(--text-muted); margin-bottom: 12px; }
.market-actions { display: flex; gap: 8px; }
.visibility-tag { font-size: 10px; padding: 2px 8px; border-radius: 10px; background: rgba(107,91,255,0.12); color: var(--accent); text-transform: uppercase; }

.detail-meta { display: flex; flex-wrap: wrap; gap: 12px; font-size: 12px; color: var(--text-muted); margin-bottom: 12px; }
.detail-desc { font-size: 14px; color: var(--text-secondary); margin-bottom: 16px; }
.versions-section { margin-bottom: 16px; }
.versions-section h4 { font-size: 14px; margin-bottom: 8px; }
.version-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border-color); font-size: 13px; flex-wrap: wrap; }
.version-tag { font-family: monospace; font-size: 12px; background: var(--bg-primary); padding: 2px 8px; border-radius: 4px; }
.version-status { font-size: 11px; color: var(--success); }
.version-changelog { font-size: 12px; color: var(--text-muted); flex: 1; min-width: 120px; }

.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 8px 20px; font-size: 14px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.btn-sm { padding: 4px 12px; font-size: 12px; border-radius: 4px; border: 1px solid var(--border-color); background: transparent; color: var(--text-secondary); }
.btn-primary-sm { color: #fff; background: var(--accent); border-color: var(--accent); }
.loading, .empty-state { text-align: center; padding: 48px; color: var(--text-secondary); }
.empty-state-icon { font-size: 48px; margin-bottom: 12px; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 32px; width: 640px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h2 { margin-bottom: 20px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--text-secondary); }
.form-group input, .form-group select {
  width: 100%; padding: 8px 12px; background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; color: var(--text-primary); font-size: 14px;
}
.modal-actions { display: flex; gap: 12px; justify-content: flex-end; margin-top: 20px; }
.chat-error { color: var(--danger); font-size: 13px; margin-top: 8px; }
</style>
