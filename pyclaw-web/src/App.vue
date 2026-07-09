<template>
  <div class="app">
    <aside v-if="state.me" class="sidebar">
      <div class="brand">
        <span class="brand-mark">P</span>
        <div>
          <strong>pyclaw</strong>
          <small>Console</small>
        </div>
      </div>
      <nav>
        <button
          v-for="item in visibleNav"
          :key="item.key"
          :class="{ active: state.view === item.key }"
          @click="setView(item.key)"
        >
          <span>{{ item.icon }}</span>
          {{ item.label }}
        </button>
      </nav>
    </aside>

    <main :class="['main', { centered: !state.me }]">
      <section v-if="!state.me" class="login-panel">
        <div class="login-copy">
          <p class="eyebrow">pyclaw Console</p>
          <h1>登录管理控制台</h1>
          <p>通过 Spring Backend 统一鉴权后访问 Agent、Provider、Channel、审计与用量接口。</p>
          <div class="login-bullets">
            <span>Agent 调用</span>
            <span>Token 管理</span>
            <span>审计追踪</span>
          </div>
        </div>
        <form class="panel form-grid login-card" @submit.prevent="login">
          <div class="panel-title">
            <h2>账户登录</h2>
            <span>Bearer Access</span>
          </div>
          <label class="wide">
            后端地址
            <input v-model="state.apiBase" placeholder="留空表示同源，例如 K3s Ingress" />
          </label>
          <label>
            用户名
            <input v-model="loginForm.username" autocomplete="username" />
          </label>
          <label>
            密码
            <input v-model="loginForm.password" type="password" autocomplete="current-password" />
          </label>
          <button class="primary wide" type="submit" :disabled="state.loading">
            {{ state.loading ? "登录中" : "登录" }}
          </button>
          <p v-if="state.error" class="error wide">{{ state.error }}</p>
        </form>
      </section>

      <template v-else>
        <header class="topbar">
          <div>
            <p class="eyebrow">{{ currentTitle }}</p>
            <h1>{{ currentSubtitle }}</h1>
          </div>
          <div class="userbox">
            <span>{{ state.me.username }}</span>
            <small>{{ state.me.actorType }}</small>
            <button class="ghost" @click="logout">退出</button>
          </div>
        </header>

        <div v-if="state.error" class="toast error">
          {{ state.error }}
          <button @click="state.error = ''">关闭</button>
        </div>
        <div v-if="state.notice" class="toast success">
          {{ state.notice }}
          <button @click="state.notice = ''">关闭</button>
        </div>

        <section v-if="state.view === 'dashboard'" class="stack">
          <div class="metric-grid">
            <article class="metric" :class="{ good: dashboard.health === 'ok' }">
              <span>后端健康</span>
              <strong>{{ dashboard.health }}</strong>
            </article>
            <article class="metric">
              <span>当前用户</span>
              <strong>{{ state.me.username }}</strong>
            </article>
            <article class="metric">
              <span>用量记录</span>
              <strong>{{ usageStats.totalRuns }}</strong>
            </article>
            <article class="metric">
              <span>总 Tokens</span>
              <strong>{{ usageStats.totalTokens }}</strong>
            </article>
          </div>
          <div class="panel">
            <div class="panel-title">
              <h2>权限</h2>
              <button @click="refreshDashboard">刷新</button>
            </div>
            <div class="chips">
              <span v-for="authority in state.me.authorities" :key="authority">{{ authority }}</span>
            </div>
          </div>
        </section>

        <section v-if="state.view === 'agent'" class="two-column">
          <form class="panel form-grid" @submit.prevent="runAgent">
            <div class="panel-title">
              <div>
                <h2>Agent Playground</h2>
                <p>快速验证 Provider、模型与工具权限是否可用。</p>
              </div>
              <button class="primary" type="submit" :disabled="state.loading">
                {{ state.loading ? "运行中" : "运行" }}
              </button>
            </div>
            <label class="wide">
              Prompt
              <textarea v-model="agentForm.prompt" rows="8" />
            </label>
            <label>
              Provider
              <input v-model="agentForm.provider" />
            </label>
            <label>
              Model
              <input v-model="agentForm.model" placeholder="可留空" />
            </label>
            <label>
              Session ID
              <input v-model="agentForm.sessionId" />
            </label>
            <label>
              Tool Profile
              <select v-model="agentForm.toolProfile">
                <option>minimal</option>
                <option>readonly</option>
                <option>coding</option>
                <option>messaging</option>
                <option>full</option>
              </select>
            </label>
          </form>
          <article class="panel result-panel">
            <div class="panel-title">
              <div>
                <h2>响应</h2>
                <p>返回内容与原始 JSON 会保留在当前页面。</p>
              </div>
              <span v-if="agentResult.latencyMs" class="latency">{{ agentResult.latencyMs }} ms</span>
            </div>
            <pre class="answer">{{ agentResult.text || "等待调用结果" }}</pre>
            <details>
              <summary>原始 JSON</summary>
              <pre>{{ pretty(agentResult.raw) }}</pre>
            </details>
          </article>
        </section>

        <section v-if="state.view === 'tokens'" class="stack">
          <form class="panel form-grid" @submit.prevent="createToken">
            <div class="panel-title">
              <div>
                <h2>创建 API Token</h2>
                <p>Token 只会在创建后显示一次，请及时保存。</p>
              </div>
              <button class="primary" type="submit">创建</button>
            </div>
            <label>
              名称
              <input v-model="tokenForm.name" />
            </label>
            <label>
              过期时间
              <input v-model="tokenForm.expiresAt" placeholder="2026-12-31T23:59:59+08:00" />
            </label>
            <label class="wide">
              权限 scopes
              <input v-model="tokenForm.scopes" placeholder="agent:run,token:manage_self" />
            </label>
          </form>
          <DataTable title="API Tokens" :rows="tokens" :columns="tokenColumns">
            <template #actions="{ row }">
              <button class="danger" :disabled="Boolean(row.revokedAt)" @click="revokeToken(row.id)">撤销</button>
            </template>
          </DataTable>
        </section>

        <section v-if="state.view === 'users'" class="stack">
          <form class="panel form-grid" @submit.prevent="createUser">
            <div class="panel-title">
              <div>
                <h2>创建用户</h2>
                <p>为控制台用户分配可访问的权限范围。</p>
              </div>
              <button class="primary" type="submit">创建</button>
            </div>
            <label>
              用户名
              <input v-model="userForm.username" />
            </label>
            <label>
              密码
              <input v-model="userForm.password" type="password" />
            </label>
            <label>
              显示名
              <input v-model="userForm.displayName" />
            </label>
            <label class="wide">
              权限
              <input v-model="userForm.authorities" />
            </label>
          </form>
          <DataTable title="Users" :rows="users" :columns="userColumns">
            <template #actions="{ row }">
              <button class="danger" :disabled="row.status === 'DISABLED'" @click="disableUser(row.id)">禁用</button>
            </template>
          </DataTable>
        </section>

        <section v-if="state.view === 'providers'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveProvider">
            <div class="panel-title">
              <div>
                <h2>{{ providerForm.id ? "编辑 Provider" : "创建 Provider" }}</h2>
                <p>配置模型网关、默认模型与 API Key。</p>
              </div>
              <div>
                <button v-if="providerForm.id" type="button" @click="resetProviderForm">取消编辑</button>
                <button class="primary" type="submit">保存</button>
              </div>
            </div>
            <label>
              名称
              <input v-model="providerForm.name" />
            </label>
            <label>
              类型
              <input v-model="providerForm.providerType" />
            </label>
            <label>
              Base URL
              <input v-model="providerForm.baseUrl" />
            </label>
            <label>
              Model
              <input v-model="providerForm.model" />
            </label>
            <label>
              API Mode
              <select v-model="providerForm.apiMode">
                <option>chat_completions</option>
                <option>responses</option>
              </select>
            </label>
            <label>
              Secret Ref
              <input v-model="providerForm.secretRef" />
            </label>
            <label>
              API Key
              <input
                v-model="providerForm.apiKey"
                type="password"
                autocomplete="off"
                :placeholder="providerForm.apiKeyConfigured ? '已保存，留空表示不修改' : '粘贴 DeepSeek API Key'"
              />
            </label>
            <label v-if="providerForm.id && providerForm.apiKeyConfigured" class="checkline">
              <input v-model="providerForm.clearApiKey" type="checkbox" />
              删除已保存 API Key
            </label>
            <label class="checkline">
              <input v-model="providerForm.enabled" type="checkbox" />
              启用
            </label>
          </form>
          <DataTable title="Providers" :rows="providers" :columns="providerColumns">
            <template #actions="{ row }">
              <button @click="editProvider(row)">编辑</button>
              <button class="danger" @click="deleteProvider(row.id)">删除</button>
            </template>
          </DataTable>
        </section>

        <section v-if="state.view === 'channels'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveChannel">
            <div class="panel-title">
              <div>
                <h2>{{ channelForm.id ? "编辑 Channel" : "创建 Channel" }}</h2>
                <p>维护企业微信、飞书等外部入口配置。</p>
              </div>
              <div>
                <button v-if="channelForm.id" type="button" @click="resetChannelForm">取消编辑</button>
                <button class="primary" type="submit">保存</button>
              </div>
            </div>
            <label>
              类型
              <select v-model="channelForm.channelType">
                <option>wechat</option>
                <option>feishu</option>
              </select>
            </label>
            <label>
              名称
              <input v-model="channelForm.name" />
            </label>
            <label>
              Secret Ref
              <input v-model="channelForm.secretRef" />
            </label>
            <label>
              Reply Mode
              <select v-model="channelForm.replyMode" @change="syncChannelReplyMode">
                <option v-for="mode in channelReplyModes" :key="mode.value" :value="mode.value">
                  {{ mode.label }}
                </option>
              </select>
            </label>
            <label class="checkline">
              <input v-model="channelForm.enabled" type="checkbox" />
              启用
            </label>
            <label class="wide">
              Config JSON
              <textarea v-model="channelForm.configJson" rows="7" />
            </label>
          </form>
          <DataTable title="Channels" :rows="channels" :columns="channelColumns">
            <template #actions="{ row }">
              <button @click="editChannel(row)">编辑</button>
              <button class="danger" @click="deleteChannel(row.id)">删除</button>
            </template>
          </DataTable>
        </section>

        <section v-if="state.view === 'audit'" class="stack">
          <DataTable title="Audit Logs" :rows="auditLogs" :columns="auditColumns" />
        </section>

        <section v-if="state.view === 'usage'" class="stack">
          <div class="metric-grid">
            <article class="metric">
              <span>调用次数</span>
              <strong>{{ usageStats.totalRuns }}</strong>
            </article>
            <article class="metric">
              <span>成功率</span>
              <strong>{{ usageStats.successRate }}%</strong>
            </article>
            <article class="metric">
              <span>总 Tokens</span>
              <strong>{{ usageStats.totalTokens }}</strong>
            </article>
            <article class="metric">
              <span>平均延迟</span>
              <strong>{{ usageStats.avgLatency }} ms</strong>
            </article>
          </div>
          <DataTable title="Usage Records" :rows="usageRecords" :columns="usageColumns" />
        </section>
      </template>
    </main>

    <div v-if="createdToken.token" class="modal-backdrop">
      <section class="modal">
        <p class="eyebrow">API Token 只显示一次</p>
        <h2>{{ createdToken.tokenId }}</h2>
        <pre>{{ createdToken.token }}</pre>
        <div class="modal-actions">
          <button @click="copy(createdToken.token)">复制</button>
          <button class="primary" @click="createdToken.token = ''">我已保存</button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref, watch } from "vue";

