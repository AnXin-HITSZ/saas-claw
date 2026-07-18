<template>
  <div class="claw-page">
    <PageHeader title="Claw 管理" subtitle="每个 Claw 是一个独立工作区，包含多个 Agent 角色。">
      <template #actions>
        <AppButton variant="primary" @click="openCreate">+ 新建 Claw</AppButton>
      </template>
    </PageHeader>

    <div v-if="loading" class="claw-skeletons">
      <div class="stat-row">
        <AppSkeleton v-for="i in 4" :key="i" variant="rect" :height="86" />
      </div>
      <div class="claw-grid">
        <AppSkeleton v-for="i in 3" :key="i" variant="rect" :height="260" />
      </div>
    </div>
    <div v-else-if="error" class="error-panel">
      <p class="error-msg">{{ error }}</p>
      <AppButton variant="ghost" @click="load">重试</AppButton>
    </div>

    <template v-else>
      <section class="guide-band" aria-label="开始使用 PyClaw">
        <div class="guide-icon" aria-hidden="true">⚡</div>
        <div class="guide-copy">
          <h2>开始使用 PyClaw</h2>
          <p>Claw 是执行容器，需要添加 Agent 角色后才能开始对话。完成配置后即可进入对应 Claw 的对话页面。</p>
          <div class="guide-steps" aria-label="开始步骤">
            <span><b>1</b> 创建 Claw</span>
            <i>→</i>
            <span><b>2</b> 添加 Agent 角色</span>
            <i>→</i>
            <span><b>3</b> 开始对话</span>
          </div>
        </div>
      </section>

      <section class="stat-row" aria-label="Claw 概览">
        <div class="stat-card accent">
          <div class="stat-value">{{ claws.length }}</div>
          <div class="stat-label">CLAW 总数</div>
        </div>
        <div class="stat-card success">
          <div class="stat-value">{{ activeCount }}</div>
          <div class="stat-label">运行中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value agent-value">{{ roleCount }}</div>
          <div class="stat-label">AGENT 角色</div>
        </div>
        <div class="stat-card">
          <div class="stat-value feishu-value">{{ feishuCount }}</div>
          <div class="stat-label">飞书已绑定</div>
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
            <AppTag :tone="statusTone(claw.status)" :pulse="(claw.status || 'active') === 'active'">
              {{ claw.status || 'active' }}
            </AppTag>
          </div>

          <div class="role-summary">
            <div class="role-summary-head">
              <span>AGENT 角色</span>
              <strong>{{ claw.roles?.length || 0 }}</strong>
            </div>

            <div v-if="claw.roles?.length" class="role-list">
              <div v-for="role in claw.roles.slice(0, 3)" :key="role.id || `${role.agentId}-${role.roleKey}`" class="role-card">
                <span class="role-dot"></span>
                <span class="role-copy">
                  <strong>{{ role.displayName || role.agentName || role.roleKey }}</strong>
                  <small>{{ role.agentName || role.roleKey }}</small>
                </span>
                <button
                  class="role-delete"
                  type="button"
                  :disabled="deletingId === roleDeleteKey(claw, role)"
                  :title="deletingId === roleDeleteKey(claw, role) ? '删除中...' : '删除角色'"
                  aria-label="删除角色"
                  @click.stop="handleDeleteRole(claw, role)"
                >
                  {{ deletingId === roleDeleteKey(claw, role) ? '删除中...' : '删除' }}
                </button>
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
            <AppButton
              variant="danger"
              :loading="deletingId === claw.id"
              loading-text="删除中..."
              @click="handleDelete(claw)"
            >
              删除
            </AppButton>
            <button class="text-button enter" type="button" @click="goDetail(claw.id)">进入 Claw ›</button>
          </div>
        </article>
      </section>

      <section v-else class="empty-state-wrap">
        <AppEmpty icon="＋" title="还没有 Claw" description="创建你的第一个 Claw，然后为它添加默认 Agent 和独立沙箱工作区。">
          <AppButton variant="primary" @click="openCreate">+ 创建第一个 Claw</AppButton>
        </AppEmpty>
      </section>
    </template>

    <AppModal :show="showCreate" title="新建 Claw" @close="closeCreate">
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
          <AppButton variant="ghost" type="button" @click="closeCreate">取消</AppButton>
          <AppButton variant="primary" type="submit" :loading="submitting" loading-text="创建中...">创建</AppButton>
        </div>
      </form>
    </AppModal>

    <AppModal :show="showAddRole" title="添加 Agent 角色" @close="closeAddRole">
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
          <AppButton variant="primary" type="submit" :loading="submitting" :disabled="!agents.length" loading-text="添加中...">添加</AppButton>
        </div>
      </form>
    </AppModal>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { api } from "../api/client.js";
import { useToast } from "../composables/useToast.js";
import AppButton from "../components/ui/AppButton.vue";
import AppSkeleton from "../components/ui/AppSkeleton.vue";
import AppTag from "../components/ui/AppTag.vue";
import AppEmpty from "../components/ui/AppEmpty.vue";
import AppModal from "../components/ui/AppModal.vue";
import PageHeader from "../components/ui/PageHeader.vue";

const { toast } = useToast();
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
const submitting = ref(false);
const deletingId = ref(null);

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

