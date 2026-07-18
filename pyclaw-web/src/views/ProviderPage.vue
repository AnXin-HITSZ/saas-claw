<template>
  <div class="provider-page">
    <PageHeader title="Provider 管理" subtitle="Provider 是 AI 模型的接入点，配置后可被 Agent 和 Claw 使用。">
      <template #actions>
        <AppButton variant="primary" @click="openCreate">+ 新建 Provider</AppButton>
      </template>
    </PageHeader>

    <div v-if="loading" class="provider-grid">
      <div v-for="i in 6" :key="i" class="card provider-card skeleton-card">
        <AppSkeleton variant="text" :width="'55%'" :height="18" />
        <AppSkeleton variant="text" :width="'40%'" :height="12" />
        <AppSkeleton variant="text" :width="'80%'" :height="12" />
        <AppSkeleton variant="text" :width="'65%'" :height="12" />
      </div>
    </div>
    <div v-else-if="providers.length === 0" class="empty-state-wrap">
      <AppEmpty icon="⚡" title="还没有 Provider 配置" description="Provider 是 AI 模型的接入点，配置后可被 Agent 和 Claw 使用。">
        <AppButton variant="primary" @click="openCreate">+ 添加第一个 Provider</AppButton>
      </AppEmpty>
    </div>
    <div v-else class="provider-grid">
      <article
        v-for="(p, index) in providers"
        :key="p.id"
        class="card provider-card"
        :style="{ transitionDelay: `${index * 40}ms` }"
      >
        <div class="provider-header">
          <h3>{{ p.name }}</h3>
          <div class="provider-tags">
            <AppTag :tone="p.enabled ? 'success' : 'neutral'">{{ p.enabled ? "启用" : "停用" }}</AppTag>
            <AppTag tone="info">{{ p.providerType }}</AppTag>
          </div>
        </div>
        <div class="provider-detail">
          <div class="detail-row"><span class="detail-label">地址</span><span class="detail-value">{{ p.baseUrl || "默认" }}</span></div>
          <div class="detail-row"><span class="detail-label">模型</span><span class="detail-value mono">{{ p.model }}</span></div>
          <div class="detail-row"><span class="detail-label">模式</span><span class="detail-value mono">{{ p.apiMode }}</span></div>
          <div class="detail-row"><span class="detail-label">API Key</span><span class="detail-value">{{ p.apiKeyConfigured ? "已配置 (..." + p.apiKeyLast4 + ")" : "未配置" }}</span></div>
          <div class="detail-row"><span class="detail-label">所有者</span><span class="detail-value">{{ p.ownerUserId ? "个人" : "平台共享" }}</span></div>
        </div>
        <div class="provider-actions">
          <AppButton variant="ghost" @click="openEdit(p)">编辑</AppButton>
          <AppButton
            variant="danger"
            :loading="deletingId === p.id"
            loading-text="删除中..."
            @click="handleDelete(p)"
          >删除</AppButton>
        </div>
      </article>
    </div>

    <!-- Create/Edit Modal -->
    <AppModal :show="showModal" :title="editing ? '编辑 Provider' : '新建 Provider'" @close="showModal = false">
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
import { useAuth } from "../composables/useAuth.js";
import { useToast } from "../composables/useToast.js";
import AppButton from "../components/ui/AppButton.vue";
import AppSkeleton from "../components/ui/AppSkeleton.vue";
import AppTag from "../components/ui/AppTag.vue";
import AppEmpty from "../components/ui/AppEmpty.vue";
import AppModal from "../components/ui/AppModal.vue";
import PageHeader from "../components/ui/PageHeader.vue";

const { isAdmin } = useAuth();
const { toast } = useToast();
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
  if (submitting.value) return;
  submitting.value = true;
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
    toast.success("已保存");
    await load();
  } catch (e) {
    toast.error("保存失败: " + e.message);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(p) {
  if (!confirm(`确定删除 Provider "${p.name}"？`)) return;
  if (deletingId.value) return;
  deletingId.value = p.id;
  try {
    await api.delete(`/api/providers/${p.id}`);
    toast.success("已删除");
    await load();
  } catch (e) {
    toast.error("删除失败: " + e.message);
  } finally {
    if (deletingId.value === p.id) deletingId.value = null;
  }
}

onMounted(load);
</script>

<style scoped>
.provider-page { max-width: 1200px; }
.provider-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
.provider-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  animation: card-in 0.4s var(--ease-out) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.skeleton-card { gap: 10px; padding: 22px; }
.provider-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.provider-header h3 { font-size: 16px; margin: 0; }
.provider-tags { display: inline-flex; gap: 6px; flex-wrap: wrap; }
.provider-detail { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-secondary); }
.detail-row { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
.detail-label { color: var(--text-muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; flex: 0 0 auto; }
.detail-value { text-align: right; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-value.mono { font-family: var(--font-mono); font-size: 12px; color: var(--accent); }
.provider-actions { display: flex; gap: 8px; margin-top: auto; }
.empty-state-wrap { margin-top: 24px; }

.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.checkbox-group { display: flex; flex-direction: column; gap: 8px; }
.checkbox-label { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--text-primary); cursor: pointer; }
.checkbox-label input { width: auto; }
.hint { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

@media (max-width: 640px) {
  .provider-grid { grid-template-columns: 1fr; }
  .form-row { grid-template-columns: 1fr; }
}
</style>
