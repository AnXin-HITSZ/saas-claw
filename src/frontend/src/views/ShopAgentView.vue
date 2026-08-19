<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { shopApi, clawApi, ApiError } from '@/api'
import type { ShopAgentVO, MyAgentInstallationVO, Claw, MissingSkillVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppSelect, { type SelectOption } from '@/components/ui/AppSelect.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const tab = ref<'market' | 'mine'>('market')

const market = ref<ShopAgentVO[]>([])
const mine = ref<MyAgentInstallationVO[]>([])
const claws = ref<Claw[]>([])
const loading = ref(false)

// 安装弹窗
const showInstall = ref(false)
const installTarget = ref<ShopAgentVO | null>(null)
const targetClawId = ref<number | null>(null)
const installing = ref(false)
const missing = ref<MissingSkillVO[]>([])

const uninstalling = ref<MyAgentInstallationVO | null>(null)
const uninstallingLoading = ref(false)

const clawOptions = computed<SelectOption[]>(() =>
  claws.value.map((c) => ({ value: c.id, label: c.name })),
)

async function load() {
  loading.value = true
  try {
    const [m, mi, c] = await Promise.all([
      shopApi.listAgents(),
      shopApi.myAgentInstallations(),
      clawApi.list(),
    ])
    market.value = m
    mine.value = mi
    claws.value = c
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openInstall(a: ShopAgentVO) {
  if (!claws.value.length) {
    toast.error('请先创建 Claw')
    return
  }
  installTarget.value = a
  targetClawId.value = claws.value[0].id
  missing.value = []
  showInstall.value = true
}

async function doInstall() {
  if (!installTarget.value || !targetClawId.value) return
  installing.value = true
  try {
    const res = await shopApi.installAgent(installTarget.value.agent_id, {
      claw_id: targetClawId.value,
    })
    missing.value = res.missing_skills || []
    if (missing.value.length) {
      toast.info('已安装，但存在缺失技能')
    } else {
      toast.success('安装成功')
    }
    showInstall.value = false
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '安装失败')
  } finally {
    installing.value = false
  }
}

function askUninstall(m: MyAgentInstallationVO) {
  uninstalling.value = m
}

async function confirmUninstall() {
  if (!uninstalling.value) return
  uninstallingLoading.value = true
  try {
    await shopApi.uninstallAgent(uninstalling.value.installation_id)
    toast.success('已卸载')
    uninstalling.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '卸载失败')
  } finally {
    uninstallingLoading.value = false
  }
}

const clawName = (id: number) => claws.value.find((c) => c.id === id)?.name || `#${id}`

function fmtTime(s: string) {
  return s?.replace('T', ' ').slice(0, 16) ?? ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="Agent 市场" subtitle="浏览他人发布的 Agent，一键安装到你的 Claw。">
      <template #actions>
        <div class="tab-switch">
          <button class="tab-btn" :class="{ active: tab === 'market' }" @click="tab = 'market'">市场</button>
          <button class="tab-btn" :class="{ active: tab === 'mine' }" @click="tab = 'mine'">我的安装</button>
        </div>
      </template>
    </PageHeader>

    <!-- 市场 -->
    <template v-if="tab === 'market'">
      <div v-if="loading" class="agent-grid">
        <AppSkeleton v-for="i in 4" :key="i" variant="rect" height="190px" />
      </div>

      <AppEmpty
        v-else-if="!market.length"
        icon="□"
        title="市场暂无 Agent"
        description="还没有人发布 Agent，先创建属于你自己的 Agent 吧。"
      />

      <div v-else class="agent-grid">
        <TransitionGroup name="stagger" tag="div" class="agent-grid">
          <div
            v-for="(a, i) in market"
            :key="a.agent_id"
            class="shop-card card"
            :style="{ transitionDelay: `${i * 40}ms` }"
          >
            <div class="card-top">
              <span class="card-icon">□</span>
              <div class="card-title">
                <h3>{{ a.name }}</h3>
                <span class="mono alias">{{ a.alias }}</span>
              </div>
              <AppTag v-if="a.version" tone="info">v{{ a.version }}</AppTag>
            </div>

            <p class="shop-desc">{{ a.description || '暂无描述' }}</p>

            <div class="shop-meta">
              <span class="meta-item">模型 <span class="mono value">{{ a.base_model }}</span></span>
              <span class="meta-item">安装 <span class="value">{{ a.installs }}</span></span>
            </div>
            <div class="shop-meta">
              <span class="meta-item">发布者 <span class="value">{{ a.publisher_nickname || '#' + a.publisher_id }}</span></span>
            </div>

            <div class="card-actions">
              <AppButton size="sm" @click="openInstall(a)">安装</AppButton>
            </div>
          </div>
        </TransitionGroup>
      </div>
    </template>

    <!-- 我的安装 -->
    <template v-else>
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="120px" />
      </div>

      <AppEmpty
        v-else-if="!mine.length"
        icon="□"
        title="还没有安装任何市场 Agent"
        description="去市场浏览并一键安装到你的 Claw。"
      />

      <div v-else class="card">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>alias</th>
              <th>Claw</th>
              <th>模型</th>
              <th>安装时间</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in mine" :key="m.installation_id">
              <td>{{ m.name }}</td>
              <td class="mono">{{ m.alias }}</td>
              <td>
                <span class="text-weak">{{ clawName(m.claw_id) }}</span>
              </td>
              <td class="text-weak">{{ m.base_model }}</td>
              <td class="text-weak">{{ fmtTime(m.installed_at) }}</td>
              <td>
                <AppButton variant="danger" size="sm" @click="askUninstall(m)">卸载</AppButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 安装弹窗 -->
    <AppModal :show="showInstall" :title="`安装 · ${installTarget?.name ?? ''}`" width="500px" @close="showInstall = false">
      <div class="form-item">
        <label>安装到哪个 Claw</label>
        <AppSelect v-model="targetClawId" :options="clawOptions" placeholder="选择 Claw" />
      </div>

      <div v-if="missing.length" class="alert alert-info">
        以下技能缺失，安装后需自行补齐：
        <ul class="missing-list">
          <li v-for="s in missing" :key="s.skill_id">
            <span>{{ s.name }}</span>
            <AppTag :tone="s.installable ? 'success' : 'danger'">
              {{ s.installable ? '市场可装' : '不可用' }}
            </AppTag>
          </li>
        </ul>
      </div>

      <template #actions>
        <AppButton variant="ghost" @click="showInstall = false">关闭</AppButton>
        <AppButton :loading="installing" @click="doInstall">{{ installing ? '' : '确认安装' }}</AppButton>
      </template>
    </AppModal>

    <!-- 卸载确认 -->
    <AppConfirm
      :show="!!uninstalling"
      title="卸载 Agent"
      :message="`卸载已安装的 Agent「${uninstalling?.name}」？`"
      confirm-text="卸载"
      danger
      :loading="uninstallingLoading"
      @confirm="confirmUninstall"
      @cancel="uninstalling = null"
    />
  </div>
</template>

<style scoped>
.tab-switch {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 12px;
}
.tab-btn {
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  transition: color 0.2s var(--ease-out), background 0.2s var(--ease-out);
}
.tab-btn:hover {
  color: var(--text-primary);
}
.tab-btn.active {
  color: var(--accent);
  background: var(--accent-glow);
}

.agent-grid {
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
.alias {
  font-size: 12px;
  color: var(--accent);
}
.shop-desc {
  margin: 12px 0;
  font-size: 13px;
  color: var(--text-secondary);
  min-height: 38px;
}
.shop-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}
.meta-item .value {
  color: var(--text-secondary);
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.missing-list {
  margin: 8px 0 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.missing-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.stagger-enter-active {
  transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out);
}
.stagger-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
</style>