function defaultAgentIdFromRoles(roles, fallback) {
  return roles.find(role => role.defaultRole)?.agentId || fallback;
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

function statusTone(status) {
  const value = (status || "active").toLowerCase();
  if (value === "active") return "success";
  if (value === "inactive" || value === "disabled") return "danger";
  return "neutral";
}

function roleDeleteKey(claw, role) {
  return `${claw.id}:${role.id || role.roleKey}`;
}

async function handleCreate() {
  if (submitting.value) return;
  submitting.value = true;
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
    toast.success("已创建");
    await load();
  } catch (e) {
    toast.error("创建失败: " + e.message);
  } finally {
    submitting.value = false;
  }
}

async function handleAddRole() {
  if (!selectedClaw.value) return;
  if (submitting.value) return;
  const selectedAgent = agents.value.find(agent => agent.id === roleForm.value.agentId);
  if (!selectedAgent) {
    toast.error("请先选择 Agent");
    return;
  }

  submitting.value = true;
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
    toast.success("已添加角色");
    await load();
  } catch (e) {
    toast.error("添加角色失败: " + e.message);
  } finally {
    submitting.value = false;
  }
}

async function handleDeleteRole(claw, role) {
  const roleName = role.displayName || role.agentName || role.roleKey;
  if (!confirm(`确定删除角色 "${roleName}"？`)) return;
  if (deletingId.value) return;

  const key = roleDeleteKey(claw, role);
  deletingId.value = key;
  const roles = ensureDefaultRole(existingRoleRequests(claw).filter(item => !roleMatches(item, role)));
  const defaultAgentId = defaultAgentIdFromRoles(roles, roles.length ? roles[0].agentId : claw.defaultAgentId);

  try {
    await api.put(`/api/claws/${claw.id}`, clawUpdatePayload(claw, roles, defaultAgentId));
    toast.success("已删除");
    await load();
  } catch (e) {
    toast.error("删除角色失败: " + e.message);
  } finally {
    if (deletingId.value === key) deletingId.value = null;
  }
}

async function handleDelete(claw) {
  if (!confirm(`确定删除 Claw "${claw.name}"？此操作不可撤销。`)) return;
  if (deletingId.value) return;
  deletingId.value = claw.id;
  try {
    await api.delete(`/api/claws/${claw.id}`);
    toast.success("已删除");
    await load();
  } catch (e) {
    toast.error("删除失败: " + e.message);
  } finally {
    if (deletingId.value === claw.id) deletingId.value = null;
  }
}

function goDetail(id) {
  router.push(`/workspace/claws/${id}`);
}

onMounted(load);
</script>

<style scoped>
.claw-page {
  width: 100%;
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
  border-left: 3px solid rgba(255, 176, 0, 0.85);
  border-radius: 12px;
  background: linear-gradient(180deg, rgba(34, 24, 12, 0.86), rgba(25, 18, 10, 0.82));
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
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

.agent-value { color: var(--accent-3); }
.feishu-value { color: var(--accent-2); }

.claw-skeletons {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.claw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 18px;
}

.claw-card {
  min-height: 238px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 40%), var(--bg-surface);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: border-color 0.22s var(--ease-out), transform 0.22s var(--ease-out), box-shadow 0.22s var(--ease-out);
}

.claw-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 14px;
  right: 14px;
  height: 1px;
  background: var(--gradient-aurora);
  opacity: 0;
  transition: opacity 0.3s var(--ease-out);
}

.claw-card:hover {
  border-color: var(--border-light);
  transform: translateY(-2px);
  box-shadow: var(--shadow), 0 0 24px rgba(245, 168, 61, 0.08);
}

.claw-card:hover::before {
  opacity: 0.7;
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
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  transition: background 0.18s var(--ease-out), border-color 0.18s var(--ease-out);
}

.role-card:hover {
  background: var(--bg-hover);
  border-color: var(--border-light);
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
  flex: 1;
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

.role-delete {
  flex: 0 0 auto;
  padding: 3px 6px;
  border: 0;
  border-radius: 4px;
  color: var(--text-muted);
  background: transparent;
  font-size: 11px;
  font-weight: 700;
  opacity: 0.72;
  cursor: pointer;
  transition: color 0.18s var(--ease-out), background 0.18s var(--ease-out), opacity 0.18s var(--ease-out);
}

.role-delete:hover:not(:disabled) {
  color: var(--danger);
  background: rgba(248, 81, 73, 0.1);
  opacity: 1;
}

.role-delete:disabled {
  cursor: not-allowed;
  opacity: 0.5;
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
  cursor: pointer;
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
  cursor: pointer;
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
  gap: 10px;
  padding: 10px 16px;
  border-top: 1px solid var(--border);
}

.text-button {
  padding: 4px 0;
  border: 0;
  background: transparent;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.text-button.enter { color: var(--accent); }
.text-button.enter:hover { color: var(--accent-soft); }

.error-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 48px 24px;
}

.error-panel .error-msg {
  margin: 0;
}

.empty-state-wrap {
  margin-top: 24px;
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

.switch-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.switch-grid .switch-line {
  margin: 0;
}

@media (max-width: 640px) {
  .claw-grid,
  .switch-grid {
    grid-template-columns: 1fr;
  }
}
</style>
