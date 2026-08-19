<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { skillApi, shopApi, ApiError } from '@/api'
import type { Skill, SkillFileVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

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

const removing = ref<Skill | null>(null)
const removingLoading = ref(false)
const publishing = ref<Skill | null>(null)
const publishingLoading = ref(false)

const enabledCount = computed(() => list.value.filter((s) => s.status === 1).length)

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

async function confirmRemove() {
  if (!removing.value) return
  removingLoading.value = true
  try {
    await skillApi.remove(removing.value.id)
    toast.success('已删除')
    removing.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    removingLoading.value = false
  }
}

async function confirmPublish() {
  if (!publishing.value) return
  publishingLoading.value = true
  try {
    await shopApi.publishSkill(publishing.value.id)
    toast.success('已发布到市场')
    publishing.value = null
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '发布失败')
  } finally {
    publishingLoading.value = false
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
const fileDeleting = ref<SkillFileVO | null>(null)
const fileDeletingLoading = ref(false)
async function confirmDeleteFile() {
  if (!fileTarget.value || !fileDeleting.value) return
  fileDeletingLoading.value = true
  try {
    await skillApi.deleteFile(fileTarget.value.id, fileDeleting.value.id)
    files.value = await skillApi.listFiles(fileTarget.value.id)
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

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="技能 Skill" subtitle="技能是可复用的能力包，可绑定到 Agent，也可发布到市场共享。">
      <template #actions>
        <AppButton @click="openCreate">新建技能</AppButton>
      </template>
    </PageHeader>

    <!-- 统计行 -->
    <div v-if="!loading && list.length" class="stat-row">
      <div class="stat-card">
        <div class="stat-value accent">{{ list.length }}</div>
        <div class="stat-label">技能总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value success">{{ enabledCount }}</div>
        <div class="stat-label">启用中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ list.filter((s) => s.version).length }}</div>
        <div class="stat-label">带版本号</div>
      </div>
    </div>

    <!-- 骨架 -->
    <div v-if="loading" class="grid">
      <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="140px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="⚙"
      title="还没有技能"
      description="技能是可复用的能力包，创建后绑定到 Agent 即可生效，也可发布到市场。"
    >
      <AppButton @click="openCreate">新建技能</AppButton>
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
            <span class="card-icon">⚙</span>
            <div class="card-title">
              <h3>{{ s.name }}</h3>
              <span class="meta-line">
                <span class="mono">#{{ s.id }}</span>
                <template v-if="s.version"> · v{{ s.version }}</template>
                <template v-if="s.author"> · {{ s.author }}</template>
              </span>
            </div>
            <AppTag :tone="s.status === 1 ? 'success' : 'neutral'" pulse>
              {{ s.status === 1 ? '启用' : '停用' }}
            </AppTag>
          </div>

          <p class="desc">{{ s.description }}</p>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="openFiles(s)">文件</AppButton>
            <AppButton variant="ghost" size="sm" @click="publishing = s">发布</AppButton>
            <AppButton variant="ghost" size="sm" @click="openEdit(s)">编辑</AppButton>
            <AppButton variant="danger" size="sm" @click="removing = s">删除</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- 创建/编辑弹窗 -->
    <AppModal :show="showEdit" :title="editing ? '编辑技能' : '新建技能'" width="520px" @close="showEdit = false">
      <div class="form-item">
        <label>名称</label>
        <input v-model="form.name" class="input" placeholder="如 web-search" @keyup.enter="save" />
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

    <!-- 文件弹窗 -->
    <AppModal :show="showFiles" :title="`技能文件 · ${fileTarget?.name ?? ''}`" width="560px" @close="showFiles = false">
      <div class="upload-bar">
        <input v-model="uploadPath" class="input mono" style="flex: 1" placeholder="目标路径，如 SKILL.md" />
        <input ref="fileInput" type="file" style="display: none" @change="onPickFile" />
        <AppButton :loading="fileBusy" @click="fileInput?.click()">上传</AppButton>
      </div>

      <div v-if="fileBusy" class="empty">处理中…</div>
      <AppEmpty v-else-if="!files.length" icon="▤" title="暂无文件" description="上传 SKILL.md / 示例 / 辅助资源等技能文件。" />
      <table v-else class="data-table">
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

    <!-- 发布确认 -->
    <AppConfirm
      :show="!!publishing"
      title="发布到市场"
      :message="`将技能「${publishing?.name}」发布到市场？其他用户即可安装使用。`"
      confirm-text="发布"
      :loading="publishingLoading"
      @confirm="confirmPublish"
      @cancel="publishing = null"
    />

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除技能"
      :message="`确认删除技能「${removing?.name}」？`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />

    <!-- 文件删除确认 -->
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
  background: var(--accent-glow);
  color: var(--accent);
  font-size: 18px;
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

.upload-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>