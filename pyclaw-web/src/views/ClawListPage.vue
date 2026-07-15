<template>
  <div class="claw-page">
    <div class="breadcrumb">工作台 <span>›</span> Claw 管理</div>

    <header class="hero-row">
      <div>
        <h1>Claw 管理</h1>
        <p>每个 Claw 是一个独立工作区，包含多个 Agent 角色。</p>
      </div>
      <button class="btn-primary add-button" type="button" @click="openCreate">+ 新建 Claw</button>
    </header>

    <div v-if="loading" class="loading-panel">
      <div class="skeleton title"></div>
      <div class="skeleton line"></div>
    </div>
    <div v-else-if="error" class="error-msg">{{ error }}</div>

    <template v-else>
      <section class="guide-band" aria-label="开始使用 PyClaw">
        <div class="guide-icon" aria-hidden="true">⚡</div>
        <div class="guide-copy">
          <h2>开始使用 PyClaw</h2>
          <p>Claw 是执行容器，需要添加 Agent 角色后才能开始对话。完成配置后即可在「Agent 对话」中与 Agent 交互。</p>
          <div class="guide-steps" aria-label="开始步骤">
            <span><b>1</b> 创建 Claw</span>
            <i>→</i>
            <span><b>2</b> 添加 Agent 角色</span>
            <i>→</i>
            <span><b>3</b> 开始对话</span>
          </div>
        </div>
      </section>

      <section class="metric-grid" aria-label="Claw 概览">
        <div class="metric-card accent">
          <strong>{{ claws.length }}</strong>
          <span>CLAW 总数</span>
        </div>
        <div class="metric-card success">
          <strong>{{ activeCount }}</strong>
          <span>运行中</span>
        </div>
        <div class="metric-card agent">
          <strong>{{ roleCount }}</strong>
          <span>AGENT 角色</span>
        </div>
        <div class="metric-card feishu">
          <strong>{{ feishuCount }}</strong>
          <span>飞书已绑定</span>
        </div>
      </section>

      <section v-if="claws.length" class="claw-grid" aria-label="Claw 列表">
        <article
          v-for="(claw, index) in claws"
          :key="claw.id"
          class="claw-card"
          :style="{ transitionDelay: `${index * 40}ms` }"
          @click="goDetail(claw.id)"
        >
          <div class="claw-card-top">
            <div class="claw-title-row">
              <span class="claw-icon">▣</span>
              <div>
                <h3>{{ claw.name }}</h3>
                <p>{{ claw.description || 'Claw 实例' }}</p>
              </div>
            </div>
            <span class="status-pill" :class="statusClass(claw.status)">{{ claw.status || 'active' }}</span>
          </div>

          <div class="role-summary">
            <div class="role-summary-head">
              <span>AGENT 角色</span>
              <strong>{{ claw.roles?.length || 0 }}</strong>
            </div>

            <div v-if="claw.roles?.length" class="role-list">
              <div v-for="role in claw.roles.slice(0, 3)" :key="role.id" class="role-card">
                <span class="role-dot"></span>
                <span class="role-copy">
                  <strong>{{ role.displayName || role.agentName || role.roleKey }}</strong>
                  <small>{{ role.agentName || role.roleKey }}</small>
                </span>
              </div>
              <button class="add-more-role" type="button" @click.stop="openAddRole(claw)">+ 添加更多角色</button>
            </div>

            <button v-else class="add-role-box" type="button" @click.stop="openAddRole(claw)">
              <span class="add-role-plus">＋</span>
              <strong>添加 Agent 角色</strong>
              <small>Agent 是 Claw 的执行者</small>
            </button>
          </div>

          <div class="claw-card-footer" @click.stop>
            <button class="text-button danger" type="button" @click="handleDelete(claw)">删除</button>
            <button class="text-button enter" type="button" @click="goDetail(claw.id)">进入 Claw ›</button>
          </div>
        </article>
      </section>

      <section v-else class="empty-state">
        <div class="empty-state-icon">＋</div>
        <h3>还没有 Claw</h3>
        <p>创建你的第一个 Claw，然后为它添加默认 Agent 和独立沙箱工作区。</p>
        <button class="btn-primary" type="button" @click="openCreate">+ 创建第一个 Claw</button>
      </section>
    </template>

    <div v-if="showCreate" class="modal-overlay" @click.self="closeCreate">
      <div class="modal claw-modal">
        <h2>新建 Claw</h2>
        <form @submit.prevent="handleCreate">
          <div class="form-group">
            <label>名称 *</label>
            <input v-model="createForm.name" required placeholder="我的 Claw" />
          </div>

          <div class="form-group">
            <label>描述</label>
            <textarea v-model="createForm.description" rows="3" placeholder="可选描述这个 Claw 的用途" />
          </div>

          <div class="form-group">
            <label>默认 Agent</label>
            <select v-model="createForm.defaultAgentId">
              <option value="">不选择</option>
              <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                {{ agent.name }} ({{ agent.agentKey }})
              </option>
            </select>
            <p v-if="createForm.defaultAgentId" class="field-hint">该 Agent 会自动作为新 Claw 的第一个默认角色。</p>
          </div>

          <label class="switch-line">
            <span class="switch-label">启用飞书</span>
            <input class="switch-input" type="checkbox" v-model="createForm.feishuEnabled" />
            <span class="switch-track"></span>
          </label>

          <div v-if="createForm.feishuEnabled" class="form-group">
            <label>飞书 Peer ID</label>
            <input v-model="createForm.feishuPeerId" placeholder="群聊 ID 或用户 ID" />
          </div>

          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="closeCreate">取消</button>
            <button type="submit" class="btn-primary">创建</button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="showAddRole" class="modal-overlay" @click.self="closeAddRole">
      <div class="modal role-modal">
        <h2>添加 Agent 角色</h2>
        <p class="modal-subtitle">为 <strong>{{ selectedClaw?.name }}</strong> 添加可路由的 Agent 角色。</p>
        <form @submit.prevent="handleAddRole">
          <div class="form-group">
            <label>Agent *</label>
            <select v-model="roleForm.agentId" required @change="syncSelectedAgent">
              <option value="">请选择 Agent</option>
              <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                {{ agent.name }} ({{ agent.agentKey }})
              </option>
            </select>
          </div>
          <div class="form-group">
            <label>角色 Key *</label>
            <input v-model="roleForm.roleKey" required placeholder="default / frontend / ops" />
          </div>
          <div class="form-group">
            <label>展示名称 *</label>
            <input v-model="roleForm.displayName" required placeholder="前端 Agent" />
          </div>
          <div class="form-group">
            <label>Mention Aliases</label>
            <input v-model="roleForm.mentionAliases" placeholder="前端, frontend" />
          </div>
          <div class="form-group">
            <label>Command Prefixes</label>
            <input v-model="roleForm.commandPrefixes" placeholder="/frontend, /fe" />
          </div>
          <div class="switch-grid">
            <label class="switch-line">
              <span class="switch-label">设为默认角色</span>
              <input class="switch-input" type="checkbox" v-model="roleForm.defaultRole" />
              <span class="switch-track"></span>
            </label>
            <label class="switch-line">
              <span class="switch-label">启用</span>
              <input class="switch-input" type="checkbox" v-model="roleForm.enabled" />
              <span class="switch-track"></span>
            </label>
          </div>
          <div class="modal-actions">
            <button type="button" class="btn-secondary" @click="closeAddRole">取消</button>
            <button type="submit" class="btn-primary" :disabled="!agents.length">添加</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { api } from "../api/client.js";

