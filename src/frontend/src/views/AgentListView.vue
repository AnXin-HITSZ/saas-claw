<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { agentApi, clawApi, skillApi, ApiError } from '@/api'
import type { Agent, Claw, Skill, AgentFileVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppSelect, { type SelectOption } from '@/components/ui/AppSelect.vue'

const toast = useToast()

const claws = ref<Claw[]>([])
const agents = ref<Agent[]>([])
const allSkills = ref<Skill[]>([])
const loading = ref(false)
const filterClawId = ref<number | 'all' | null>('all')

// ---- 创建/编辑 ----
const showEdit = ref(false)
const editing = ref<Agent | null>(null)
const form = reactive({
  claw_id: null as number | null,
  alias: '',
  name: '',
  description: '',
  system_prompt: '',
  base_model: '',
  temperature: '' as number | '',
  max_tokens: '' as number | '',
})
const submitting = ref(false)

// ---- 技能绑定弹窗 ----
const showSkills = ref(false)
const skillTarget = ref<Agent | null>(null)
const boundSkills = ref<Skill[]>([])
const skillBusy = ref(false)

// ---- 人格文件弹窗 ----
const showFiles = ref(false)
const fileTarget = ref<Agent | null>(null)
const files = ref<AgentFileVO[]>([])
const uploadPath = ref('AGENTS.md')
const fileInput = ref<HTMLInputElement | null>(null)
const fileBusy = ref(false)

// ---- 删除确认 ----
const removing = ref<Agent | null>(null)
const removingLoading = ref(false)

const clawOptions = computed<SelectOption[]>(() => [
  { value: 'all', label: '全部 Claw' },
  ...claws.value.map((c) => ({ value: c.id, label: c.name })),
])

const clawName = (id: number) => claws.value.find((c) => c.id === id)?.name || `#${id}`

const filteredAgents = computed(() =>
  filterClawId.value === 'all' || filterClawId.value == null
    ? agents.value
    : agents.value.filter((a) => a.claw_id === filterClawId.value),
)

async function loadAll() {
  loading.value = true
  try {
    const [c, a, s] = await Promise.all([clawApi.list(), agentApi.list(), skillApi.list()])
    claws.value = c
    agents.value = a
    allSkills.value = s
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    claw_id: claws.value[0]?.id ?? null,
    alias: '',
    name: '',
    description: '',
    system_prompt: '',
    base_model: '',
    temperature: '',
    max_tokens: '',
  })
  showEdit.value = true
}

function openEdit(a: Agent) {
  editing.value = a
  Object.assign(form, {
    claw_id: a.claw_id,
    alias: a.alias,
    name: a.name,
    description: a.description ?? '',
    system_prompt: a.system_prompt ?? '',
    base_model: a.base_model,
    temperature: a.temperature ?? '',
    max_tokens: a.max_tokens ?? '',
  })
  showEdit.value = true
}

async function save() {
  if (!editing.value) {
    if (!form.claw_id) return toast.error('请选择 Claw')
    if (!form.alias.trim()) return toast.error('请输入 alias')
    if (!form.name.trim()) return toast.error('请输入名称')
    if (!form.base_model.trim()) return toast.error('请输入基础模型')
  }
  submitting.value = true
  try {
    if (editing.value) {
      await agentApi.update(editing.value.id, {
        name: form.name.trim(),
        description: form.description,
        system_prompt: form.system_prompt,
        base_model: form.base_model.trim(),
        temperature: form.temperature === '' ? undefined : Number(form.temperature),
        max_tokens: form.max_tokens === '' ? undefined : Number(form.max_tokens),
      })
      toast.success('已更新')
    } else {
      await agentApi.create({
        claw_id: form.claw_id!,
        alias: form.alias.trim(),
        name: form.name.trim(),
        description: form.description || undefined,
        system_prompt: form.system_prompt || undefined,
        base_model: form.base_model.trim(),
        temperature: form.temperature === '' ? undefined : Number(form.temperature),
        max_tokens: form.max_tokens === '' ? undefined : Number(form.max_tokens),
      })
      toast.success('已创建')
    }
    showEdit.value = false
    await loadAll()
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
    await agentApi.remove(removing.value.id)
    toast.success('已删除')
    removing.value = null
    await loadAll()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    removingLoading.value = false
  }
}