const TOKEN_KEY = "pyclaw.console.token";
const BASE_KEY = "pyclaw.console.baseUrl";

const nav = [
  { key: "dashboard", label: "概览", icon: "01" },
  { key: "agent", label: "Agent", icon: "02", authority: "agent:run" },
  { key: "tokens", label: "API Tokens", icon: "03", authority: "token:manage_self" },
  { key: "users", label: "用户", icon: "04", authority: "user:manage" },
  { key: "providers", label: "Providers", icon: "05", authority: "provider:manage" },
  { key: "channels", label: "Channels", icon: "06", authority: "channel:manage" },
  { key: "audit", label: "审计", icon: "07", authority: "audit:read" },
  { key: "usage", label: "用量", icon: "08", authority: "audit:read" }
];

const state = reactive({
  apiBase: localStorage.getItem(BASE_KEY) || "",
  token: localStorage.getItem(TOKEN_KEY) || "",
  me: null,
  view: "dashboard",
  loading: false,
  error: "",
  notice: ""
});

const loginForm = reactive({ username: "admin", password: "" });
const agentForm = reactive({
  prompt: "你好，请用一句话介绍 pyclaw。",
  provider: "openai",
  sessionId: "web-demo",
  toolProfile: "minimal",
  model: ""
});
const tokenForm = reactive({ name: "frontend-token", expiresAt: "", scopes: "agent:run" });
const userForm = reactive({
  username: "",
  password: "",
  displayName: "",
  authorities: "agent:run,token:manage_self"
});
const providerForm = reactive(defaultProviderForm());
const channelForm = reactive(defaultChannelForm());

