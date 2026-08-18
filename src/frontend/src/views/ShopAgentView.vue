<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { shopApi, clawApi, ApiError } from '@/api'
import type { ShopAgentVO, MyAgentInstallationVO, Claw, MissingSkillVO } from '@/types/api'
import BaseModal from '@/components/BaseModal.vue'
import { useToast } from '@/composables/useToast'

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
      showInstall.value = false
    }
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '安装失败')
  } finally {
    installing.value = false
  }
}

async function uninstall(m: MyAgentInstallationVO) {
  if (!confirm(`卸载已安装的 Agent「${m.name}」？`)) return
  try {
    await shopApi.uninstallAgent(m.installation_id)
    toast.success('已卸载')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '卸载失败')
  }
}

const clawName = (id: number) => claws.value.find((c) => c.id === id)?.name || `#${id}`

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">Agent 市场</div>
        <div class="page-sub">浏览他人发布的 Agent，一键安装到你的 Claw。</div>
      </div>
      <div class="row">
        <button class="btn" :class="{ 'btn-primary': tab === 'market' }" @click="tab = 'market'">
          市场
        </button>
        <button class="btn" :class="{ 'btn-primary': tab === 'mine' }" @click="tab = 'mine'">
          我的安装
        </button>
      </div>
    </div>

    <!-- 市场 -->
    <div v-if="tab === 'market'">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!market.length" class="empty">市场暂无 Agent。</div>
      <div v-else class="grid">
        <div v-for="a in market" :key="a.agent_id" class="card shop-card">
          <div class="shop-title">{{ a.name }}</div>
          <div class="mono text-weak">{{ a.alias }}</div>
          <div class="shop-desc">{{ a.description || '暂无描述' }}</div>
          <div class="shop-meta text-weak">
            <span>模型 {{ a.base_model }}</span>
            <span>· 安装 {{ a.installs }}</span>
          </div>
          <div class="shop-meta text-weak">
            <span>发布者 {{ a.publisher_nickname || '#' + a.publisher_id }}</span>
            <span v-if="a.version">· v{{ a.version }}</span>
          </div>
          <button class="btn btn-primary btn-sm" style="margin-top: 10px" @click="openInstall(a)">
            安装
          </button>
        </div>
      </div>
    </div>

    <!-- 我的安装 -->
    <div v-else class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!mine.length" class="empty">还没有安装任何市场 Agent。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>alias</th>
            <th>Claw</th>
            <th>模型</th>
            <th>安装时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in mine" :key="m.installation_id">
            <td>{{ m.name }}</td>
            <td class="mono">{{ m.alias }}</td>
            <td class="text-weak">{{ clawName(m.claw_id) }}</td>
            <td class="text-weak">{{ m.base_model }}</td>
            <td class="text-weak">{{ m.installed_at }}</td>
            <td><button class="btn btn-sm btn-danger" @click="uninstall(m)">卸载</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseModal v-model="showInstall" :title="`安装 · ${installTarget?.name ?? ''}`">
      <div class="form-item">
        <label>安装到哪个 Claw</label>
        <select v-model="targetClawId" class="select">
          <option v-for="c in claws" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div v-if="missing.length" class="alert alert-info">
        以下技能缺失，安装后需自行补齐：
        <ul style="margin: 6px 0 0; padding-left: 18px">
          <li v-for="s in missing" :key="s.skill_id">
            {{ s.name }}
            <span :class="s.installable ? 'tag tag-success' : 'tag tag-danger'">
              {{ s.installable ? '市场可装' : '不可用' }}
            </span>
          </li>
        </ul>
      </div>
      <template #footer>
        <button class="btn" @click="showInstall = false">关闭</button>
        <button class="btn btn-primary" :disabled="installing" @click="doInstall">
          {{ installing ? '安装中…' : '确认安装' }}
        </button>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.shop-card {
  padding: 16px;
}
.shop-title {
  font-size: 16px;
  font-weight: 600;
}
.shop-desc {
  margin: 8px 0;
  color: var(--color-text);
  min-height: 40px;
  font-size: 13px;
}
.shop-meta {
  font-size: 12px;
  display: flex;
  gap: 6px;
  margin-top: 2px;
}
</style>