// ---- 技能绑定 ----
async function openSkills(a: Agent) {
  skillTarget.value = a
  showSkills.value = true
  boundSkills.value = []
  skillBusy.value = true
  try {
    boundSkills.value = await agentApi.listSkills(a.id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载技能失败')
  } finally {
    skillBusy.value = false
  }
}
const isBound = (id: number) => boundSkills.value.some((s) => s.id === id)
async function toggleSkill(s: Skill) {
  if (!skillTarget.value) return
  skillBusy.value = true
  try {
    if (isBound(s.id)) {
      await agentApi.unbindSkill(skillTarget.value.id, s.id)
    } else {
      await agentApi.bindSkill(skillTarget.value.id, { skill_id: s.id })
    }
    boundSkills.value = await agentApi.listSkills(skillTarget.value.id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    skillBusy.value = false
  }
}

// ---- 人格文件 ----
async function openFiles(a: Agent) {
  fileTarget.value = a
  showFiles.value = true
  files.value = []
  fileBusy.value = true
  try {
    files.value = await agentApi.listFiles(a.id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载文件失败')
  } finally {
    fileBusy.value = false
  }
}
async function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !fileTarget.value) return
  fileBusy.value = true
  try {
    await agentApi.uploadFile(fileTarget.value.id, file, uploadPath.value.trim() || file.name)
    toast.success('已上传')
    files.value = await agentApi.listFiles(fileTarget.value.id)
  } catch (err) {
    toast.error(err instanceof ApiError ? err.message : '上传失败')
  } finally {
    fileBusy.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
const fileDeleting = ref<AgentFileVO | null>(null)
const fileDeletingLoading = ref(false)
async function confirmDeleteFile() {
  if (!fileTarget.value || !fileDeleting.value) return
  fileDeletingLoading.value = true
  try {
    await agentApi.deleteFile(fileTarget.value.id, fileDeleting.value.id)
    files.value = await agentApi.listFiles(fileTarget.value.id)
    toast.success('已删除')
    fileDeleting.value = null
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    fileDeletingLoading.value = false
  }
}

function fmtSize(b: number) {
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(2)} MB`
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <PageHeader title="Agent" subtitle="Agent 归属于某个 Claw，alias 在你的账号内唯一，对话时作为 model 使用。">
      <template #actions>
        <AppButton :disabled="!claws.length" @click="openCreate">新建 Agent</AppButton>
      </template>
    </PageHeader>

    <!-- 筛选 -->
    <div class="filter-bar">
      <AppSelect v-model="filterClawId" :options="clawOptions" placeholder="按 Claw 筛选" width="200px" />
      <span v-if="!claws.length" class="text-weak">请先创建 Claw 后再添加 Agent。</span>
    </div>

    <!-- 骨架 -->
    <div v-if="loading" class="agent-grid">
      <AppSkeleton v-for="i in 4" :key="i" variant="rect" height="180px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!agents.length"
      icon="⌂"
      title="暂无 Agent"
      description="创建你的第一个 Agent，绑定基础模型后即可在对话中使用。"
    >
      <AppButton :disabled="!claws.length" @click="openCreate">新建 Agent</AppButton>
    </AppEmpty>
    <AppEmpty v-else-if="!filteredAgents.length" icon="◇" title="该 Claw 下暂无 Agent" description="切换筛选或为当前 Claw 新建 Agent。" />

    <!-- 卡片网格 -->
    <div v-else class="agent-grid">
      <TransitionGroup name="stagger" tag="div" class="agent-grid">
        <div
          v-for="(a, i) in filteredAgents"
          :key="a.id"
          class="agent-card card"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="agent-card-head">
            <div class="agent-title">
              <h3>{{ a.name }}</h3>
              <span class="mono alias">{{ a.alias }}</span>
            </div>
            <AppTag :tone="a.status === 1 ? 'success' : 'neutral'" pulse>
              {{ a.status === 1 ? '启用' : '停用' }}
            </AppTag>
          </div>

          <p class="desc">{{ a.description || '暂无描述' }}</p>

          <div class="agent-detail">
            <div class="detail-item"><span>Claw</span><span class="value">{{ clawName(a.claw_id) }}</span></div>
            <div class="detail-item"><span>模型</span><span class="value mono">{{ a.base_model }}</span></div>
            <div class="detail-item"><span>温度</span><span class="value">{{ a.temperature ?? '—' }}</span></div>
            <div class="detail-item"><span>Max Tokens</span><span class="value">{{ a.max_tokens ?? '—' }}</span></div>
          </div>

          <div class="prompt-summary mono">
            {{ a.system_prompt || '未配置 System Prompt' }}
          </div>

          <div class="agent-card-actions">
            <AppButton variant="ghost" @click="openSkills(a)">技能</AppButton>
            <AppButton variant="ghost" @click="openFiles(a)">人格文件</AppButton>
            <AppButton variant="ghost" @click="openEdit(a)">编辑</AppButton>
            <AppButton variant="danger" @click="removing = a">删除</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- 创建/编辑弹窗 -->
    <AppModal :show="showEdit" :title="editing ? '编辑 Agent' : '新建 Agent'" width="560px" @close="showEdit = false">
      <div class="form-item" v-if="!editing">
        <label>所属 Claw</label>
        <AppSelect v-model="form.claw_id" :options="claws.map((c) => ({ value: c.id, label: c.name }))" placeholder="选择 Claw" />
      </div>
      <div class="form-item" v-if="!editing">
        <label>alias（账号内唯一，对话 model 值）</label>
        <input v-model="form.alias" class="input mono" placeholder="如 assistant-1" />
      </div>
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="展示名" />
      </div>
      <div class="form-item">
        <label>基础模型</label>
        <input v-model="form.base_model" class="input" placeholder="如 deepseek-v4-flash" />
      </div>
      <div class="form-item">
        <label>描述</label>
        <input v-model="form.description" class="input" placeholder="可选" />
      </div>
      <div class="form-item">
        <label>System Prompt</label>
        <textarea v-model="form.system_prompt" class="textarea" placeholder="可选" />
      </div>
      <div class="row">
        <div class="form-item" style="flex: 1">
          <label>Temperature</label>
          <input v-model="form.temperature" class="input" type="number" step="0.1" placeholder="可选" />
        </div>
        <div class="form-item" style="flex: 1">
          <label>Max Tokens</label>
          <input v-model="form.max_tokens" class="input" type="number" placeholder="可选" />
        </div>
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showEdit = false">取消</AppButton>
        <AppButton :loading="submitting" @click="save">{{ submitting ? '' : '保存' }}</AppButton>
      </template>
    </AppModal>

    <!-- 技能绑定 -->
    <AppModal :show="showSkills" :title="`技能绑定 · ${skillTarget?.name ?? ''}`" width="560px" @close="showSkills = false">
      <AppEmpty v-if="!allSkills.length" icon="⚙" title="你还没有技能" description="先到「技能 Skill」创建或从市场安装。" />
      <div v-else class="skill-list">
        <div v-for="s in allSkills" :key="s.id" class="skill-row">
          <div class="skill-info">
            <div class="skill-name">{{ s.name }}</div>
            <div class="skill-desc">{{ s.description }}</div>
          </div>
          <AppButton
            variant="ghost"
            :class="isBound(s.id) ? 'bound' : ''"
            :loading="skillBusy"
            @click="toggleSkill(s)"
          >
            {{ isBound(s.id) ? '解绑' : '绑定' }}
          </AppButton>
        </div>
      </div>
    </AppModal>

    <!-- 人格文件 -->
    <AppModal :show="showFiles" :title="`人格文件 · ${fileTarget?.name ?? ''}`" width="560px" @close="showFiles = false">
      <div class="upload-bar">
        <input v-model="uploadPath" class="input mono" style="flex: 1" placeholder="目标路径，如 AGENTS.md" />
        <input ref="fileInput" type="file" style="display: none" @change="onPickFile" />
        <AppButton :loading="fileBusy" @click="fileInput?.click()">上传</AppButton>
      </div>

      <div v-if="fileBusy" class="empty">处理中…</div>
      <AppEmpty v-else-if="!files.length" icon="▤" title="暂无文件" description="上传 AGENTS.md / IDENTITY.md / SOUL.md 等作为 Agent 人格。" />
      <table v-else class="data-table file-table">
        <thead>
          <tr><th>文件名</th><th>类型</th><th>大小</th><th style="width: 80px"></th></tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.id">
            <td class="mono">{{ f.file_name }}</td>
            <td class="text-weak">{{ f.file_type }}</td>
            <td class="text-weak">{{ fmtSize(f.file_size) }}</td>
            <td>
              <AppButton variant="danger" size="sm" @click="fileDeleting = f">删除</AppButton>
            </td>
          </tr>
        </tbody>
      </table>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除 Agent"
      :message="`确认删除 Agent「${removing?.name}」(${removing?.alias})？`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />
    <AppConfirm
      :show="!!fileDeleting"
      title="删除文件"
      :message="`确认删除文件「${fileDeleting?.file_name}」？`"
      confirm-text="删除"
      danger
      :loading="fileDeletingLoading"
      @confirm="confirmDeleteFile"
      @cancel="fileDeleting = null"
    />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.agent-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.agent-title {
  min-width: 0;
}
.agent-title h3 {
  margin: 0 0 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.alias {
  font-size: 12px;
  color: var(--accent);
}

.desc {
  margin: 10px 0;
  font-size: 13px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 16px;
  padding: 10px 0;
  border-top: 1px dashed var(--border);
  font-size: 12px;
}
.detail-item {
  display: flex;
  justify-content: space-between;
  color: var(--text-muted);
}
.detail-item .value {
  color: var(--text-secondary);
}

.prompt-summary {
  margin: 6px 0 12px;
  padding: 10px 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 技能列表 */
.skill-list {
  max-height: 50vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.skill-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.skill-info {
  min-width: 0;
}
.skill-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.skill-desc {
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 文件上传 */
.upload-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.file-table {
  margin-top: 4px;
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>