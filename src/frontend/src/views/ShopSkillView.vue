<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { shopApi, ApiError } from '@/api'
import type { ShopSkillVO, MySkillInstallationVO } from '@/types/api'
import { useToast } from '@/composables/useToast'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppConfirm from '@/components/ui/AppConfirm.vue'
import AppEmpty from '@/components/ui/AppEmpty.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import AppTag from '@/components/ui/AppTag.vue'

const toast = useToast()
const tab = ref<'market' | 'mine'>('market')

const market = ref<ShopSkillVO[]>([])
const mine = ref<MySkillInstallationVO[]>([])
const loading = ref(false)
const selected = ref<Set<number>>(new Set())

const installingOne = ref<number | null>(null)
const batchInstalling = ref(false)

const uninstalling = ref<MySkillInstallationVO | null>(null)
const uninstallingLoading = ref(false)

const selectedCount = computed(() => selected.value.size)

async function load() {
  loading.value = true
  try {
    const [m, mi] = await Promise.all([shopApi.listSkills(), shopApi.mySkillInstallations()])
    market.value = m
    mine.value = mi
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function toggleSelect(id: number) {
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
  // 触发响应式
  selected.value = new Set(selected.value)
}

async function installOne(s: ShopSkillVO) {
  installingOne.value = s.skill_id
  try {
    await shopApi.installSkill(s.skill_id)
    toast.success('安装成功')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '安装失败')
  } finally {
    installingOne.value = null
  }
}

async function installBatch() {
  if (!selectedCount.value) return toast.error('请先勾选技能')
  batchInstalling.value = true
  try {
    const res = await shopApi.installSkillsBatch({ skill_ids: [...selected.value] })
    const ok = res.succeeded?.length ?? 0
    const fail = res.failed?.length ?? 0
    toast.success(`成功 ${ok} 个${fail ? `，失败 ${fail} 个` : ''}`)
    if (fail) {
      res.failed.forEach((f) => toast.error(`#${f.skill_id}: ${f.reason}`))
    }
    selected.value = new Set()
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '批量安装失败')
  } finally {
    batchInstalling.value = false
  }
}

function askUninstall(m: MySkillInstallationVO) {
  uninstalling.value = m
}

async function confirmUninstall() {
  if (!uninstalling.value) return
  uninstallingLoading.value = true
  try {
    await shopApi.uninstallSkill(uninstalling.value.installation_id, uninstalling.value.bound_agent_count > 0)
    toast.success('已卸载')
    uninstalling.value = null
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '卸载失败')
  } finally {
    uninstallingLoading.value = false
  }
}

function fmtTime(s: string) {
  return s?.replace('T', ' ').slice(0, 16) ?? ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="Skill 市场" subtitle="浏览并安装他人发布的技能，支持批量安装。">
      <template #actions>
        <div class="tab-switch">
          <button class="tab-btn" :class="{ active: tab === 'market' }" @click="tab = 'market'">市场</button>
          <button class="tab-btn" :class="{ active: tab === 'mine' }" @click="tab = 'mine'">我的安装</button>
        </div>
      </template>
    </PageHeader>

    <!-- 市场 -->
    <template v-if="tab === 'market'">
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 4" :key="i" variant="rect" height="120px" />
      </div>

      <AppEmpty
        v-else-if="!market.length"
        icon="◇"
        title="市场暂无技能"
        description="还没有人发布技能，去「技能 Skill」创建并发布吧。"
      />

      <template v-else>
        <div class="batch-bar">
          <span class="text-weak">已选 {{ selectedCount }} 项</span>
          <AppButton
            size="sm"
            :disabled="!selectedCount"
            :loading="batchInstalling"
            @click="installBatch"
          >批量安装</AppButton>
        </div>

        <div class="card">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 44px"></th>
                <th>名称</th>
                <th>描述</th>
                <th>版本</th>
                <th>安装数</th>
                <th>发布者</th>
                <th style="width: 90px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in market" :key="s.skill_id">
                <td>
                  <label class="checkbox">
                    <input
                      type="checkbox"
                      :checked="selected.has(s.skill_id)"
                      @change="toggleSelect(s.skill_id)"
                    />
                    <span class="checkmark"></span>
                  </label>
                </td>
                <td>{{ s.name }}</td>
                <td class="text-weak">{{ s.description }}</td>
                <td class="text-weak">{{ s.version || '—' }}</td>
                <td>
                  <span class="installs">{{ s.installs }} 次</span>
                </td>
                <td class="text-weak">{{ s.publisher_nickname || '#' + s.publisher_id }}</td>
                <td>
                  <AppButton
                    size="sm"
                    :loading="installingOne === s.skill_id"
                    @click="installOne(s)"
                  >安装</AppButton>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>

    <!-- 我的安装 -->
    <template v-else>
      <div v-if="loading" class="grid">
        <AppSkeleton v-for="i in 3" :key="i" variant="rect" height="120px" />
      </div>

      <AppEmpty
        v-else-if="!mine.length"
        icon="◇"
        title="还没有安装任何技能"
        description="去市场浏览并安装需要的技能，之后可绑定到 Agent。"
      />

      <div v-else class="card">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th>版本</th>
              <th>已绑定 Agent</th>
              <th>安装时间</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in mine" :key="m.installation_id">
              <td>{{ m.name }}</td>
              <td class="text-weak">{{ m.description }}</td>
              <td class="text-weak">{{ m.version || '—' }}</td>
              <td>
                <AppTag :tone="m.bound_agent_count ? 'warning' : 'neutral'">
                  {{ m.bound_agent_count }}
                </AppTag>
              </td>
              <td class="text-weak">{{ fmtTime(m.installed_at) }}</td>
              <td>
                <AppButton variant="danger" size="sm" @click="askUninstall(m)">卸载</AppButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 卸载确认 -->
    <AppConfirm
      :show="!!uninstalling"
      title="卸载技能"
      :message="
        uninstalling?.bound_agent_count
          ? `技能「${uninstalling?.name}」已被 ${uninstalling?.bound_agent_count} 个 Agent 绑定，强制卸载会解除绑定。确认？`
          : `卸载技能「${uninstalling?.name}」？`
      "
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

.batch-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 14px;
}

.installs {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 自定义 checkbox */
.checkbox {
  display: inline-flex;
  align-items: center;
  position: relative;
  cursor: pointer;
}
.checkbox input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}
.checkmark {
  width: 16px;
  height: 16px;
  border-radius: 5px;
  border: 1.5px solid var(--border-light);
  background: var(--bg-deep);
  transition: background 0.18s var(--ease-out), border-color 0.18s var(--ease-out);
  position: relative;
}
.checkbox input:checked + .checkmark {
  background: linear-gradient(135deg, var(--accent), var(--accent-soft));
  border-color: transparent;
}
.checkbox input:checked + .checkmark::after {
  content: '';
  position: absolute;
  left: 4.5px;
  top: 1.5px;
  width: 4px;
  height: 8px;
  border: solid #0a0e14;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}
.checkbox:hover .checkmark {
  border-color: var(--accent);
}
</style>