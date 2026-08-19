<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { modelConfigApi, ApiError } from '@/api'
import type { ModelConfig } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppSelect, { type SelectOption } from '@/components/ui/AppSelect.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const list = ref<ModelConfig[]>([])
const loading = ref(false)

const showEdit = ref(false)
const editing = ref<ModelConfig | null>(null)
const form = reactive({
  name: '',
  provider: '',
  model_name: '',
  endpoint: '',
  api_key: '',
  status: 1 as number | null,
})
const submitting = ref(false)

const removing = ref<ModelConfig | null>(null)
const removingLoading = ref(false)

const statusOptions: SelectOption[] = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
]

async function load() {
  loading.value = true
  try {
    list.value = await modelConfigApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    provider: '',
    model_name: '',
    endpoint: '',
    api_key: '',
    status: 1,
  })
  showEdit.value = true
}
function openEdit(m: ModelConfig) {
  editing.value = m
  Object.assign(form, {
    name: m.name,
    provider: m.provider,
    model_name: m.model_name,
    endpoint: m.endpoint,
    api_key: '', // 明文不回显，留空表示不修改
    status: m.status,
  })
  showEdit.value = true
}

async function save() {
  if (!editing.value && !form.name.trim()) return toast.error('请输入名称')
  submitting.value = true
  try {
    if (editing.value) {
      await modelConfigApi.update(editing.value.id, {
        endpoint: form.endpoint,
        api_key: form.api_key || undefined, // 空则不改
        status: form.status ?? 1,
      })
      toast.success('已更新')
    } else {
      if (!form.name.trim() || !form.provider.trim() || !form.model_name.trim() || !form.endpoint.trim())
        return toast.error('请填写完整')
      if (!form.api_key.trim()) return toast.error('请输入 API Key')
      await modelConfigApi.create({
        name: form.name.trim(),
        provider: form.provider.trim(),
        model_name: form.model_name.trim(),
        endpoint: form.endpoint.trim(),
        api_key: form.api_key.trim(),
      })
      toast.success('已创建')
    }
    showEdit.value = false
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function confirmRemove() {
  if (!removing.value) return
  removingLoading.value = true
  try {
    await modelConfigApi.remove(removing.value.id)
    toast.success('已删除')
    removing.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    removingLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="模型配置" subtitle="平台级模型供应商配置，供所有 Agent 的基础模型引用。API Key 明文不回显。">
      <template #actions>
        <AppButton @click="openCreate">新增配置</AppButton>
      </template>
    </PageHeader>

    <!-- 骨架 -->
    <div v-if="loading" class="grid">
      <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="140px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="◈"
      title="暂无模型配置"
      description="添加模型供应商后，Agent 才能选择对应基础模型进行推理。"
    >
      <AppButton @click="openCreate">新增配置</AppButton>
    </AppEmpty>

    <!-- 卡片网格 -->
    <div v-else class="model-grid">
      <TransitionGroup name="stagger" tag="div" class="model-grid">
        <div
          v-for="(m, i) in list"
          :key="m.id"
          class="model-card card"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="card-top">
            <span class="card-icon">◈</span>
            <div class="card-title">
              <h3>{{ m.name }}</h3>
              <span class="meta-line">{{ m.provider }} · <span class="mono">{{ m.model_name }}</span></span>
            </div>
            <AppTag :tone="m.status === 1 ? 'success' : 'neutral'" pulse>
              {{ m.status === 1 ? '启用' : '停用' }}
            </AppTag>
          </div>

          <div class="endpoint">
            <span class="endpoint-label">Endpoint</span>
            <span class="mono endpoint-url">{{ m.endpoint }}</span>
          </div>

          <div class="card-meta">
            <span class="meta-item">ID <span class="mono">#{{ m.id }}</span></span>
            <span class="meta-item">更新 <span class="mono">{{ m.updated_at?.replace('T', ' ').slice(0, 16) ?? '—' }}</span></span>
          </div>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="openEdit(m)">编辑</AppButton>
            <AppButton variant="danger" size="sm" @click="removing = m">删除</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- 编辑/新增弹窗 -->
    <AppModal :show="showEdit" :title="editing ? '编辑模型配置' : '新增模型配置'" width="540px" @close="showEdit = false">
      <template v-if="!editing">
        <div class="form-item">
          <label>名称</label>
          <input v-model="form.name" class="input" placeholder="如 deepseek-official" />
        </div>
        <div class="form-item">
          <label>供应商</label>
          <input v-model="form.provider" class="input" placeholder="如 deepseek / openai" />
        </div>
        <div class="form-item">
          <label>模型名</label>
          <input v-model="form.model_name" class="input mono" placeholder="如 deepseek-chat" />
        </div>
      </template>
      <div class="form-item">
        <label>Endpoint</label>
        <input v-model="form.endpoint" class="input mono" placeholder="https://api.xxx.com/v1" />
      </div>
      <div class="form-item">
        <label>API Key {{ editing ? '（留空则不修改）' : '' }}</label>
        <input v-model="form.api_key" class="input mono" type="password" placeholder="sk-..." />
      </div>
      <div class="form-item" v-if="editing">
        <label>状态</label>
        <AppSelect v-model="form.status" :options="statusOptions" placeholder="选择状态" />
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showEdit = false">取消</AppButton>
        <AppButton :loading="submitting" @click="save">{{ submitting ? '' : '保存' }}</AppButton>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除模型配置"
      :message="`删除模型配置「${removing?.name}」？依赖它的 Agent 将无法推理。`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />
  </div>
</template>

<style scoped>
.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}
.card-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--gradient-aurora);
  color: #0a0e14;
  font-size: 16px;
  flex-shrink: 0;
}
.card-title {
  flex: 1;
  min-width: 0;
}
.card-title h3 {
  margin: 0 0 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta-line {
  font-size: 12px;
  color: var(--text-muted);
}
.endpoint {
  margin: 12px 0;
  padding: 9px 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: 3px;
  word-break: break-all;
}
.endpoint-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
}
.endpoint-url {
  font-size: 12px;
  color: var(--text-secondary);
}
.card-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-muted);
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>