<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { skillApi, shopApi, ApiError } from '@/api'
import type { Skill, SkillFileVO } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const list = ref<Skill[]>([])
const loading = ref(false)

const showEdit = ref(false)
const editing = ref<Skill | null>(null)
const form = reactive({ name: '', description: '', version: '', author: '' })
const submitting = ref(false)

const showFiles = ref(false)
const fileTarget = ref<Skill | null>(null)
const files = ref<SkillFileVO[]>([])
const uploadPath = ref('SKILL.md')
const fileInput = ref<HTMLInputElement | null>(null)
const fileBusy = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await skillApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', description: '', version: '', author: '' })
  showEdit.value = true
}
function openEdit(s: Skill) {
  editing.value = s
  Object.assign(form, {
    name: s.name,
    description: s.description,
    version: s.version ?? '',
    author: s.author ?? '',
  })
  showEdit.value = true
}

async function save() {
  if (!form.name.trim()) return toast.error('请输入名称')
  if (!form.description.trim()) return toast.error('请输入描述')
  submitting.value = true
  try {
    const body = {
      name: form.name.trim(),
      description: form.description.trim(),
      version: form.version || undefined,
      author: form.author || undefined,
    }
    if (editing.value) {
      await skillApi.update(editing.value.id, body)
      toast.success('已更新')
    } else {
      await skillApi.create(body)
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

async function remove(s: Skill) {
  if (!confirm(`确认删除技能「${s.name}」？`)) return
  try {
    await skillApi.remove(s.id)
    toast.success('已删除')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

async function publish(s: Skill) {
  if (!confirm(`将技能「${s.name}」发布到市场？其他用户即可安装。`)) return
  try {
    await shopApi.publishSkill(s.id)
    toast.success('已发布到市场')
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '发布失败')
  }
}

// ---- 文件 ----
async function openFiles(s: Skill) {
  fileTarget.value = s
  showFiles.value = true
  files.value = []
  fileBusy.value = true
  try {
    files.value = await skillApi.listFiles(s.id)
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
    await skillApi.uploadFile(fileTarget.value.id, file, uploadPath.value.trim() || file.name)
    toast.success('已上传')
    files.value = await skillApi.listFiles(fileTarget.value.id)
  } catch (err) {
    toast.error(err instanceof ApiError ? err.message : '上传失败')
  } finally {
    fileBusy.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
async function deleteFile(f: SkillFileVO) {
  if (!fileTarget.value) return
  if (!confirm(`删除文件「${f.file_name}」？`)) return
  fileBusy.value = true
  try {
    await skillApi.deleteFile(fileTarget.value.id, f.id)
    files.value = await skillApi.listFiles(fileTarget.value.id)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    fileBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">技能 Skill</div>
        <div class="page-sub">技能是可复用的能力包，可绑定到 Agent，也可发布到市场共享。</div>
      </div>
      <button class="btn btn-primary" @click="openCreate">新建技能</button>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">还没有技能。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>版本</th>
            <th>作者</th>
            <th style="width: 260px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in list" :key="s.id">
            <td>{{ s.id }}</td>
            <td>{{ s.name }}</td>
            <td class="text-weak">{{ s.description }}</td>
            <td class="text-weak">{{ s.version || '—' }}</td>
            <td class="text-weak">{{ s.author || '—' }}</td>
            <td>
              <div class="row" style="gap: 6px">
                <button class="btn btn-sm" @click="openFiles(s)">文件</button>
                <button class="btn btn-sm" @click="publish(s)">发布</button>
                <button class="btn btn-sm" @click="openEdit(s)">编辑</button>
                <button class="btn btn-sm btn-danger" @click="remove(s)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showEdit" :title="editing ? '编辑技能' : '新建技能'">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" />
      </div>
      <div class="form-item">
        <label>描述</label>
        <textarea v-model="form.description" class="textarea" />
      </div>
      <div class="row">
        <div class="form-item" style="flex: 1">
          <label>版本</label>
          <input v-model="form.version" class="input" placeholder="如 1.0.0" />
        </div>
        <div class="form-item" style="flex: 1">
          <label>作者</label>
          <input v-model="form.author" class="input" placeholder="可选" />
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="showEdit = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="save">
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </template>
    </BaseModal>

    <BaseModal v-model="showFiles" :title="`技能文件 · ${fileTarget?.name ?? ''}`">
      <div class="row" style="margin-bottom: 12px">
        <input v-model="uploadPath" class="input mono" style="flex: 1" placeholder="目标路径，如 SKILL.md" />
        <input ref="fileInput" type="file" style="display: none" @change="onPickFile" />
        <button class="btn btn-primary" :disabled="fileBusy" @click="fileInput?.click()">上传</button>
      </div>
      <div v-if="fileBusy" class="empty">处理中…</div>
      <div v-else-if="!files.length" class="empty">暂无文件。</div>
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
