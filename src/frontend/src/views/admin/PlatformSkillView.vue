<script setup lang="ts">
/**
 * 平台技能管理（管理员）：走 /skills/platform 系列接口，与用户技能区分。
 * 列表暂复用 GET /skills（后端平台技能 user_id=0；此处展示当前登录管理员可见集合）。
 */
import { onMounted, reactive, ref } from 'vue'
import { skillApi, ApiError } from '@/api'
import type { Skill } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'

const toast = useToast()
const list = ref<Skill[]>([])
const loading = ref(false)

const showEdit = ref(false)
const editing = ref<Skill | null>(null)
const form = reactive({ name: '', description: '', version: '', author: '' })
const submitting = ref(false)

const removing = ref<Skill | null>(null)
const removingLoading = ref(false)

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

async function confirmRemove() {
  if (!removing.value) return
  removingLoading.value = true
  try {
    await skillApi.removePlatform(removing.value.id)
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
    <PageHeader title="平台技能" subtitle="平台级公共技能（user_id=0），对所有用户可见。">
      <template #actions>
        <AppButton @click="openCreate">新建平台技能</AppButton>
      </template>
    </PageHeader>

    <!-- 骨架 -->
    <div v-if="loading" class="skill-grid">
      <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="140px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="▥"
      title="暂无平台技能"
      description="创建平台级公共技能，所有用户即可在技能列表中看到。"
    >
      <AppButton @click="openCreate">新建平台技能</AppButton>
    </AppEmpty>

    <!-- 卡片网格 -->
    <div v-else class="skill-grid">
      <TransitionGroup name="stagger" tag="div" class="skill-grid">
        <div
          v-for="(s, i) in list"
          :key="s.id"
          class="skill-card card"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="card-top">
            <span class="card-icon">▥</span>
            <div class="card-title">
              <h3>{{ s.name }}</h3>
              <span class="meta-line">
                <span class="mono">#{{ s.id }}</span>
                <template v-if="s.version"> · v{{ s.version }}</template>
                <template v-if="s.author"> · {{ s.author }}</template>
              </span>
            </div>
          </div>

          <p class="desc">{{ s.description }}</p>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="openEdit(s)">编辑</AppButton>
            <AppButton variant="danger" size="sm" @click="removing = s">删除</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- 创建/编辑弹窗 -->
    <AppModal :show="showEdit" :title="editing ? '编辑平台技能' : '新建平台技能'" width="520px" @close="showEdit = false">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="技能名称" />
      </div>
      <div class="form-item">
        <label>描述</label>
        <textarea v-model="form.description" class="textarea" placeholder="技能用途说明" />
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
      <template #actions>
        <AppButton variant="ghost" @click="showEdit = false">取消</AppButton>
        <AppButton :loading="submitting" @click="save">{{ submitting ? '' : '保存' }}</AppButton>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除平台技能"
      :message="`确认删除平台技能「${removing?.name}」？`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />
  </div>
</template>

<style scoped>
.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
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
.desc {
  margin: 12px 0;
  font-size: 13px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
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