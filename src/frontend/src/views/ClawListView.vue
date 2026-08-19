<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { clawApi, ApiError } from '@/api'
import type { Claw } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const list = ref<Claw[]>([])
const loading = ref(false)
const showCreate = ref(false)
const form = reactive({ name: '' })
const submitting = ref(false)

const removing = ref<Claw | null>(null)
const removingLoading = ref(false)

const runningCount = computed(() => list.value.filter((c) => c.status === 1).length)
const agentCount = computed(() => list.value.length) // 占位：Agent 数由 AgentListView 展示

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

async function confirmRemove() {
  if (!removing.value) return
  removingLoading.value = true
  try {
    await clawApi.remove(removing.value.id)
    toast.success('已删除')
    removing.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  } finally {
    removingLoading.value = false
  }
}

function fmtTime(s: string) {
  return s?.replace('T', ' ').slice(0, 16) ?? ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="Claw 实例" subtitle="每个 Claw 是一套独立部署的运行时，承载你的 Agent 与技能。">
      <template #actions>
        <AppButton @click="showCreate = true">新建 Claw</AppButton>
      </template>
    </PageHeader>

    <!-- 统计行 -->
    <div v-if="!loading && list.length" class="stat-row">
      <div class="stat-card">
        <div class="stat-value accent">{{ list.length }}</div>
        <div class="stat-label">Claw 总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value success">{{ runningCount }}</div>
        <div class="stat-label">运行中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ agentCount }}</div>
        <div class="stat-label">部署 Agent</div>
      </div>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" class="grid">
      <AppSkeleton
        v-for="i in 3"
        :key="i"
        variant="rect"
        height="140px"
        :style="{ transitionDelay: `${i * 40}ms` }"
      />
    </div>

    <!-- 空态 -->
    <AppEmpty
      v-else-if="!list.length"
      icon="▣"
      title="还没有 Claw"
      description="创建一个 Claw 作为你的运行时，随后可为其配置 Agent 与技能。"
    >
      <AppButton @click="showCreate = true">新建 Claw</AppButton>
    </AppEmpty>

    <!-- 卡片网格 -->
    <div v-else class="claw-grid">
      <TransitionGroup name="stagger" tag="div" class="claw-grid">
        <div
          v-for="(c, i) in list"
          :key="c.id"
          class="claw-card card"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="card-top">
            <span class="card-icon">▣</span>
            <div class="card-title">
              <h3>{{ c.name }}</h3>
              <span class="mono ns">{{ c.namespace }}</span>
            </div>
            <AppTag :tone="c.status === 1 ? 'success' : 'neutral'" pulse>
              {{ c.status === 1 ? '运行中' : '未部署' }}
            </AppTag>
          </div>

          <div class="card-meta">
            <span class="meta-item">ID <span class="mono">#{{ c.id }}</span></span>
            <span class="meta-item">创建 <span class="mono">{{ fmtTime(c.created_at) }}</span></span>
          </div>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="removing = c">删除</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- 新建弹窗 -->
    <AppModal :show="showCreate" title="新建 Claw" @close="showCreate = false">
      <div class="form-item">
        <label>名称</label>
        <input
          v-model="form.name"
          class="input"
          placeholder="如 my-first-claw"
          @keyup.enter="create"
        />
      </div>
      <template #actions>
        <AppButton variant="ghost" @click="showCreate = false">取消</AppButton>
        <AppButton :loading="submitting" @click="create">
          {{ submitting ? '' : '创建' }}
        </AppButton>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm
      :show="!!removing"
      title="删除 Claw"
      :message="`确认删除 Claw「${removing?.name}」？该操作会一并吊销其程序通道，不可恢复。`"
      confirm-text="删除"
      danger
      :loading="removingLoading"
      @confirm="confirmRemove"
      @cancel="removing = null"
    />
  </div>
</template>

<style scoped>
.claw-grid {
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
.ns {
  font-size: 12px;
  color: var(--text-muted);
}
.card-meta {
  display: flex;
  gap: 16px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
  color: var(--text-muted);
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>