const router = useRouter();
const claws = ref([]);
const agents = ref([]);
const loading = ref(true);
const error = ref("");
const showCreate = ref(false);
const showAddRole = ref(false);
const selectedClaw = ref(null);
const createForm = ref(emptyCreateForm());
const roleForm = ref(emptyRoleForm());

const activeCount = computed(() => claws.value.filter(claw => (claw.status || "active") === "active").length);
const roleCount = computed(() => claws.value.reduce((sum, claw) => sum + (claw.roles?.length || 0), 0));
const feishuCount = computed(() => claws.value.filter(claw => claw.feishuEnabled).length);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [clawRows, agentRows] = await Promise.all([
      api.get("/api/claws"),
      api.get("/api/agents"),
    ]);
    claws.value = clawRows;
    agents.value = agentRows;
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

function emptyCreateForm() {
  return {
    name: "",
    description: "",
    defaultAgentId: "",
    feishuEnabled: false,
    feishuPeerId: "",
  };
}

function emptyRoleForm() {
  return {
    agentId: "",
    roleKey: "",
    displayName: "",
    mentionAliases: "",
    commandPrefixes: "",
    defaultRole: false,
    enabled: true,
  };
}

function openCreate() {
  createForm.value = emptyCreateForm();
  if (agents.value.length === 1) {
    createForm.value.defaultAgentId = agents.value[0].id;
  }
  showCreate.value = true;
}

