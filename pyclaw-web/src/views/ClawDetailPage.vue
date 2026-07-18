<template>
  <div class="page">
    <PageHeader :title="claw?.name || 'Claw 详情'" subtitle="查看与调整 Claw 基本信息、Agent 角色及 Sandbox 状态。">
      <template #actions>
        <AppButton variant="ghost" @click="$router.push('/workspace/claws')">← 返回列表</AppButton>
        <button
          v-if="claw?.status === 'active'"
          class="btn-chat"
          type="button"
          @click="$router.push(`/workspace/claws/${claw.id}/chat`)"
        >💬 开始对话</button>
        <AppButton variant="primary" @click="openEdit">编辑</AppButton>
      </template>
    </PageHeader>

    <div v-if="loading" class="detail-skeleton">
      <AppSkeleton variant="rect" :height="28" width="220px" />
      <div class="skeleton-grid">
        <AppSkeleton v-for="i in 2" :key="i" variant="rect" :height="180" />
      </div>
    </div>
    <div v-else-if="error" class="error-panel">
      <p class="error-msg">{{ error }}</p>
      <AppButton variant="ghost" @click="load">重试</AppButton>
    </div>

    <div v-else class="detail-grid">
      <!-- Info Card -->
      <AppCard :hoverable="false" class="info-card">
        <h3>基本信息</h3>
        <dl>
          <dt>状态</dt>
          <dd>
            <AppTag :tone="claw.status === 'inactive' || claw.status === 'disabled' ? 'danger' : 'success'" :pulse="claw.status === 'active'">
              {{ claw.status }}
            </AppTag>
          </dd>
          <dt>描述</dt><dd>{{ claw.description || "—" }}</dd>
          <dt>默认 Agent</dt><dd>{{ agentsMap[claw.defaultAgentId]?.name || "—" }}</dd>
          <dt>飞书</dt><dd>{{ claw.feishuEnabled ? `已启用 (${claw.feishuPeerId})` : "未启用" }}</dd>
          <dt>创建时间</dt><dd>{{ formatDate(claw.createdAt) }}</dd>
        </dl>
      </AppCard>

      <!-- Sandbox Card -->
      <AppCard :hoverable="false" class="info-card">
        <h3>Sandbox 状态</h3>
        <div v-if="sandboxLoading" class="no-data">查询中...</div>
        <div v-else-if="!sandboxLoading && sandboxError" class="no-data" style="color:var(--danger)">连接失败</div>
        <dl v-else>
          <dt>Health</dt>
          <dd>
            <AppTag
              :tone="sandboxHealthy ? 'success' : 'danger'"
              :pulse="sandboxHealthy"
              :title="!sandboxHealthy && sandboxError ? sandboxError : ''"
            >
              {{ sandboxHealthy ? 'Healthy' : (sandboxError ? 'Down — 悬浮查看详情' : 'Down') }}
            </AppTag>
          </dd>
          <dt>Workspace</dt><dd class="mono">{{ sandboxWorkspace || "—" }}</dd>
        </dl>
        <div style="margin-top: 14px;">
          <router-link :to="`/workspace/claws/${claw.id}/files`" class="btn-secondary" style="text-decoration:none;font-size:12px">📁 Workspace 文件</router-link>
        </div>
      </AppCard>

      <!-- Agent Roles Card -->
      <AppCard :hoverable="false" class="info-card">
        <div class="card-title-row">
          <h3>Agent 角色 ({{ claw.roles?.length || 0 }})</h3>
          <AppButton variant="ghost" @click="openAddRole">添加角色</AppButton>
        </div>
        <div v-if="claw.roles?.length" class="role-list">
          <div v-for="role in claw.roles" :key="role.id || `${role.agentId}-${role.roleKey}`" class="role-item">
            <div class="role-info">
              <span class="role-name">{{ role.displayName }}</span>
              <span class="role-key">{{ role.roleKey }}</span>
              <span class="role-agent">{{ role.agentName || role.agentId }}</span>
              <span v-if="role.defaultRole" class="badge badge-accent">默认</span>
              <span v-if="!role.enabled" class="badge badge-danger">已禁用</span>
            </div>
            <div class="role-actions">
              <button class="role-action" type="button" @click="openEditRole(role)">编辑</button>
              <button
                class="role-action danger"
                type="button"
                :disabled="deletingRoleId === (role.id || role.roleKey)"
                @click="handleDeleteRole(role)"
              >
                {{ deletingRoleId === (role.id || role.roleKey) ? '删除中...' : '删除' }}
              </button>
            </div>
          </div>
        </div>
        <p v-else class="no-data">无 Agent 角色</p>
      </AppCard>

      <!-- Sessions Card -->
      <AppCard :hoverable="false" class="info-card">
        <h3>会话记录</h3>
        <div v-if="sessions.length" class="session-list">
          <div v-for="s in sessions" :key="s.sessionId" class="session-item">
            <div class="session-info">
              <span class="session-agent">{{ s.agentKey }}</span>
              <span class="session-model">{{ s.model }}</span>
              <span class="session-count">{{ s.messageCount }} 条消息</span>
              <span class="session-time">{{ formatDate(s.lastActiveAt) }}</span>
            </div>
          </div>
        </div>
        <p v-else class="no-data">暂无会话记录</p>
      </AppCard>
    </div>

    <!-- Edit Modal -->
    <AppModal :show="showEdit" title="编辑 Claw" @close="showEdit = false">
      <form @submit.prevent="handleUpdate">
        <div class="form-group">
          <label>名称 *</label>
          <input v-model="editForm.name" required />
        </div>
        <div class="form-group">
          <label>描述</label>
          <textarea v-model="editForm.description" rows="3" />
        </div>
        <div class="form-group">
          <label>默认 Agent</label>
          <select v-model="editForm.defaultAgentId">
            <option value="">不选择</option>
            <option v-for="a in allAgents" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </div>
        <label class="switch-line">
          <span class="switch-label">启用飞书</span>
          <input class="switch-input" type="checkbox" v-model="editForm.feishuEnabled" />
          <span class="switch-track"></span>
        </label>
        <div v-if="editForm.feishuEnabled" class="form-group">
          <label>飞书 Peer ID</label>
          <input v-model="editForm.feishuPeerId" />
        </div>
        <div class="modal-actions">
          <AppButton variant="ghost" type="button" @click="showEdit = false">取消</AppButton>
          <AppButton variant="primary" type="submit" :loading="submitting" loading-text="保存中...">保存</AppButton>
        </div>
      </form>
    </AppModal>

    <!-- Add Role Modal -->
    <AppModal :show="showAddRole" :title="editingRole ? '编辑 Agent 角色' : '添加 Agent 角色'" @close="closeAddRole">
      <form @submit.prevent="handleSaveRole">
        <div class="form-group">
          <label>Agent *</label>
          <select v-model="roleForm.agentId" required @change="syncSelectedAgent">
            <option value="">请选择 Agent</option>
            <option v-for="a in allAgents" :key="a.id" :value="a.id">{{ a.name }} ({{ a.agentKey }})</option>
          </select>
        </div>
        <div class="form-group">
          <label class="label-with-help">
            <span>角色 Key *</span>
            <span
              class="help-dot"
              tabindex="0"
              role="img"
              aria-label="角色 Key 是这个 Agent 在当前 Claw 里的路由身份，例如 default、frontend 或 ops。"
              data-tooltip="当前 Claw 内的路由身份。它定义“在这个 Claw 里怎么调用该 Agent”，可与 Mention Aliases 和 Command Prefixes 配合使用。"
            >?</span>
          </label>
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
          <AppButton variant="ghost" type="button" @click="closeAddRole">取消</AppButton>
          <AppButton variant="primary" type="submit" :loading="submitting" :disabled="!allAgents.length" :loading-text="editingRole ? '保存中...' : '添加中...'">
            {{ editingRole ? "保存" : "添加" }}
          </AppButton>
        </div>
      </form>
    </AppModal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api/client.js";
