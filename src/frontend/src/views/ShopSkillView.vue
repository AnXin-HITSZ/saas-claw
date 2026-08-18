<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { shopApi, ApiError } from '@/api'
import type { ShopSkillVO, MySkillInstallationVO } from '@/types/api'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const tab = ref<'market' | 'mine'>('market')

const market = ref<ShopSkillVO[]>([])
const mine = ref<MySkillInstallationVO[]>([])
const loading = ref(false)
const selected = ref<Set<number>>(new Set())

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
  try {
    await shopApi.installSkill(s.skill_id)
    toast.success('安装成功')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '安装失败')
  }
}

async function installBatch() {
  if (!selected.value.size) return toast.error('请先勾选技能')
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
  }
}

async function uninstall(m: MySkillInstallationVO) {
  const bound = m.bound_agent_count > 0
  const msg = bound
    ? `技能「${m.name}」已被 ${m.bound_agent_count} 个 Agent 绑定，强制卸载会解除绑定。确认？`
    : `卸载技能「${m.name}」？`
  if (!confirm(msg)) return
  try {
    await shopApi.uninstallSkill(m.installation_id, bound)
    toast.success('已卸载')
    await load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '卸载失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">Skill 市场</div>
        <div class="page-sub">浏览并安装他人发布的技能，支持批量安装。</div>
      </div>
      <div class="row">
        <button class="btn" :class="{ 'btn-primary': tab === 'market' }" @click="tab = 'market'">市场</button>
        <button class="btn" :class="{ 'btn-primary': tab === 'mine' }" @click="tab = 'mine'">我的安装</button>
      </div>
    </div>

    <div v-if="tab === 'market'">
      <div class="row" style="margin-bottom: 12px" v-if="market.length">
        <span class="text-weak">已选 {{ selected.size }} 项</span>
        <button class="btn btn-primary btn-sm" :disabled="!selected.size" @click="installBatch">
          批量安装
        </button>
      </div>
      <div class="card">
        <div v-if="loading" class="empty">加载中…</div>
        <div v-else-if="!market.length" class="empty">市场暂无技能。</div>
        <table v-else class="table">
          <thead>
            <tr>
              <th style="width: 40px"></th>
              <th>名称</th>
              <th>描述</th>
              <th>版本</th>
              <th>安装数</th>
              <th style="width: 80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in market" :key="s.skill_id">
              <td>
                <input
                  type="checkbox"
                  :checked="selected.has(s.skill_id)"
                  @change="toggleSelect(s.skill_id)"
                />
              </td>
              <td>{{ s.name }}</td>
              <td class="text-weak">{{ s.description }}</td>
              <td class="text-weak">{{ s.version || '—' }}</td>
              <td class="text-weak">{{ s.installs }}</td>
              <td><button class="btn btn-sm btn-primary" @click="installOne(s)">安装</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else class="card">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!mine.length" class="empty">还没有安装任何市场技能。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>描述</th>
            <th>版本</th>
            <th>已绑定 Agent</th>
            <th>安装时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in mine" :key="m.installation_id">
            <td>{{ m.name }}</td>
            <td class="text-weak">{{ m.description }}</td>
            <td class="text-weak">{{ m.version || '—' }}</td>
            <td>
              <span class="tag" :class="m.bound_agent_count ? 'tag-warning' : ''">
                {{ m.bound_agent_count }}
              </span>
            </td>
            <td class="text-weak">{{ m.installed_at }}</td>
            <td><button class="btn btn-sm btn-danger" @click="uninstall(m)">卸载</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