const dashboard = reactive({ health: "unknown" });
const agentResult = reactive({ text: "", latencyMs: 0, raw: null });
const createdToken = reactive({ tokenId: "", token: "" });

const tokens = ref([]);
const users = ref([]);
const providers = ref([]);
const channels = ref([]);
const auditLogs = ref([]);
const usageRecords = ref([]);

const visibleNav = computed(() => nav.filter((item) => !item.authority || has(item.authority)));
const currentTitle = computed(() => nav.find((item) => item.key === state.view)?.label || "Console");
const channelReplyModes = computed(() => {
  const modes = [{ value: "async_worker", label: "Async Worker" }];
  if (channelForm.channelType === "wechat") {
    modes.unshift({ value: "passive_xml", label: "Passive XML" });
  }
  return modes;
});
const currentSubtitle = computed(() => {
  const map = {
    dashboard: "系统运行状态",
    agent: "调用 pyclaw Agent",
    tokens: "管理个人或管理员 API Token",
    users: "用户与权限",
    providers: "模型 Provider 配置",
    channels: "微信与飞书 Channel 配置",
    audit: "安全审计记录",
    usage: "Agent 调用用量"
  };
  return map[state.view] || "pyclaw Console";
});

const tokenColumns = ["name", "scopes", "expiresAt", "revokedAt", "createdAt", "lastUsedAt"];
const userColumns = ["username", "displayName", "status", "authorities", "createdAt", "updatedAt"];
const providerColumns = ["name", "providerType", "baseUrl", "model", "apiMode", "secretRef", "apiKeyConfigured", "enabled"];
const channelColumns = ["channelType", "name", "configJson", "secretRef", "enabled", "updatedAt"];
const auditColumns = ["createdAt", "actorType", "actorId", "action", "resourceType", "resourceId", "success", "errorMessage"];
const usageColumns = ["createdAt", "userId", "sessionId", "provider", "model", "totalTokens", "success", "latencyMs"];
const DataTable = {
  props: {
    title: { type: String, required: true },
    rows: { type: Array, required: true },
    columns: { type: Array, required: true }
  },
  setup(props, { slots }) {
    const cellValue = (value) => {
      if (value === null || value === undefined || value === "") return "-";
      if (typeof value === "object") return JSON.stringify(value);
      return String(value);
    };

    return () => h("article", { class: "panel table-panel" }, [
      h("div", { class: "panel-title" }, [
        h("h2", props.title),
        h("span", `${props.rows.length} 条`)
      ]),
      h("div", { class: "table-wrap" }, [
        h("table", [
          h("thead", [
            h("tr", [
              ...props.columns.map((column) => h("th", { key: column }, column)),
              slots.actions ? h("th", "操作") : null
            ])
          ]),
          h("tbody", props.rows.length
            ? props.rows.map((row) => h("tr", { key: row.id || JSON.stringify(row) }, [
                ...props.columns.map((column) => h("td", { key: column }, [
                  typeof row[column] === "boolean"
                    ? h("code", String(row[column]))
                    : h("span", cellValue(row[column]))
                ])),
                slots.actions ? h("td", { class: "actions" }, slots.actions({ row })) : null
              ]))
            : [h("tr", [
                h("td", {
                  class: "empty",
                  colspan: props.columns.length + (slots.actions ? 1 : 0)
                }, "暂无数据")
              ])])
        ])
      ])
    ]);
  }
};

