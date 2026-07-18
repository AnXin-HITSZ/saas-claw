<template>
  <div class="agent-page">
    <PageHeader title="Agent 配置" subtitle="Agent 定义了 Claw 的行为方式——模型、系统提示词、工具策略和审批模式。">
      <template #actions>
        <AppButton variant="primary" @click="openCreate">+ 新建 Agent</AppButton>
      </template>
    </PageHeader>

    <div v-if="loading" class="agent-grid">
      <div v-for="i in 6" :key="i" class="card agent-card skeleton-card">
        <AppSkeleton variant="text" :width="'60%'" :height="18" />
        <AppSkeleton variant="text" :width="'40%'" :height="12" />
        <AppSkeleton variant="text" :width="'90%'" :height="12" />
        <AppSkeleton variant="text" :width="'70%'" :height="12" />
      </div>
    </div>
    <div v-else-if="agents.length === 0" class="empty-state-wrap">
      <AppEmpty icon="🤖" title="还没有 Agent 配置" description="Agent 定义了 Claw 的行为方式——模型、系统提示词、工具策略和审批模式。">
        <AppButton variant="primary" @click="openCreate">+ 创建第一个 Agent</AppButton>
      </AppEmpty>
    </div>
    <div v-else class="agent-grid">
      <article
        v-for="(agent, index) in agents"
        :key="agent.id"
        class="card agent-card"
        :style="{ transitionDelay: `${index * 40}ms` }"
      >
        <div class="agent-header">
          <h3>{{ agent.name }}</h3>
          <AppTag :tone="agent.enabled ? 'success' : 'neutral'">
            {{ agent.enabled ? "启用" : "停用" }}
          </AppTag>
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
          <AppButton variant="ghost" @click="openEdit(agent)">编辑</AppButton>
          <AppButton
            variant="danger"
            :loading="deletingId === agent.id"
            loading-text="删除中..."
            @click="handleDelete(agent)"
          >删除</AppButton>
        </div>
      </article>
    </div>

    <!-- Create/Edit Modal -->
    <AppModal :show="showModal" :title="editing ? '编辑 Agent' : '新建 Agent'" @close="showModal = false">
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
            <AppSelect
              v-model="form.providerId"
              :options="[{value:'',label:'默认'}, ...providers.map(p => ({value:p.id, label:p.name + ' (' + p.model + ')'}))]"
            />
          </div>
          <div class="form-group">
            <label>Model（可选）</label>
            <input v-model="form.model" placeholder="如 deepseek-chat" />
            <p class="field-hint">留空则用 Provider 默认模型；填写则覆盖。</p>
          </div>
        </div>
        <div class="form-group">
          <label>System Prompt</label>
          <textarea v-model="form.systemPrompt" rows="4" placeholder="给 Agent 的系统提示词..." />
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>Tool Profile</label>
            <AppSelect
              v-model="form.toolProfile"
              :options="[{value:'minimal',label:'minimal'},{value:'readonly',label:'readonly'},{value:'coding',label:'coding'},{value:'messaging',label:'messaging'},{value:'full',label:'full'}]"
            />
          </div>
          <div class="form-group">
            <label>工作目录</label>
            <input v-model="form.workspaceDir" placeholder="/workspace" />
          </div>
        </div>
        <div class="form-group switch-field">
          <label class="switch-line">
            <span class="switch-label">启用</span>
            <input class="switch-input" type="checkbox" v-model="form.enabled" />
            <span class="switch-track"></span>
          </label>
        </div>
        <div class="modal-actions">
          <AppButton variant="ghost" type="button" @click="showModal = false">取消</AppButton>
          <AppButton variant="primary" type="submit" :loading="submitting" loading-text="保存中...">保存</AppButton>
        </div>
      </form>
    </AppModal>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { api } from "../api/client.js";
import { useToast } from "../composables/useToast.js";
import AppButton from "../components/ui/AppButton.vue";
import AppSkeleton from "../components/ui/AppSkeleton.vue";
import AppTag from "../components/ui/AppTag.vue";
import AppEmpty from "../components/ui/AppEmpty.vue";
import AppModal from "../components/ui/AppModal.vue";
import AppSelect from "../components/ui/AppSelect.vue";
import PageHeader from "../components/ui/PageHeader.vue";

const { toast } = useToast();
const agents = ref([]);
const providers = ref([]);
const loading = ref(true);
const showModal = ref(false);
const editing = ref(null);
const form = ref({});
const submitting = ref(false);
const deletingId = ref(null);

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
  if (submitting.value) return;
  submitting.value = true;
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
    toast.success("已保存");
    await load();
  } catch (e) {
    toast.error("保存失败: " + e.message);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(agent) {
  if (!confirm(`确定删除 Agent "${agent.name}"？`)) return;
  if (deletingId.value) return;
  deletingId.value = agent.id;
  try {
    await api.delete(`/api/agents/${agent.id}`);
    toast.success("已删除");
    await load();
  } catch (e) {
    toast.error("删除失败: " + e.message);
  } finally {
    if (deletingId.value === agent.id) deletingId.value = null;
  }
}

function truncate(text, max) {
  if (!text) return "";
  return text.length > max ? text.slice(0, max) + "..." : text;
}

onMounted(load);
</script>

<style scoped>
.agent-page { max-width: 1200px; }
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 20px; }
.agent-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  animation: card-in 0.4s var(--ease-out) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.skeleton-card { gap: 10px; padding: 22px; }
.agent-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px; }
.agent-header h3 { font-size: 16px; margin: 0; }
.agent-key { font-family: var(--font-mono); font-size: 12px; color: var(--accent); margin: 0; }
.agent-desc { font-size: 13px; color: var(--text-secondary); margin: 0; }
.agent-detail { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; color: var(--text-muted); margin-top: 8px; }
.agent-prompt { font-size: 12px; color: var(--text-muted); background: var(--bg-primary); padding: 8px; border-radius: 6px; margin: 4px 0 0; font-family: var(--font-mono); }
.agent-actions { display: flex; gap: 8px; margin-top: auto; padding-top: 14px; }
.empty-state-wrap { margin-top: 24px; }

.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.field-hint { margin-top: 6px; color: var(--text-muted); font-size: 12px; }
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
.switch-field { display: flex; align-items: center; }
.switch-field .switch-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  margin: 0;
  cursor: pointer;
}

@media (max-width: 640px) {
  .agent-grid { grid-template-columns: 1fr; }
  .form-row { grid-template-columns: 1fr; }
}
</style>
