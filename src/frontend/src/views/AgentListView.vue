<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { agentApi, clawApi, skillApi, ApiError } from '@/api'
import type { Agent, Claw, Skill, AgentFileVO } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()

const claws = ref<Claw[]>([])
const agents = ref<Agent[]>([])
const allSkills = ref<Skill[]>([])
const loading = ref(false)
const filterClawId = ref<number | 'all'>('all')

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

const clawName = (id: number) => claws.value.find((c) => c.id === id)?.name || `#${id}`

const filteredAgents = computed(() =>
  filterClawId.value === 'all'
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

async function remove(a: Agent) {
  if (!confirm(`确认删除 Agent「${a.name}」(${a.alias})？`)) return
  try {
    await agentApi.remove(a.id)
    toast.success('已删除')
    await loadAll()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
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
async function deleteFile(f: AgentFileVO) {
  if (!fileTarget.value) return
  if (!confirm(`删除文件「${f.file_name}」？`)) return
  fileBusy.value = true
  try {
    await agentApi.deleteFile(fileTarget.value.id, f.id)
    files.value = await agentApi.listFiles(fileTarget.value.id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    fileBusy.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">Agent</div>
        <div class="page-sub">Agent 归属于某个 Claw，alias 在你的账号内唯一，对话时作为 model 使用。</div>
      </div>
      <button class="btn btn-primary" :disabled="!claws.length" @click="openCreate">新建 Agent</button>
    </div>

    <div class="row" style="margin-bottom: 12px">
      <label class="text-weak">按 Claw 筛选：</label>
      <select v-model="filterClawId" class="select" style="width: 200px">
        <option value="all">全部</option>
        <option v-for="c in claws" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <div v-if="!claws.length" class="text-weak">请先创建 Claw 后再添加 Agent。</div>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!filteredAgents.length" class="empty">暂无 Agent。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>alias</th>
            <th>Claw</th>
            <th>基础模型</th>
            <th style="width: 240px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in filteredAgents" :key="a.id">
            <td>{{ a.id }}</td>
            <td>{{ a.name }}</td>
            <td class="mono">{{ a.alias }}</td>
            <td class="text-weak">{{ clawName(a.claw_id) }}</td>
            <td class="text-weak">{{ a.base_model }}</td>
            <td>
              <div class="row" style="gap: 6px">
                <button class="btn btn-sm" @click="openSkills(a)">技能</button>
                <button class="btn btn-sm" @click="openFiles(a)">人格文件</button>
                <button class="btn btn-sm" @click="openEdit(a)">编辑</button>
                <button class="btn btn-sm btn-danger" @click="remove(a)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 创建/编辑 -->
    <BaseModal v-model="showEdit" :title="editing ? '编辑 Agent' : '新建 Agent'">
      <div class="form-item" v-if="!editing">
        <label>所属 Claw</label>
        <select v-model="form.claw_id" class="select">
          <option v-for="c in claws" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
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
      <template #footer>
        <button class="btn" @click="showEdit = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="save">
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </template>
    </BaseModal>

    <!-- 技能绑定 -->
    <BaseModal v-model="showSkills" :title="`技能绑定 · ${skillTarget?.name ?? ''}`">
      <div v-if="!allSkills.length" class="empty">你还没有技能，先到「技能 Skill」创建或从市场安装。</div>
      <table v-else class="table">
        <thead>
          <tr><th>技能</th><th>描述</th><th style="width: 90px">状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in allSkills" :key="s.id">
            <td>{{ s.name }}</td>
            <td class="text-weak">{{ s.description }}</td>
            <td>
              <button
                class="btn btn-sm"
                :class="isBound(s.id) ? 'btn-danger' : 'btn-primary'"
                :disabled="skillBusy"
                @click="toggleSkill(s)"
              >
                {{ isBound(s.id) ? '解绑' : '绑定' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </BaseModal>

    <!-- 人格文件 -->
    <BaseModal v-model="showFiles" :title="`人格文件 · ${fileTarget?.name ?? ''}`">
      <div class="row" style="margin-bottom: 12px">
        <input v-model="uploadPath" class="input mono" style="flex: 1" placeholder="目标路径，如 AGENTS.md" />
        <input ref="fileInput" type="file" style="display: none" @change="onPickFile" />
        <button class="btn btn-primary" :disabled="fileBusy" @click="fileInput?.click()">上传</button>
      </div>
      <div v-if="fileBusy" class="empty">处理中…</div>
      <div v-else-if="!files.length" class="empty">暂无文件（如 AGENTS.md / IDENTITY.md / SOUL.md）。</div>
      <table v-else class="table">
        <thead>
          <tr><th>文件名</th><th>类型</th><th>大小</th><th style="width: 70px"></th></tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.id">
            <td class="mono">{{ f.file_name }}</td>
            <td class="text-weak">{{ f.file_type }}</td>
            <td class="text-weak">{{ f.file_size }} B</td>
            <td><button class="btn btn-sm btn-danger" @click="deleteFile(f)">删除</button></td>
          </tr>
        </tbody>
      </table>
    </BaseModal>
  </div>
</template>