const usageStats = computed(() => {
  const rows = usageRecords.value;
  const totalRuns = rows.length;
  const success = rows.filter((item) => item.success).length;
  const totalTokens = rows.reduce((sum, item) => sum + Number(item.totalTokens || 0), 0);
  const latencyRows = rows.filter((item) => Number.isFinite(Number(item.latencyMs)));
  const avgLatency = latencyRows.length
    ? Math.round(latencyRows.reduce((sum, item) => sum + Number(item.latencyMs || 0), 0) / latencyRows.length)
    : 0;
  const successRate = totalRuns ? Math.round((success / totalRuns) * 100) : 0;
  return { totalRuns, successRate, totalTokens, avgLatency };
});

watch(() => channelForm.channelType, () => {
  if (!channelReplyModes.value.some((mode) => mode.value === channelForm.replyMode)) {
    channelForm.replyMode = "async_worker";
  }
  syncChannelReplyMode();
});

onMounted(async () => {
  if (state.token) {
    await loadMe();
  }
});

function defaultProviderForm() {
  return {
    id: "",
    name: "",
    providerType: "openai-compatible",
    baseUrl: "",
    model: "deepseek-chat",
    apiMode: "chat_completions",
    secretRef: "pyclaw-provider-secret",
    apiKey: "",
    clearApiKey: false,
    apiKeyConfigured: false,
    enabled: true
  };
}

function defaultChannelForm() {
  return {
    id: "",
    channelType: "wechat",
    name: "",
    configJson: '{\n  "callbackPath": "/api/webhooks/wechat",\n  "reply_mode": "passive_xml"\n}',
    replyMode: "passive_xml",
    secretRef: "",
    enabled: true
  };
}

function has(authority) {
  return Boolean(state.me?.authorities?.includes(authority));
}

