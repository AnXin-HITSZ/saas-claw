<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { modelConfigApi, ApiError } from '@/api'
import type { ModelConfig } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

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
  status: 1,
})
const submitting = ref(false)

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
  submitting.value = true
  try {
    if (editing.value) {
      await modelConfigApi.update(editing.value.id, {
        endpoint: form.endpoint,
        api_key: form.api_key || undefined, // 空则不改
        status: form.status,
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

async function remove(m: ModelConfig) {
  if (!confirm(`删除模型配置「${m.name}」？依赖它的 Agent 将无法推理。`)) return
  try {
    await modelConfigApi.remove(m.id)
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
        <div class="page-title">模型配置 <span class="tag tag-warning">管理员</span></div>
        <div class="page-sub">平台级模型供应商配置，供所有 Agent 的基础模型引用。API Key 明文不回显。</div>
      </div>
      <button class="btn btn-primary" @click="openCreate">新增配置</button>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无模型配置。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>供应商</th>
            <th>模型</th>
            <th>Endpoint</th>
            <th>状态</th>
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in list" :key="m.id">
            <td>{{ m.id }}</td>
            <td>{{ m.name }}</td>
            <td class="text-weak">{{ m.provider }}</td>
            <td class="mono">{{ m.model_name }}</td>
            <td class="mono text-weak">{{ m.endpoint }}</td>
            <td>
              <span v-if="m.status === 1" class="tag tag-success">启用</span>
              <span v-else class="tag tag-danger">停用</span>
            </td>
            <td>
              <div class="row" style="gap: 6px">
                <button class="btn btn-sm" @click="openEdit(m)">编辑</button>
                <button class="btn btn-sm btn-danger" @click="remove(m)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showEdit" :title="editing ? '编辑模型配置' : '新增模型配置'">
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
        <select v-model.number="form.status" class="select">
          <option :value="1">启用</option>
          <option :value="0">停用</option>
        </select>
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