function closeCreate() {
  showCreate.value = false;
}

function openAddRole(claw) {
  selectedClaw.value = claw;
  const roleCount = claw.roles?.length || 0;
  roleForm.value = {
    ...emptyRoleForm(),
    agentId: agents.value[0]?.id || "",
    roleKey: roleCount ? `role-${roleCount + 1}` : "default",
    defaultRole: !claw.roles?.some(role => role.defaultRole),
  };
  syncSelectedAgent();
  showAddRole.value = true;
}

function closeAddRole() {
  showAddRole.value = false;
  selectedClaw.value = null;
}

function syncSelectedAgent() {
  const agent = agents.value.find(item => item.id === roleForm.value.agentId);
  if (!agent) return;
  if (!roleForm.value.displayName) {
    roleForm.value.displayName = agent.name || agent.agentKey || "Agent 角色";
  }
}

function splitList(value) {
  return (value || "")
    .split(/[,，]/)
    .map(item => item.trim())
    .filter(Boolean);
}

function toRoleRequest(role, index) {
  return {
    id: role.id,
    agentId: role.agentId,
    roleKey: role.roleKey,
    displayName: role.displayName || role.agentName || role.roleKey,
    mentionAliases: role.mentionAliases || [],
    commandPrefixes: role.commandPrefixes || [],
    defaultRole: !!role.defaultRole,
    enabled: role.enabled !== false,
    sortOrder: role.sortOrder ?? index,
  };
}

function existingRoleRequests(claw) {
  return (claw.roles || []).map(toRoleRequest);
}

function clawUpdatePayload(claw, roles, defaultAgentId = claw.defaultAgentId) {
  return {
    name: claw.name,
    description: claw.description || undefined,
    defaultAgentId: defaultAgentId || undefined,
    feishuEnabled: claw.feishuEnabled,
    feishuPeerId: claw.feishuPeerId || undefined,
    roles,
  };
}

async function handleCreate() {
  try {
    const selectedAgent = agents.value.find(agent => agent.id === createForm.value.defaultAgentId);
    const roles = selectedAgent ? [{
      agentId: selectedAgent.id,
      roleKey: "default",
      displayName: selectedAgent.name || selectedAgent.agentKey || "Default Agent",
      mentionAliases: [],
      commandPrefixes: [],
      defaultRole: true,
      enabled: true,
      sortOrder: 0,
    }] : [];

    await api.post("/api/claws", {
      name: createForm.value.name,
      description: createForm.value.description || undefined,
      defaultAgentId: createForm.value.defaultAgentId || undefined,
      feishuEnabled: createForm.value.feishuEnabled,
      feishuPeerId: createForm.value.feishuPeerId || undefined,
      roles,
    });
    showCreate.value = false;
    createForm.value = emptyCreateForm();
    await load();
  } catch (e) {
    alert("创建失败: " + e.message);
  }
}