function endpoint(path) {
  const base = state.apiBase.trim().replace(/\/$/, "");
  return `${base}${path}`;
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = headers["Content-Type"] || "application/json";
  }
  if (state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }
  const res = await fetch(endpoint(path), { ...options, headers });
  if (res.status === 401 || res.status === 403) {
    if (path !== "/api/auth/me") {
      throw new Error("未登录、登录已过期或权限不足");
    }
  }
  if (!res.ok) {
    const message = await readError(res);
    throw new Error(message);
  }
  if (res.status === 204) {
    return null;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function readError(res) {
  const text = await res.text();
  if (!text) {
    return `${res.status} ${res.statusText}`;
  }
  try {
    return JSON.parse(text).message || text;
  } catch {
    return text;
  }
}

async function login() {
  await withLoading(async () => {
    localStorage.setItem(BASE_KEY, state.apiBase.trim());
    const data = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: loginForm.username, password: loginForm.password })
    });
    state.token = data.accessToken;
    localStorage.setItem(TOKEN_KEY, state.token);
    await loadMe();
    notice("登录成功");
  });
}

async function loadMe() {
  try {
    state.me = await api("/api/auth/me");
    if (!visibleNav.value.some((item) => item.key === state.view)) {
      state.view = "dashboard";
    }
    await refreshCurrent();
  } catch (error) {
    state.me = null;
    state.token = "";
    localStorage.removeItem(TOKEN_KEY);
    state.error = error.message;
  }
}

function logout() {
  state.me = null;
  state.token = "";
  localStorage.removeItem(TOKEN_KEY);
}

async function setView(view) {
  state.view = view;
  await refreshCurrent();
}

async function refreshCurrent() {
  const tasks = {
    dashboard: refreshDashboard,
    tokens: loadTokens,
    users: loadUsers,
    providers: loadProviders,
    channels: loadChannels,
    audit: loadAudit,
    usage: loadUsage
  };
  if (tasks[state.view]) {
    await tasks[state.view]();
  }
}

async function refreshDashboard() {
  await withError(async () => {
    try {
      const healthPath = state.apiBase.trim() ? "/healthz" : "/backend-healthz";
      const data = await api(healthPath);
      dashboard.health = data?.status || "ok";
    } catch {
      dashboard.health = "unavailable";
    }
    if (has("audit:read")) {
      await loadUsage();
    }
  });
}

async function runAgent() {
  await withLoading(async () => {
    const payload = {
      prompt: agentForm.prompt,
      provider: agentForm.provider || undefined,
      sessionId: agentForm.sessionId || undefined,
      toolProfile: agentForm.toolProfile || undefined,
      model: agentForm.model || undefined
    };
    const data = await api("/api/agent/run", { method: "POST", body: JSON.stringify(payload) });
    agentResult.text = data.text || "";
    agentResult.latencyMs = data.latencyMs || 0;
    agentResult.raw = data;
  });
}

async function loadTokens() {
  await withError(async () => {
    tokens.value = sanitizeRows(await api("/api/tokens"));
  });
}

async function createToken() {
  await withLoading(async () => {
    const data = await api("/api/tokens", {
      method: "POST",
      body: JSON.stringify({
        name: tokenForm.name,
        expiresAt: tokenForm.expiresAt || null,
        scopes: splitCsv(tokenForm.scopes)
      })
    });
    createdToken.tokenId = data.tokenId;
    createdToken.token = data.token;
    await loadTokens();
  });
}

async function revokeToken(id) {
  if (!confirm("确认撤销这个 API Token？")) return;
  await withLoading(async () => {
    await api(`/api/tokens/${id}`, { method: "DELETE" });
    await loadTokens();
    notice("Token 已撤销");
  });
}

async function loadUsers() {
  await withError(async () => {
    users.value = sanitizeRows(await api("/api/users"));
  });
}

async function createUser() {
  await withLoading(async () => {
    await api("/api/users", {
      method: "POST",
      body: JSON.stringify({
        username: userForm.username,
        password: userForm.password,
        displayName: userForm.displayName,
        authorities: userForm.authorities
      })
    });
    Object.assign(userForm, { username: "", password: "", displayName: "", authorities: userForm.authorities });
    await loadUsers();
    notice("用户已创建");
  });
}

async function disableUser(id) {
  if (!confirm("确认禁用这个用户？")) return;
  await withLoading(async () => {
    await api(`/api/users/${id}/disable`, { method: "PUT" });
    await loadUsers();
  });
}

async function loadProviders() {
  await withError(async () => {
    providers.value = await api("/api/providers");
  });
}

function editProvider(row) {
  Object.assign(providerForm, row, { apiKey: "", clearApiKey: false });
}

function resetProviderForm() {
  Object.assign(providerForm, defaultProviderForm());
}