import { useToast } from "../composables/useToast.js";
import AppButton from "../components/ui/AppButton.vue";
import AppSkeleton from "../components/ui/AppSkeleton.vue";
import AppTag from "../components/ui/AppTag.vue";
import AppCard from "../components/ui/AppCard.vue";
import AppModal from "../components/ui/AppModal.vue";
import PageHeader from "../components/ui/PageHeader.vue";

const { toast } = useToast();
const route = useRoute();
const claw = ref(null);
const allAgents = ref([]);
const sessions = ref([]);
const loading = ref(true);
const error = ref("");
const showEdit = ref(false);
const showAddRole = ref(false);
const editingRole = ref(null);
const editForm = ref({});
const roleForm = ref(emptyRoleForm());
const sandboxHealthy = ref(false);
const sandboxWorkspace = ref("");
const sandboxLoading = ref(true);
const sandboxError = ref("");
const submitting = ref(false);
const deletingRoleId = ref(null);

const agentsMap = computed(() => {
  const map = {};
  allAgents.value.forEach(a => { map[a.id] = a; });
  return map;
});

async function load() {
  loading.value = true;
  try {
    const [c, a, s] = await Promise.all([
      api.get(`/api/claws/${route.params.id}`),
      api.get("/api/agents"),
      api.get(`/api/sessions?clawId=${route.params.id}`).catch(() => []),
    ]);
    claw.value = c;
    allAgents.value = a;
    sessions.value = s || [];

    sandboxLoading.value = true;
    try {
      await api.get(`/api/claws/${route.params.id}/sandbox/healthz`);
      sandboxHealthy.value = true;
      sandboxError.value = "";
    } catch (e) {
      sandboxHealthy.value = false;
      sandboxError.value = e.message || "Sandbox unavailable";
      console.warn("sandbox healthz failed:", e.message);
    }
    try { const w = await api.get(`/api/claws/${route.params.id}/sandbox/workspace`); sandboxWorkspace.value = typeof w === "string" ? w : JSON.stringify(w); } catch { sandboxWorkspace.value = ""; }
    sandboxLoading.value = false;
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

function openEdit() {
  showEdit.value = true;
  editForm.value = {
    name: claw.value.name, description: claw.value.description || "",
    defaultAgentId: claw.value.defaultAgentId || "", feishuEnabled: claw.value.feishuEnabled,
    feishuPeerId: claw.value.feishuPeerId || "",
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

function openAddRole() {
  editingRole.value = null;
  const roleCount = claw.value?.roles?.length || 0;
  roleForm.value = {
    ...emptyRoleForm(),
    agentId: allAgents.value[0]?.id || "",
    roleKey: roleCount ? `role-${roleCount + 1}` : "default",
    defaultRole: !(claw.value?.roles || []).some(role => role.defaultRole),
  };
  syncSelectedAgent();
  showAddRole.value = true;
}

function openEditRole(role) {
  editingRole.value = role;
  roleForm.value = {
    agentId: role.agentId || "",
    roleKey: role.roleKey || "",
    displayName: role.displayName || role.agentName || role.roleKey || "",
    mentionAliases: joinList(role.mentionAliases),
    commandPrefixes: joinList(role.commandPrefixes),
    defaultRole: !!role.defaultRole,
    enabled: role.enabled !== false,
  };
  showAddRole.value = true;
}

function closeAddRole() {
  showAddRole.value = false;
  editingRole.value = null;
}

function syncSelectedAgent() {
  const agent = allAgents.value.find(a => a.id === roleForm.value.agentId);
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

function joinList(value) {
  return (value || []).join(", ");
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

function existingRoleRequests() {
  return (claw.value?.roles || []).map(toRoleRequest);
}

function roleMatches(item, role) {
  if (item.id && role.id) return item.id === role.id;
  return item.agentId === role.agentId && item.roleKey === role.roleKey;
}

function ensureDefaultRole(roles) {
  if (roles.length && !roles.some(role => role.defaultRole)) {
    return roles.map((role, index) => ({ ...role, defaultRole: index === 0 }));
  }
  return roles;
}

function reindexRoles(roles) {
  return roles.map((role, index) => ({ ...role, sortOrder: index }));
}

function defaultAgentIdFromRoles(roles, fallback) {
  return roles.find(role => role.defaultRole)?.agentId || fallback;
}

function baseUpdatePayload(roles, defaultAgentId = claw.value.defaultAgentId) {
  return {
    name: claw.value.name,
    description: claw.value.description || undefined,
    defaultAgentId: defaultAgentId || undefined,
    feishuEnabled: claw.value.feishuEnabled,
    feishuPeerId: claw.value.feishuPeerId || undefined,
    roles,
  };
}

async function handleUpdate() {
  if (submitting.value) return;
  submitting.value = true;
  try {
    await api.put(`/api/claws/${route.params.id}`, {
      name: editForm.value.name, description: editForm.value.description || undefined,
      defaultAgentId: editForm.value.defaultAgentId || undefined,
      feishuEnabled: editForm.value.feishuEnabled, feishuPeerId: editForm.value.feishuPeerId || undefined,
      roles: existingRoleRequests(),
    });
    showEdit.value = false;
    toast.success("已保存");
    await load();
  } catch (e) { toast.error("更新失败: " + e.message); } finally {
    submitting.value = false;
  }
}

function roleFromForm(selectedAgent, sortOrder, id) {
  return {
    id,
    agentId: selectedAgent.id,
    roleKey: roleForm.value.roleKey.trim(),
    displayName: roleForm.value.displayName.trim(),
    mentionAliases: splitList(roleForm.value.mentionAliases),
    commandPrefixes: splitList(roleForm.value.commandPrefixes),
    defaultRole: roleForm.value.defaultRole,
    enabled: roleForm.value.enabled,
    sortOrder,
  };
}

async function handleSaveRole() {
  const selectedAgent = allAgents.value.find(agent => agent.id === roleForm.value.agentId);
  if (!selectedAgent) {
    toast.error("请先选择 Agent");
    return;
  }
  if (submitting.value) return;
  submitting.value = true;

  const existingRoles = existingRoleRequests();
  let savedIndex = existingRoles.length;
  let roles;

  if (editingRole.value) {
    const targetRole = toRoleRequest(editingRole.value, 0);
    const currentIndex = existingRoles.findIndex(role => roleMatches(role, targetRole));
    savedIndex = currentIndex >= 0 ? currentIndex : existingRoles.length;
    const editedRole = roleFromForm(selectedAgent, savedIndex, targetRole.id);
    roles = currentIndex >= 0
      ? existingRoles.map((role, index) => (index === currentIndex ? editedRole : role))
      : existingRoles.concat(editedRole);
  } else {
    const newRole = roleFromForm(selectedAgent, existingRoles.length);
    roles = existingRoles.concat(newRole);
  }

  roles = roleForm.value.defaultRole
    ? roles.map((role, index) => ({ ...role, defaultRole: index === savedIndex }))
    : ensureDefaultRole(roles);
  roles = reindexRoles(roles);

  try {
    await api.put(`/api/claws/${route.params.id}`, baseUpdatePayload(roles, defaultAgentIdFromRoles(roles, claw.value.defaultAgentId)));
    closeAddRole();
    toast.success(editingRole.value ? "已保存角色" : "已添加角色");
    await load();
  } catch (e) { toast.error(`${editingRole.value ? "编辑" : "添加"}角色失败: ` + e.message); } finally {
    submitting.value = false;
  }
}

async function handleDeleteRole(role) {
  const roleName = role.displayName || role.agentName || role.roleKey;
  if (!confirm(`确定删除角色 "${roleName}"？`)) return;
  if (deletingRoleId.value) return;

  const key = role.id || role.roleKey;
  deletingRoleId.value = key;
  const roles = reindexRoles(ensureDefaultRole(existingRoleRequests().filter(item => !roleMatches(item, role))));

  try {
    await api.put(`/api/claws/${route.params.id}`, baseUpdatePayload(roles, defaultAgentIdFromRoles(roles, roles.length ? roles[0].agentId : claw.value.defaultAgentId)));
    toast.success("已删除");
    await load();
  } catch (e) { toast.error("删除角色失败: " + e.message); } finally {
    if (deletingRoleId.value === key) deletingRoleId.value = null;
  }
}

function formatDate(s) {
  if (!s) return "—";
  return new Date(s).toLocaleString("zh-CN");
}

onMounted(load);
</script>

<style scoped>
.page { max-width: 1000px; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.info-card { min-height: 0; }
.card-title-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.card-title-row h3 { margin: 0; }

.detail-skeleton { display: flex; flex-direction: column; gap: 18px; }
.skeleton-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }

.error-panel { display: flex; flex-direction: column; align-items: center; gap: 16px; padding: 48px 24px; }
.error-panel .error-msg { margin: 0; }

.label-with-help {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

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
  width: min(320px, 72vw);
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

.switch-grid { display: grid; grid-template-columns: 1fr; gap: 10px; }
.switch-grid .switch-line { margin: 0; }

dl { display: grid; grid-template-columns: auto 1fr; gap: 8px 16px; font-size: 13px; }
dt { color: var(--text-muted); font-weight: 500; }
.mono { font-family: "JetBrains Mono", "Fira Code", monospace; font-size: 12px; color: var(--text-muted); }

.role-list { display: flex; flex-direction: column; gap: 6px; }
.role-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 14px; background: var(--bg-deep); border: 1px solid transparent; border-radius: var(--radius-sm); font-size: 13px; transition: background 0.2s var(--ease-out), border-color 0.2s var(--ease-out); }
.role-item:hover { background: var(--bg-raised); border-color: var(--border-light); }
.role-info { min-width: 0; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.role-name { font-weight: 600; }
.role-key { color: var(--text-muted); font-family: monospace; font-size: 12px; }
.role-agent { color: var(--text-secondary); }
.role-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.role-action { padding: 3px 6px; border: 0; border-radius: 4px; color: var(--text-muted); background: transparent; font-size: 12px; font-weight: 700; cursor: pointer; }
.role-action:hover:not(:disabled) { color: var(--accent); background: var(--accent-glow); }
.role-action.danger:hover:not(:disabled) { color: var(--danger); background: rgba(248,81,73,0.1); }
.role-action:disabled { cursor: not-allowed; opacity: 0.5; }

.badge { font-size: 10px; padding: 1px 8px; border-radius: 8px; font-weight: 600; letter-spacing: 0.2px; }
.badge-accent { background: var(--accent-glow); color: var(--accent); }
.badge-danger { background: rgba(248,81,73,0.1); color: var(--danger); }

.session-list { display: flex; flex-direction: column; gap: 6px; }
.session-item { padding: 10px 14px; background: var(--bg-deep); border-radius: var(--radius-sm); font-size: 13px; transition: background 0.2s var(--ease-out); }
.session-item:hover { background: var(--bg-raised); }
.session-info { display: flex; gap: 16px; }
.session-agent { font-weight: 600; }
.session-model, .session-count { color: var(--text-secondary); }
.session-time { color: var(--text-muted); margin-left: auto; font-size: 12px; }

.btn-chat { padding: 9px 22px; font-size: 14px; font-weight: 600; color: #0a0e14; background: var(--success); border: none; border-radius: 10px; cursor: pointer; transition: all 0.2s var(--ease-out); }
.btn-chat:hover { filter: brightness(1.1); transform: translateY(-1px); box-shadow: 0 4px 16px rgba(63,185,80,0.25); }

@media (max-width: 640px) {
  .detail-grid,
  .skeleton-grid { grid-template-columns: 1fr; }
  .switch-grid { grid-template-columns: 1fr; }
}
</style>