async function handleAddRole() {
  if (!selectedClaw.value) return;
  const selectedAgent = agents.value.find(agent => agent.id === roleForm.value.agentId);
  if (!selectedAgent) {
    alert("请先选择 Agent");
    return;
  }

  const existingRoles = existingRoleRequests(selectedClaw.value);
  const newRole = {
    agentId: selectedAgent.id,
    roleKey: roleForm.value.roleKey.trim(),
    displayName: roleForm.value.displayName.trim(),
    mentionAliases: splitList(roleForm.value.mentionAliases),
    commandPrefixes: splitList(roleForm.value.commandPrefixes),
    defaultRole: roleForm.value.defaultRole,
    enabled: roleForm.value.enabled,
    sortOrder: existingRoles.length,
  };
  const roles = roleForm.value.defaultRole
    ? existingRoles.map(role => ({ ...role, defaultRole: false })).concat(newRole)
    : existingRoles.concat(newRole);
  const defaultAgentId = roleForm.value.defaultRole ? selectedAgent.id : selectedClaw.value.defaultAgentId;

  try {
    await api.put(`/api/claws/${selectedClaw.value.id}`, clawUpdatePayload(selectedClaw.value, roles, defaultAgentId));
    closeAddRole();
    await load();
  } catch (e) {
    alert("添加角色失败: " + e.message);
  }
}

async function handleDelete(claw) {
  if (!confirm(`确定删除 Claw "${claw.name}"？此操作不可撤销。`)) return;
  try {
    await api.delete(`/api/claws/${claw.id}`);
    await load();
  } catch (e) {
    alert("删除失败: " + e.message);
  }
}

function goDetail(id) {
  router.push(`/workspace/claws/${id}`);
}

function statusClass(status) {
  return (status || "active").toLowerCase();
}

onMounted(load);
</script>

<style scoped>
.claw-page {
  width: 100%;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 28px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.breadcrumb span,
.breadcrumb::first-letter {
  color: var(--accent);
}

.hero-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.hero-row h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
  letter-spacing: 0;
}

.hero-row p {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.add-button {
  min-width: 112px;
}

.guide-band {
  min-height: 96px;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  align-items: start;
  gap: 12px;
  margin-bottom: 22px;
  padding: 17px 42px 18px 18px;
  border: 1px solid rgba(198, 123, 16, 0.58);
  border-radius: 12px;
  background: linear-gradient(180deg, rgba(34, 24, 12, 0.86), rgba(25, 18, 10, 0.82));
  box-shadow: inset 0 1px 0 rgba(255, 181, 65, 0.06);
}

.guide-icon {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  margin-top: 1px;
  color: #ffb000;
  font-size: 14px;
  line-height: 1;
}

.guide-copy h2 {
  margin: 0 0 4px;
  color: #ffb000;
  font-size: 14px;
  line-height: 1.25;
  font-weight: 800;
  letter-spacing: 0;
}

.guide-copy p {
  margin: 0;
  color: #c7a43b;
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
}

.guide-steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 11px;
  color: #ffb000;
  font-size: 12px;
  line-height: 1;
  font-weight: 700;
}

.guide-steps span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.guide-steps b {
  width: 14px;
  height: 14px;
  display: inline-grid;
  place-items: center;
  border: 1px solid rgba(255, 176, 0, 0.75);
  border-radius: 999px;
  color: #ffb000;
  font-size: 9px;
  line-height: 1;
  font-weight: 800;
}

.guide-steps i {
  color: #9a6a17;
  font-style: normal;
  font-size: 12px;
  line-height: 1;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}

.metric-card {
  min-height: 76px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(17, 22, 29, 0.86);
}

.metric-card strong {
  display: block;
  font-size: 27px;
  line-height: 1;
  font-weight: 900;
  letter-spacing: 0;
}

.metric-card span {
  display: block;
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}