async function saveProvider() {
  await withLoading(async () => {
    const payload = {
      name: providerForm.name,
      providerType: providerForm.providerType,
      baseUrl: providerForm.baseUrl || null,
      model: providerForm.model,
      apiMode: providerForm.apiMode,
      secretRef: providerForm.secretRef || null,
      apiKey: providerForm.apiKey || null,
      clearApiKey: providerForm.clearApiKey,
      enabled: providerForm.enabled
    };
    const path = providerForm.id ? `/api/providers/${providerForm.id}` : "/api/providers";
    const method = providerForm.id ? "PUT" : "POST";
    await api(path, { method, body: JSON.stringify(payload) });
    resetProviderForm();
    await loadProviders();
    notice("Provider 已保存");
  });
}

async function deleteProvider(id) {
  if (!confirm("确认删除这个 Provider？")) return;
  await withLoading(async () => {
    await api(`/api/providers/${id}`, { method: "DELETE" });
    await loadProviders();
  });
}

async function loadChannels() {
  await withError(async () => {
    channels.value = await api("/api/channels");
  });
}

function editChannel(row) {
  let config = {};
  try {
    config = parseConfig(row.configJson || "{}");
  } catch {
    config = {};
  }
  Object.assign(channelForm, {
    ...row,
    replyMode: normalizeReplyMode(config.reply_mode || config.replyMode, row.channelType),
    configJson: formatConfig(row.configJson)
  });
}

function resetChannelForm() {
  Object.assign(channelForm, defaultChannelForm());
}

function normalizeReplyMode(value, channelType) {
  const mode = String(value || (channelType === "wechat" ? "passive_xml" : "async_worker")).replace(/-/g, "_");
  if (mode === "passive_xml" && channelType !== "wechat") return "async_worker";
  return mode === "passive_xml" ? "passive_xml" : "async_worker";
}

function syncChannelReplyMode() {
  let config = {};
  try {
    config = parseConfig(channelForm.configJson || "{}");
  } catch {
    return;
  }
  config.reply_mode = normalizeReplyMode(channelForm.replyMode, channelForm.channelType);
  channelForm.configJson = JSON.stringify(config, null, 2);
}

function channelConfigPayload() {
  const config = parseConfig(channelForm.configJson);
  config.reply_mode = normalizeReplyMode(channelForm.replyMode, channelForm.channelType);
  return config;
}

async function saveChannel() {
  await withLoading(async () => {
    const payload = {
      channelType: channelForm.channelType,
      name: channelForm.name,
      config: channelConfigPayload(),
      secretRef: channelForm.secretRef || null,
      enabled: channelForm.enabled
    };
    const path = channelForm.id ? `/api/channels/${channelForm.id}` : "/api/channels";
    const method = channelForm.id ? "PUT" : "POST";
    await api(path, { method, body: JSON.stringify(payload) });
    resetChannelForm();
    await loadChannels();
    notice("Channel 已保存");
  });
}

async function deleteChannel(id) {
  if (!confirm("确认删除这个 Channel？")) return;
  await withLoading(async () => {
    await api(`/api/channels/${id}`, { method: "DELETE" });
    await loadChannels();
  });
}

async function loadAudit() {
  await withError(async () => {
    auditLogs.value = await api("/api/audit-logs");
  });
}

async function loadUsage() {
  await withError(async () => {
    usageRecords.value = await api("/api/usage-records");
  });
}

function splitCsv(value) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function parseConfig(value) {
  try {
    return value.trim() ? JSON.parse(value) : {};
  } catch {
    throw new Error("Config JSON 格式不正确");
  }
}

function formatConfig(value) {
  if (!value) return "{}";
  try {
    return JSON.stringify(typeof value === "string" ? JSON.parse(value) : value, null, 2);
  } catch {
    return value;
  }
}

function sanitizeRows(rows) {
  return (rows || []).map((row) => {
    const copy = { ...row };
    delete copy.passwordHash;
    delete copy.tokenHash;
    return copy;
  });
}

function pretty(value) {
  return value ? JSON.stringify(value, null, 2) : "{}";
}

async function copy(value) {
  await navigator.clipboard?.writeText(value);
  notice("已复制");
}

async function withLoading(fn) {
  state.loading = true;
  await withError(fn);
  state.loading = false;
}

async function withError(fn) {
  state.error = "";
  try {
    await fn();
  } catch (error) {
    state.error = error.message || String(error);
  } finally {
    state.loading = false;
  }
}

function notice(message) {
  state.notice = message;
  setTimeout(() => {
    if (state.notice === message) state.notice = "";
  }, 3000);
}
</script>

<style>
:root {
  color: #18212f;
  background: #f3f5f7;
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  font-size: 15px;
  line-height: 1.5;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: #f3f5f7;
}

button,
input,
select,
textarea {
  font: inherit;
}

