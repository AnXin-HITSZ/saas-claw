<script setup lang="ts">
/**
 * 平台技能管理（管理员）：走 /skills/platform 系列接口，与用户技能区分。
 * 列表暂复用 GET /skills（后端平台技能 user_id=0；此处展示当前登录管理员可见集合）。
 */
import { onMounted, reactive, ref } from 'vue'
import { skillApi, ApiError } from '@/api'
import type { Skill } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const list = ref<Skill[]>([])
const loading = ref(false)

const showEdit = ref(false)
const editing = ref<Skill | null>(null)
const form = reactive({ name: '', description: '', version: '', author: '' })
const submitting = ref(false)

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
      await skillApi.updatePlatform(editing.value.id, body)
      toast.success('已更新')
    } else {
      await skillApi.createPlatform(body)
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
  if (!confirm(`删除平台技能「${s.name}」？`)) return
  try {
    await skillApi.removePlatform(s.id)
    toast.success('已删除')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">平台技能 <span class="tag tag-warning">管理员</span></div>
        <div class="page-sub">平台级公共技能（user_id=0），对所有用户可见。</div>
      </div>
      <button class="btn btn-primary" @click="openCreate">新建平台技能</button>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无技能。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>版本</th>
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in list" :key="s.id">
            <td>{{ s.id }}</td>
            <td>{{ s.name }}</td>
            <td class="text-weak">{{ s.description }}</td>
            <td class="text-weak">{{ s.version || '—' }}</td>
            <td>
              <div class="row" style="gap: 6px">
                <button class="btn btn-sm" @click="openEdit(s)">编辑</button>
                <button class="btn btn-sm btn-danger" @click="remove(s)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showEdit" :title="editing ? '编辑平台技能' : '新建平台技能'">
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
  </div>
</template>
