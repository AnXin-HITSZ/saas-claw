<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { clawApi, ApiError } from '@/api'
import type { Claw } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const list = ref<Claw[]>([])
const loading = ref(false)
const showCreate = ref(false)
const form = reactive({ name: '' })
const submitting = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await clawApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!form.name.trim()) {
    toast.error('请输入名称')
    return
  }
  submitting.value = true
  try {
    await clawApi.create({ name: form.name.trim() })
    toast.success('创建成功')
    showCreate.value = false
    form.name = ''
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '创建失败')
  } finally {
    submitting.value = false
  }
}

async function remove(c: Claw) {
  if (!confirm(`确认删除 Claw「${c.name}」？该操作不可恢复。`)) return
  try {
    await clawApi.remove(c.id)
    toast.success('已删除')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

function statusTag(status: number) {
  return status === 1 ? { cls: 'tag-success', text: '运行中' } : { cls: '', text: '未部署' }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">Claw 实例</div>
        <div class="page-sub">每个 Claw 是一套独立部署的运行时，承载你的 Agent 与技能。</div>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">新建 Claw</button>
    </div>

    <div class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!list.length" class="empty">还没有 Claw，点击右上角新建。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>命名空间</th>
            <th>状态</th>
            <th>创建时间</th>
            <th style="width: 100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in list" :key="c.id">
            <td>{{ c.id }}</td>
            <td>{{ c.name }}</td>
            <td class="mono text-weak">{{ c.namespace }}</td>
            <td><span class="tag" :class="statusTag(c.status).cls">{{ statusTag(c.status).text }}</span></td>
            <td class="text-weak">{{ c.created_at }}</td>
            <td>
              <button class="btn btn-sm btn-danger" @click="remove(c)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showCreate" title="新建 Claw">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="如 my-first-claw" @keyup.enter="create" />
      </div>
      <template #footer>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="create">
          {{ submitting ? '创建中…' : '创建' }}
        </button>
      </template>
    </BaseModal>
  </div>
</template>