button {
  min-height: 38px;
  border: 1px solid #c8d0da;
  border-radius: 6px;
  background: #ffffff;
  color: #253246;
  cursor: pointer;
  padding: 0.5rem 0.78rem;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

button:hover {
  border-color: #6f8199;
  background: #f8fafc;
}

button:active {
  transform: translateY(1px);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
  transform: none;
}

.primary {
  background: #0f766e;
  border-color: #0f766e;
  color: #ffffff;
  font-weight: 700;
}

.primary:hover {
  background: #115e59;
  border-color: #115e59;
}

.danger {
  color: #a11c25;
  border-color: #efc2c5;
}

.ghost {
  background: transparent;
}

.app {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
}

.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  background: #152130;
  color: #e6edf5;
  padding: 1rem 0.85rem;
  overflow: auto;
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.45rem 0.3rem 1.1rem;
}

.brand-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: #2bb19f;
  color: #ffffff;
  font-weight: 800;
  box-shadow: 0 8px 22px rgba(43, 177, 159, 0.28);
}

.brand strong {
  letter-spacing: 0;
}

.brand small {
  display: block;
  color: #9fb0c3;
}

nav {
  display: grid;
  gap: 0.3rem;
}

nav button {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.62rem;
  background: transparent;
  border-color: transparent;
  color: #cbd5e1;
  text-align: left;
  font-weight: 650;
}

nav button span {
  width: 30px;
  height: 24px;
  flex: 0 0 30px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  background: #25354a;
  color: #d9e4ef;
  font-size: 0.76rem;
  font-weight: 700;
}

nav button.active,
nav button:hover {
  background: #25354a;
  border-color: #334963;
  color: #ffffff;
}

.main {
  min-width: 0;
  padding: 1.25rem;
  overflow: auto;
}

.main.centered {
  grid-column: 1 / -1;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 1.5rem;
}

.login-panel {
  width: min(980px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: 1.4rem;
  align-items: center;
}

.login-copy {
  max-width: 520px;
}

.login-copy h1,
.topbar h1 {
  margin: 0;
  color: #121a27;
  letter-spacing: 0;
}

.login-copy h1 {
  font-size: clamp(2rem, 3.5vw, 3rem);
}

.login-copy p:not(.eyebrow) {
  margin: 0.85rem 0 0;
  color: #405066;
  font-size: 1.02rem;
}

.login-bullets {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
  margin-top: 1.2rem;
}

.login-bullets span,
.latency {
  border: 1px solid #c8d0da;
  border-radius: 999px;
  background: #ffffff;
  color: #405066;
  padding: 0.38rem 0.66rem;
  font-size: 0.84rem;
}

.login-card {
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.08);
}

.eyebrow {
  margin: 0 0 0.25rem;
  color: #66778c;
  font-size: 0.78rem;
  font-weight: 750;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.topbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin: 0 auto 1rem;
  max-width: 1440px;
}

.topbar h1 {
  font-size: clamp(1.65rem, 2vw, 2.35rem);
}

.userbox {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  background: #ffffff;
  border: 1px solid #dce2ea;
  border-radius: 8px;
  padding: 0.45rem;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.04);
}

.userbox span {
  font-weight: 700;
}

.userbox small {
  color: #66778c;
}

.stack,
.two-column {
  max-width: 1440px;
  margin: 0 auto;
}

.stack {
  display: grid;
  gap: 1rem;
}

.two-column {
  display: grid;
  grid-template-columns: minmax(380px, 0.92fr) minmax(440px, 1.08fr);
  gap: 1rem;
  align-items: start;
}

.panel,
.metric {
  background: #ffffff;
  border: 1px solid #dce2ea;
  border-radius: 8px;
  box-shadow: 0 1px 0 rgba(15, 23, 42, 0.03);
}

.panel {
  padding: 1rem;
}

