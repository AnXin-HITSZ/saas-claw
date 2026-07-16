<template>
  <div class="page">
    <div class="page-header">
      <h1>工具目录</h1>
      <span class="subtitle">查看当前 Claw 运行时可交给 Agent 的工具</span>
    </div>

    <div class="toolbar">
      <div class="tabs">
        <button
          v-for="p in profiles"
          :key="p"
          class="tab"
          :class="{ active: activeProfile === p }"
          @click="activeProfile = p"
        >{{ p }}</button>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="error" class="error-msg">{{ error }}</div>
    <div v-else class="tool-table-wrap">
      <table class="data-table tool-table">
        <thead>
          <tr>
            <th>工具名称</th>
            <th>描述</th>
            <th>分类</th>
            <th>风险</th>
            <th>只读</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="tool in effectiveTools" :key="tool.name">
            <td class="tool-name">{{ tool.name }}</td>
            <td class="tool-desc">{{ tool.description || "-" }}</td>
            <td><span class="tag">{{ tool.category || tool.sectionId || "general" }}</span></td>
            <td><span class="tag" :class="tool.risk">{{ tool.risk || "low" }}</span></td>
            <td>{{ tool.readonly ? "是" : "否" }}</td>
          </tr>
          <tr v-if="effectiveTools.length === 0">
            <td colspan="5" class="no-data">当前策略下暂无可用工具</td>
          </tr>
        </tbody>
      </table>

      <div v-if="deniedTools.length" class="denied-panel">
        <h3>当前不可用</h3>
        <div class="denied-list">
          <span v-for="name in deniedTools" :key="name" class="denied-chip">{{ name }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from "vue";
import { api } from "../api/client.js";

const catalog = ref([]);
const profiles = ref([]);
const effectiveNames = ref([]);
const deniedTools = ref([]);
const loading = ref(true);
const resolving = ref(false);
const error = ref("");
const activeProfile = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [c, p] = await Promise.all([
      api.get("/api/tools/catalog"),
      api.get("/api/tools/profiles"),
    ]);
    catalog.value = c;
    profiles.value = p;
    if (p.length) activeProfile.value = p.includes("coding") ? "coding" : p[0];
    await resolveEffective();
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function resolveEffective() {
  if (!activeProfile.value) return;
  resolving.value = true;
  try {
    const result = await api.post("/api/tools/effective", {
      profile: activeProfile.value,
      deny: [],
      alsoAllow: [],
      readonly: activeProfile.value === "readonly",
    });
    effectiveNames.value = result.effectiveTools || [];
    deniedTools.value = result.deniedTools || [];
  } catch (e) {
    error.value = e.message;
  } finally {
    resolving.value = false;
  }
}

const effectiveTools = computed(() => {
  const names = new Set(effectiveNames.value);
  return catalog.value.filter(tool => names.has(tool.name));
});

watch(activeProfile, () => {
  if (!loading.value) resolveEffective();
});

onMounted(load);
</script>

<style scoped>
.page { max-width: 1100px; }
.page-header { margin-bottom: 16px; }
.page-header h1 { font-size: 24px; }
.subtitle { color: var(--text-secondary); font-size: 14px; margin-left: 12px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.tabs { display: flex; gap: 8px; flex-wrap: wrap; }
.tab {
  padding: 6px 16px; font-size: 13px; color: var(--text-secondary);
  background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 6px;
  transition: all 0.15s;
}
.tab:hover { color: var(--text-primary); }
.tab.active { background: rgba(240,163,58,0.1); color: var(--accent); border-color: var(--accent); }
.tool-table-wrap { overflow-x: auto; }
.tool-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.tool-table th { text-align: left; padding: 10px 12px; background: var(--bg-secondary); color: var(--text-secondary); font-weight: 600; border-bottom: 1px solid var(--border-color); }
.tool-table td { padding: 10px 12px; border-bottom: 1px solid var(--border-color); }
.tool-name { font-family: monospace; font-weight: 600; }
.tool-desc { max-width: 520px; color: var(--text-secondary); }
.tag { font-size: 11px; padding: 1px 8px; border-radius: 10px; background: rgba(240,163,58,0.1); color: var(--accent); }
.tag.medium { background: rgba(210,153,34,0.14); color: var(--warning); }
.tag.high { background: rgba(248,81,73,0.12); color: var(--danger); }
.denied-panel { margin-top: 20px; padding: 16px; border: 1px solid var(--border-color); border-radius: 8px; background: var(--bg-secondary); }
.denied-panel h3 { font-size: 14px; margin-bottom: 10px; color: var(--text-secondary); }
.denied-list { display: flex; flex-wrap: wrap; gap: 8px; }
.denied-chip { font-family: monospace; font-size: 12px; color: var(--text-muted); border: 1px solid var(--border-color); border-radius: 999px; padding: 2px 8px; }
.loading, .error-msg, .no-data { text-align: center; padding: 48px; color: var(--text-secondary); }
.error-msg { color: var(--danger); }
</style>