.metric-card.accent strong { color: var(--accent); }
.metric-card.success strong { color: var(--success); }
.metric-card.agent strong { color: #7287ff; }
.metric-card.feishu strong { color: #58a6ff; }

.claw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}

.claw-card {
  min-height: 238px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(17, 22, 29, 0.9);
  cursor: pointer;
  transition: border-color 0.2s var(--ease-out);
}

.claw-card:hover {
  border-color: var(--border-light);
}

.claw-card-top {
  padding: 16px 16px 12px;
  display: flex;
  justify-content: space-between;
  gap: 14px;
}

.claw-title-row {
  min-width: 0;
  display: flex;
  gap: 10px;
}

.claw-icon {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  color: var(--accent);
  background: var(--accent-glow);
}

.claw-title-row h3 {
  margin: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.claw-title-row p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-pill {
  align-self: flex-start;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--success);
  background: rgba(63, 185, 80, 0.13);
  font-size: 11px;
  font-weight: 800;
}

.status-pill.inactive,
.status-pill.disabled {
  color: var(--danger);
  background: rgba(248, 81, 73, 0.12);
}

.role-summary {
  padding: 0 16px 12px;
  flex: 1;
}

.role-summary-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--border);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
}

.role-list {
  display: grid;
  gap: 8px;
}

.role-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 46px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
}

.role-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: var(--success);
}

.role-copy {
  min-width: 0;
  display: grid;
  line-height: 1.25;
}

.role-copy strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-copy small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add-more-role {
  width: fit-content;
  margin-top: 2px;
  padding: 2px 0;
  border: 0;
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
}

.add-more-role:hover {
  color: var(--accent);
}

.add-role-box {
  width: 100%;
  min-height: 96px;
  display: grid;
  place-items: center;
  gap: 3px;
  border: 1px dashed rgba(92, 104, 120, 0.42);
  border-radius: 8px;
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.015);
  text-align: center;
  transition: border-color 0.18s var(--ease-out), background 0.18s var(--ease-out), color 0.18s var(--ease-out);
}

.add-role-box:hover {
  border-color: rgba(240, 163, 58, 0.75);
  color: var(--accent);
  background: rgba(240, 163, 58, 0.08);
}

.add-role-plus {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: rgba(92, 104, 120, 0.16);
  color: var(--text-muted);
  font-weight: 900;
  transition: background 0.18s var(--ease-out), color 0.18s var(--ease-out);
}

.add-role-box:hover .add-role-plus {
  background: rgba(240, 163, 58, 0.16);
  color: var(--accent);
}

.add-role-box strong {
  color: currentColor;
  font-size: 12px;
  transition: color 0.18s var(--ease-out);
}

.add-role-box small {
  color: var(--text-muted);
  font-size: 11px;
  transition: color 0.18s var(--ease-out);
}

.add-role-box:hover small {
  color: #b39056;
}

.claw-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border-top: 1px solid var(--border);
}

.text-button {
  padding: 4px 0;
  border: 0;
  background: transparent;
  font-size: 12px;
  font-weight: 800;
}

.text-button.danger { color: var(--text-muted); }
.text-button.danger:hover { color: var(--danger); }
.text-button.enter { color: var(--accent); }
.text-button.enter:hover { color: var(--accent-soft); }

.loading-panel {
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-surface);
}

.loading-panel .title { width: 180px; height: 22px; margin-bottom: 16px; }
.loading-panel .line { width: 70%; height: 14px; }

.claw-modal,
.role-modal {
  width: 480px;
}

.modal-subtitle {
  margin: -10px 0 20px;
  color: var(--text-muted);
  font-size: 12px;
}

.modal-subtitle strong {
  color: var(--accent);
}

.field-hint {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 12px;
}

.switch-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.switch-grid .switch-line {
  margin: 0;
}

.empty-state {
  margin-top: 24px;
  padding: 58px 24px;
  border: 1px dashed var(--border-light);
  border-radius: 8px;
  background: rgba(17, 22, 29, 0.62);
}

@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .hero-row {
    display: grid;
  }
  .add-button {
    width: 100%;
  }
  .guide-band {
    grid-template-columns: 1fr;
  }
  .metric-grid,
  .claw-grid,
  .switch-grid {
    grid-template-columns: 1fr;
  }
}
</style>