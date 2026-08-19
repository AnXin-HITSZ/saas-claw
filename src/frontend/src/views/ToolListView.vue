<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { toolApi, ApiError } from '@/api'
import type { Tool } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const list = ref<Tool[]>([])
const loading = ref(false)
const detail = ref<Tool | null>(null)
const showDetail = ref(false)

const sensitiveCount = computed(() => list.value.filter((t) => t.is_sensitive).length)

async function load() {
  loading.value = true
  try {
    list.value = await toolApi.list()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
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

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="工具" subtitle="平台当前可被 Agent 调用的工具（由运行时同步，只读展示）。" />

    <!-- 统计行 -->
    <div v-if="!loading && list.length" class="stat-row">
      <div class="stat-card">
        <div class="stat-value accent">{{ list.length }}</div>
        <div class="stat-label">工具总数</div>
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
      description="工具由运行时从插件/注册中心同步，只读展示。"
    />

    <!-- 工具卡片 -->
    <div v-else class="tool-grid">
      <TransitionGroup name="stagger" tag="div" class="tool-grid">
        <div
          v-for="(t, i) in list"
          :key="t.id"
          class="tool-card card"
          :style="{ transitionDelay: `${i * 40}ms` }"
        >
          <div class="card-top">
            <span class="card-icon">▤</span>
            <div class="card-title">
              <h3 class="mono">{{ t.name }}</h3>
              <span class="meta-line">#{{ t.id }}</span>
            </div>
            <AppTag :tone="t.is_sensitive ? 'warning' : 'neutral'" :pulse="!!t.is_sensitive">
              {{ t.is_sensitive ? '敏感' : '普通' }}
            </AppTag>
          </div>

          <p class="desc">{{ t.description || '暂无描述' }}</p>

          <div class="card-actions">
            <AppButton variant="ghost" size="sm" @click="open(t)">查看 Schema</AppButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- Schema 弹窗 -->
    <AppModal :show="showDetail" :title="detail?.name || '工具'" width="640px" @close="showDetail = false">
      <div class="text-weak" style="margin-bottom: 10px">{{ detail?.description || '无描述' }}</div>
      <pre class="schema">{{ prettySchema(detail?.schema_json ?? null) }}</pre>
      <template #actions>
        <AppButton variant="ghost" @click="showDetail = false">关闭</AppButton>
      </template>
    </AppModal>
  </div>
</template>

<style scoped>
.tool-grid {
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

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>