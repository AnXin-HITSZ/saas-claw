<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { toolApi, ApiError } from '@/api'
import type { Tool } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const auth = useAuthStore()
const isAdmin = auth.isAdmin

const list = ref<Tool[]>([])
const loading = ref(false)

// ---- 详情弹窗 ----
const detail = ref<Tool | null>(null)
const showDetail = ref(false)

// ---- 新增/编辑 ----
const showEdit = ref(false)
const editing = ref<Tool | null>(null)
const form = reactive({
  name: '',
  description: '',
  schema_json: '',
  is_sensitive: false,
})
const submitting = ref(false)

// ---- 删除确认 ----
const removing = ref<Tool | null>(null)
const removingLoading = ref(false)
const togglingId = ref<number | null>(null)

const totalCount = computed(() => list.value.length)
const enabledCount = computed(() => list.value.filter((t) => t.status === 1).length)
const sensitiveCount = computed(() =>
  list.value.filter((t) => t.is_sensitive).filter((t) => t.status === 1).length,
)

async function load() {
  loading.value = true
  try {
    // 管理员看全部（含停用），普通用户只看启用清单
    list.value = isAdmin ? await toolApi.listAll() : await toolApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', description: '', schema_json: '', is_sensitive: false })
  showEdit.value = true
}

function openEdit(t: Tool) {
  editing.value = t
  Object.assign(form, {
    name: t.name,
    description: t.description ?? '',
    schema_json: t.schema_json ?? '',
    is_sensitive: t.is_sensitive === 1,
  })
  showEdit.value = true
}

function validateSchema(json: string): boolean {
  if (!json.trim()) return true
  try {
    JSON.parse(json)
    return true
  } catch {
    toast.error('schema_json 不是合法 JSON')
    return false
  }
}

async function save() {
  if (!form.name.trim()) return toast.error('请输入工具名')
  if (!validateSchema(form.schema_json)) return
  submitting.value = true
  try {
    const body = {
      description: form.description.trim() || undefined,
      schema_json: form.schema_json.trim() || undefined,
      is_sensitive: form.is_sensitive ? 1 : 0,
    }
    if (editing.value) {
      await toolApi.update(editing.value.id, body)
      toast.success('已更新')
    } else {
      await toolApi.create({ name: form.name.trim(), ...body })
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

async function toggleStatus(t: Tool) {
  togglingId.value = t.id
  try {
    await toolApi.update(t.id, { status: t.status === 1 ? 0 : 1 })
    toast.success(t.status === 1 ? '已停用' : '已启用')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    togglingId.value = null
  }
}

async function confirmRemove() {
  if (!removing.value) return
  removingLoading.value = true
  try {
    await toolApi.remove(removing.value.id)
    toast.success('已删除')
    removing.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    removingLoading.value = false
  }
}

function open(t: Tool) {
  detail.value = t
  showDetail.value = true
}

function prettySchema(json: string | null): string {
  if (!json) return '（无 schema）'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

const statusTone = (t: Tool) => (t.status === 1 ? (t.is_sensitive ? ('warning' as const) : ('success' as const)) : ('neutral' as const))
const statusText = (t: Tool) => (t.status === 1 ? (t.is_sensitive ? '敏感' : '启用') : '停用')

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader
      title="工具"
      :subtitle="isAdmin ? '管理员配置平台工具：新增 / 编辑契约、标记敏感、启停。' : '平台当前启用的工具（只读）。'"
    >
      <template #actions>
        <AppButton v-if="isAdmin" @click="openCreate">新增工具</AppButton>
      </template>
    </PageHeader>

    <!-- 统计行 -->
    <div v-if="!loading && list.length" class="stat-row">
      <div class="stat-card">
        <div class="stat-value accent">{{ totalCount }}</div>
        <div class="stat-label">{{ isAdmin ? '工具总数' : '工具总数' }}</div>
      </div>
      <div v-if="isAdmin" class="stat-card">
        <div class="stat-value success">{{ enabledCount }}</div>
        <div class="stat-label">启用中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value warning">{{ sensitiveCount }}</div>
        <div class="stat-label">敏感工具</div>
      </div>
    </div>

    <!-- 骨架 -->
    <div v-if="loading" class="grid">
      <AppSkeleton v-for="i in 4" :key="i" variant="rect" height="120px" />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="▤"
      title="暂无工具"
      :description="isAdmin ? '创建第一个工具，配置契约后 Agent 即可调用。' : '平台暂未启用工具。'"
    >
      <AppButton v-if="isAdmin" @click="openCreate">新增工具</AppButton>
    </AppEmpty>

    <!-- 工具卡片 -->
    <div v-else class="tool-grid">
      <TransitionGroup name="stagger" tag="div" class="tool-grid">
        <div
          v-for="(t, i) in list"
          :key="t.id"
          class="tool-card card"
          :class="{ disabled: t.status !== 1 }"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="card-top">
            <span class="card-icon">▤</span>
            <div class="card-title">
              <h3 class="mono">{{ t.name }}</h3>
              <span class="meta-line">#{{ t.id }}</span>
            </div>
            <AppTag :tone="statusTone(t)" :pulse="t.status === 1 && t.is_sensitive === 1">
              {{ statusText(t) }}
            </AppTag>
          </div>

          <p class="desc">{{ t.description || '暂无描述' }}</p>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="open(t)">查看 Schema</AppButton>
            <template v-if="isAdmin">
              <AppButton
                variant="ghost"
                size="sm"
                :loading="togglingId === t.id"
                @click="toggleStatus(t)"
              >
                {{ t.status === 1 ? '停用' : '启用' }}
              </AppButton>
              <AppButton variant="ghost" size="sm" @click="openEdit(t)">编辑</AppButton>
              <AppButton variant="danger" size="sm" @click="removing = t">删除</AppButton>
            </template>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- Schema 弹窗 -->
    <AppModal :show="showDetail" :title="detail?.name || '工具'" width="640px" @close="showDetail = false">
      <div class="text-weak" style="margin-bottom: 10px; white-space: pre-line">{{ detail?.description || '无描述' }}</div>
      <pre class="schema">{{ prettySchema(detail?.schema_json ?? null) }}</pre>
      <template #actions>
        <AppButton variant="ghost" @click="showDetail = false">关闭</AppButton>
      </template>
    </AppModal>

    <!-- 新增/编辑弹窗 -->
    <AppModal :show="showEdit" :title="editing ? '编辑工具' : '新增工具'" width="560px" @close="showEdit = false">
      <div class="form-item">
        <label>工具名（唯一标识）</label>
        <input
          v-model="form.name"
          class="input mono"
          :disabled="!!editing"
          placeholder="如 read_persona"
        />
        <span class="form-hint" v-if="editing">name 不可修改（runtime 按名匹配执行器）</span>
      </div>
      <div class="form-item">
        <label>描述</label>
        <textarea v-model="form.description" class="textarea" rows="3" placeholder="工具用途说明（支持换行）" />
      </div>
      <div class="form-item">
        <label>入参 Schema（JSON Schema 字符串）</label>
        <textarea
          v-model="form.schema_json"
          class="textarea mono"
          rows="6"
          placeholder='{"type":"object","properties":{"path":{"type":"string"}},...}'
        />
      </div>
      <div class="form-item">
        <label class="switch-line">
          <input v-model="form.is_sensitive" type="checkbox" />
          <span class="switch-track"><span class="switch-thumb"></span></span>
          <span>敏感工具（调用前需人工审批）</span>
        </label>
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showEdit = false">取消</AppButton>
        <AppButton :loading="submitting" @click="save">{{ submitting ? '' : '保存' }}</AppButton>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除工具"
      :message="`确认删除工具「${removing?.name}」？删除后历史审批记录将失去工具名。`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />
  </div>
</template>

<style scoped>
.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.tool-card.disabled {
  opacity: 0.55;
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
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
  white-space: pre-line;
  word-break: break-word;
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.schema {
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  font-size: 12px;
  color: var(--text-secondary);
  overflow: auto;
  max-height: 420px;
  font-family: var(--font-mono);
}

.form-hint {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

/* 敏感开关 */
.switch-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
}
.switch-line input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.switch-track {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  background: var(--bg-raised);
  border: 1px solid var(--border);
  position: relative;
  transition: background 0.2s var(--ease-out), border-color 0.2s var(--ease-out);
  flex-shrink: 0;
}
.switch-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: transform 0.2s var(--ease-out), background 0.2s var(--ease-out);
}
.switch-line input:checked + .switch-track {
  background: var(--accent-glow);
  border-color: var(--accent);
}
.switch-line input:checked + .switch-track .switch-thumb {
  transform: translateX(16px);
  background: var(--accent);
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>