.panel-title {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.panel-title h2 {
  margin: 0;
  color: #162033;
  font-size: 1rem;
}

.panel-title p {
  margin: 0.25rem 0 0;
  color: #66778c;
  font-size: 0.86rem;
}

.panel-title > span {
  color: #66778c;
  font-size: 0.84rem;
  white-space: nowrap;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.85rem;
}

.form-grid .panel-title,
.wide {
  grid-column: 1 / -1;
}

label {
  display: grid;
  gap: 0.35rem;
  color: #405066;
  font-size: 0.9rem;
  font-weight: 650;
}

input,
select,
textarea {
  width: 100%;
  border: 1px solid #c8d0da;
  border-radius: 6px;
  color: #17202d;
  background: #ffffff;
  padding: 0.6rem 0.7rem;
  resize: vertical;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

input:focus,
select:focus,
textarea:focus {
  border-color: #0f766e;
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.14);
  outline: none;
}

textarea {
  min-height: 168px;
}

.checkline {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.checkline input {
  width: auto;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1rem;
}

.metric {
  position: relative;
  min-height: 104px;
  padding: 1rem;
  overflow: hidden;
}

.metric::after {
  content: "";
  position: absolute;
  inset: auto 0 0;
  height: 3px;
  background: #c8d0da;
}

.metric.good::after {
  background: #0f766e;
}

.metric span {
  display: block;
  color: #66778c;
  margin-bottom: 0.45rem;
}

.metric strong {
  color: #121a27;
  font-size: 1.52rem;
  line-height: 1.1;
  overflow-wrap: anywhere;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.chips span {
  border: 1px solid #c8d0da;
  border-radius: 999px;
  padding: 0.35rem 0.65rem;
  background: #f8fafc;
  color: #334155;
  font-size: 0.88rem;
}

.table-panel {
  padding: 0;
  overflow: hidden;
}

.table-panel .panel-title {
  margin: 0;
  padding: 0.95rem 1rem;
  border-bottom: 1px solid #e4e9ef;
}

.table-wrap {
  overflow: auto;
}

.table-wrap::-webkit-scrollbar {
  height: 10px;
}

.table-wrap::-webkit-scrollbar-thumb {
  background: #c8d0da;
  border-radius: 999px;
}

table {
  width: 100%;
  border-collapse: collapse;
  min-width: 860px;
}

th,
td {
  border-bottom: 1px solid #e4e9ef;
  padding: 0.72rem 0.85rem;
  text-align: left;
  vertical-align: top;
}

th {
  color: #66778c;
  font-size: 0.78rem;
  font-weight: 800;
  background: #f8fafc;
  text-transform: uppercase;
}

td {
  max-width: 320px;
  color: #263447;
  overflow-wrap: anywhere;
}

.actions {
  white-space: nowrap;
}

.actions button + button {
  margin-left: 0.4rem;
}

.empty {
  height: 112px;
  text-align: center;
  color: #66778c;
  background: #fbfcfe;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #111827;
  color: #e5e7eb;
  border-radius: 8px;
  padding: 1rem;
  max-height: 520px;
  overflow: auto;
}

.answer {
  min-height: 260px;
}

details {
  margin-top: 1rem;
}

summary {
  cursor: pointer;
  color: #405066;
  margin-bottom: 0.5rem;
  font-weight: 700;
}

.toast {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: center;
  max-width: 1440px;
  margin: 0 auto 1rem;
  border-radius: 8px;
  padding: 0.8rem 1rem;
}

.error {
  color: #9f1d20;
}

.toast.error {
  background: #fff1f1;
  border: 1px solid #e4b6b6;
}

.toast.success {
  background: #edf8f4;
  border: 1px solid #abd8cc;
  color: #116a5b;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: rgba(15, 23, 42, 0.45);
}

.modal {
  width: min(640px, 100%);
  background: #ffffff;
  border-radius: 8px;
  padding: 1rem;
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.25);
}

.modal h2 {
  margin-top: 0;
  font-size: 1rem;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
  margin-top: 1rem;
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .two-column {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .app {
    grid-template-columns: 1fr;
  }

  .sidebar {
    position: sticky;
    top: 0;
    z-index: 10;
    height: auto;
    padding: 0.7rem 0.9rem;
  }

  .brand {
    padding: 0.2rem 0 0.6rem;
  }

  nav {
    display: flex;
    gap: 0.45rem;
    margin: 0 -0.2rem;
    padding: 0 0.2rem 0.15rem;
    overflow-x: auto;
  }

  nav button {
    width: auto;
    flex: 0 0 auto;
    min-width: 116px;
    padding: 0.5rem 0.62rem;
  }

  .main {
    padding: 1rem;
  }

  .login-panel,
  .metric-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .login-panel {
    align-items: stretch;
  }

  .topbar {
    align-items: stretch;
    flex-direction: column;
  }

  .userbox {
    justify-content: space-between;
  }
}

@media (max-width: 560px) {
  :root {
    font-size: 14px;
  }

  .main.centered {
    place-items: start stretch;
    padding: 1rem;
  }

  .login-panel {
    gap: 1rem;
  }

  .login-copy h1,
  .topbar h1 {
    font-size: 1.8rem;
  }

  .panel-title {
    flex-direction: column;
  }

  .panel-title button,
  .panel-title > div + div {
    align-self: stretch;
  }

  .panel-title > div + div {
    display: grid;
    gap: 0.5rem;
  }

  .metric-grid {
    gap: 0.75rem;
  }

  .metric {
    min-height: 92px;
  }

  .modal-actions,
  .toast {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
