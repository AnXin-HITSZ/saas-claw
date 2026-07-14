<template>
  <div class="page">
    <div class="page-header">
      <button class="btn-back" @click="$router.push(`/workspace/claws/${clawId}`)">← Claw 详情</button>
      <h1>Workspace 文件</h1>
      <span class="path-breadcrumb">{{ currentPath }}</span>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="error" class="error-msg">{{ error }}</div>

    <div v-else class="file-manager">
      <!-- File List -->
      <div class="card">
        <h3>文件列表</h3>
        <div v-if="entries.length === 0" class="no-data">目录为空</div>
        <div v-else class="file-list">
          <div v-for="entry in entries" :key="entry.name" class="file-item"
               @click="entry.isDir ? navigateTo(entry.name) : openFile(entry.name)">
            <span class="file-icon">{{ entry.isDir ? '📁' : '📄' }}</span>
            <span class="file-name">{{ entry.name }}</span>
            <span v-if="!entry.isDir" class="file-size">{{ formatSize(entry.size) }}</span>
          </div>
        </div>
      </div>

      <!-- File Editor -->
      <div v-if="selectedFile" class="card">
        <h3>{{ selectedFile }}</h3>
        <textarea v-model="fileContent" rows="15" class="file-editor" />
        <div class="editor-actions">
          <button class="btn-secondary" @click="closeFile">取消</button>
          <button class="btn-primary" @click="saveFile">保存</button>
        </div>
        <p v-if="saveMsg" class="save-msg">{{ saveMsg }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api/client.js";

const route = useRoute();
const clawId = ref(route.params.id);
const currentPath = ref(".");
const entries = ref([]);
const loading = ref(true);
const error = ref("");
const selectedFile = ref(null);
const fileContent = ref("");
const saveMsg = ref("");

async function loadDir(path) {
  loading.value = true;
  error.value = "";
  try {
    const data = await api.get(`/api/claws/${clawId.value}/sandbox/files?path=${encodeURIComponent(path)}`);
    if (Array.isArray(data)) {
      entries.value = data.map(e => ({
        name: typeof e === "string" ? e : e.name || e,
        isDir: typeof e === "object" ? e.is_dir : false,
        size: typeof e === "object" ? e.size : null,
      }));
    } else {
      entries.value = [];
    }
  } catch (e) {
    error.value = "获取文件列表失败: " + e.message;
  } finally {
    loading.value = false;
  }
}

function navigateTo(name) {
  const newPath = currentPath.value === "." ? name : currentPath.value + "/" + name;
  currentPath.value = newPath;
  loadDir(newPath);
}

async function openFile(name) {
  const filePath = currentPath.value === "." ? name : currentPath.value + "/" + name;
  try {
    const data = await api.get(`/api/claws/${clawId.value}/sandbox/files/${encodeURIComponent(filePath)}`);
    selectedFile.value = name;
    fileContent.value = typeof data === "string" ? data : JSON.stringify(data);
    saveMsg.value = "";
  } catch (e) {
    error.value = "读取文件失败: " + e.message;
  }
}

async function saveFile() {
  const filePath = currentPath.value === "." ? selectedFile.value : currentPath.value + "/" + selectedFile.value;
  try {
    await api.put(`/api/claws/${clawId.value}/sandbox/files/${encodeURIComponent(filePath)}`, fileContent.value);
    saveMsg.value = "保存成功";
  } catch (e) {
    saveMsg.value = "保存失败: " + e.message;
  }
}

function closeFile() {
  selectedFile.value = null;
  fileContent.value = "";
  saveMsg.value = "";
}

function formatSize(bytes) {
  if (!bytes) return "—";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1048576).toFixed(1) + " MB";
}

onMounted(() => loadDir("."));
</script>

<style scoped>
.page { max-width: 900px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; flex-wrap: wrap; }
.page-header h1 { font-size: 22px; }
.btn-back { padding: 6px 12px; font-size: 13px; color: var(--text-secondary); background: transparent; border: 1px solid var(--border-color); border-radius: 6px; }
.btn-primary { padding: 8px 20px; font-size: 14px; font-weight: 600; color: #fff; background: var(--accent); border: none; border-radius: 6px; }
.btn-secondary { padding: 8px 20px; font-size: 14px; color: var(--text-secondary); background: var(--bg-tertiary); border: 1px solid var(--border-color); border-radius: 6px; }
.path-breadcrumb { font-size: 13px; color: var(--text-muted); font-family: monospace; }
.file-manager { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.card { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 10px; padding: 20px; }
.card h3 { font-size: 15px; margin-bottom: 12px; }
.file-list { display: flex; flex-direction: column; gap: 4px; }
.file-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; font-size: 13px; cursor: pointer; transition: background 0.15s; }
.file-item:hover { background: var(--bg-tertiary); }
.file-icon { font-size: 16px; }
.file-name { flex: 1; }
.file-size { color: var(--text-muted); font-size: 12px; }
.file-editor { width: 100%; padding: 12px; background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 6px; color: var(--text-primary); font-family: monospace; font-size: 13px; resize: vertical; }
.editor-actions { display: flex; gap: 8px; margin-top: 12px; }
.save-msg { margin-top: 8px; font-size: 13px; color: var(--success); }
.no-data { color: var(--text-muted); font-size: 13px; }
.loading, .error-msg { text-align: center; padding: 48px; color: var(--text-secondary); }
.error-msg { color: var(--danger); }
</style>
