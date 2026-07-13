<template>
  <div :class="['app-shell', { 'auth-shell': !state.me }]">
    <aside v-if="state.me" class="sidebar">
      <div class="brand">
        <b>P</b>
        <span>pyclaw</span>
      </div>
      <nav class="nav-list" aria-label="Console navigation">
        <button
          v-for="item in visibleNav"
          :key="item.key"
          :class="['nav-link', { active: state.view === item.key }]"
          @click="setView(item.key)"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      </nav>
    </aside>

    <main :class="['main', { centered: !state.me }]">
      <section v-if="!state.me" class="cover-shell">
        <header class="cover-header">
          <div class="brand cover-brand">
            <b>P</b>
            <span>pyclaw</span>
          </div>
          <nav class="cover-nav" aria-label="Welcome navigation">
            <button :class="{ active: state.authMode === 'login' }" @click="state.authMode='login'">Sign in</button>
            <button :class="{ active: state.authMode === 'register' }" @click="state.authMode='register'">Register</button>
          </nav>
        </header>

        <section class="cover-hero">
          <div class="cover-copy">
            <p class="cover-kicker">Multi-agent workspace</p>
            <h1>开启你的 Claw 工作空间</h1>
            <p class="cover-lead">创建 Claw，绑定飞书群，把前端、后端、运维、产品、算法等角色 Agent 放进同一个协作入口。</p>
          </div>

          <form v-if="state.authMode === 'login'" class="auth-card cover-card" @submit.prevent="login">
            <h2>Sign in</h2>
            <p>进入控制台，管理你的 Claw、Agent 与飞书绑定。</p>
            <label>Backend URL<input v-model="state.apiBase" placeholder="Same origin" /></label>
            <label>Username<input v-model="loginForm.username" autocomplete="username" /></label>
            <label>Password<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label>
            <button class="btn btn-primary">Sign in</button>
            <p class="auth-switch">还没有账号？<button type="button" @click="state.authMode='register'">立即注册</button></p>
            <p v-if="state.error" class="form-error">{{ state.error }}</p>
          </form>

          <form v-else class="auth-card cover-card" @submit.prevent="register">
            <h2>Create account</h2>
            <p>注册后会获得创建 Claw、运行 Agent 和管理个人 Token 的基础权限。</p>
            <label>Backend URL<input v-model="state.apiBase" placeholder="Same origin" /></label>
            <label>Username<input v-model="registerForm.username" autocomplete="username" minlength="3" maxlength="64" /></label>
            <label>Display name<input v-model="registerForm.displayName" autocomplete="name" /></label>
            <label>Password<input v-model="registerForm.password" type="password" autocomplete="new-password" minlength="8" /></label>
            <button class="btn btn-primary">Create account</button>
            <p class="auth-switch">已有账号？<button type="button" @click="state.authMode='login'">返回登录</button></p>
            <p v-if="state.error" class="form-error">{{ state.error }}</p>
          </form>
        </section>
      </section>

      <template v-else>
        <header class="topbar">
          <div>
            <p>{{ currentTitle }}</p>
            <h1>{{ currentSubtitle }}</h1>
          </div>
          <div class="user-menu">
            <span class="status-dot"></span>
            <b>{{ state.me.username }}</b>
            <button class="btn btn-outline" @click="logout">Logout</button>
          </div>
        </header>

        <div v-if="state.loading" class="loading-bar"></div>
        <div v-if="state.error" class="toast toast-error">{{ state.error }}<button @click="state.error=''">Close</button></div>
        <div v-if="state.notice" class="toast toast-ok">{{ state.notice }}<button @click="state.notice=''">Close</button></div>

        <section v-if="state.view==='dashboard'" class="stack">
          <div class="metrics">
            <article><span>Backend</span><b>{{ dashboard.health }}</b></article>
            <article><span>User</span><b>{{ state.me.username }}</b></article>
            <article><span>Claws</span><b>{{ claws.length }}</b></article>
            <article><span>Runs</span><b>{{ usageStats.totalRuns }}</b></article>
          </div>
          <div class="action-grid">
            <button v-if="has('claw:read')" class="action-card" @click="setView('claws')"><b>开启我的 Claw</b><span>创建 Claw，绑定飞书群，并选择多个角色 Agent。</span></button>
            <button v-if="has('agent:run')" class="action-card" @click="setView('agent')"><b>进入 Web 对话</b><span>直接在网页中发起一次 Agent 会话。</span></button>
            <button v-if="has('channel:manage')" class="action-card" @click="setView('channels')"><b>部署到飞书</b><span>检查飞书 Channel 配置和回调模式。</span></button>
            <button v-if="has('provider:manage')" class="action-card" @click="setView('providers')"><b>Provider 配置</b><span>维护 DeepSeek 或其他模型服务配置。</span></button>
          </div>
          <Panel title="Authorities">
            <div class="chips"><span v-for="a in state.me.authorities" :key="a">{{ a }}</span></div>
          </Panel>
        </section>

        <section v-if="state.view==='claws'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveClaw">
            <h2>{{ clawForm.id?'Edit':'Create' }} Claw</h2>
            <label>Name<input v-model="clawForm.name" /></label>
            <label>Status<select v-model="clawForm.status"><option>active</option><option>paused</option></select></label>
            <label class="span-2">Default Agent<select v-model="clawForm.defaultAgentId"><option value="">None</option><option v-for="a in agents" :key="a.id" :value="a.id">{{ a.agentKey }} - {{ a.name }}</option></select></label>
            <label class="span-2">Description<input v-model="clawForm.description" /></label>
            <label class="check-field"><input v-model="clawForm.feishuEnabled" type="checkbox" /> Feishu enabled</label>
            <label>Feishu Account<input v-model="clawForm.feishuAccountId" placeholder="optional app/account id" /></label>
            <label>Feishu Peer Kind<select v-model="clawForm.feishuPeerKind"><option>group</option><option>direct</option><option>channel</option><option>thread</option></select></label>
            <label>Feishu Peer ID<input v-model="clawForm.feishuPeerId" placeholder="chat_id / open_chat_id" /></label>
            <label class="span-2">Feishu Comment<input v-model="clawForm.feishuComment" /></label>
            <div class="span-2 role-editor">
              <div class="role-editor-head"><h3>Role Agents</h3><button class="btn btn-outline" type="button" @click="addClawRole">Add role</button></div>
              <div v-if="!clawForm.roles.length" class="empty-state">No role agent yet</div>
              <article v-for="(role,index) in clawForm.roles" :key="role.localId" class="role-card">
                <label>Role Key<input v-model="role.roleKey" placeholder="frontend" /></label>
                <label>Display<input v-model="role.displayName" placeholder="前端" /></label>
                <label class="span-2">Agent<select v-model="role.agentId"><option value="">Select</option><option v-for="a in agents" :key="a.id" :value="a.id">{{ a.agentKey }} - {{ a.name }}</option></select></label>
                <label>Mention Aliases<input v-model="role.mentionAliases" placeholder="前端,frontend" /></label>
                <label>Command Prefixes<input v-model="role.commandPrefixes" placeholder="/frontend,/fe" /></label>
                <label>Sort Order<input v-model.number="role.sortOrder" type="number" /></label>
                <label class="check-field"><input v-model="role.defaultRole" type="checkbox" /> Default</label>
                <label class="check-field"><input v-model="role.enabled" type="checkbox" /> Enabled</label>
                <div class="role-actions"><button class="btn btn-danger" type="button" @click="removeClawRole(index)">Remove</button></div>
              </article>
            </div>
            <div class="form-actions"><button class="btn btn-primary">Save Claw</button><button class="btn btn-outline" type="button" @click="resetClawForm">Reset</button></div>
          </form>
          <DataTable title="Claws" :rows="claws" :columns="clawColumns"><template #actions="{row}"><button class="btn btn-outline" @click="editClaw(row)">Edit</button><button class="btn btn-outline" @click="syncClawRoutes(row.id)">Sync routes</button><button class="btn btn-danger" @click="deleteClaw(row.id)">Delete</button></template></DataTable>
        </section>

        <section v-if="state.view==='agent'" class="content-grid">
          <form class="panel form-grid" @submit.prevent="runAgent">
            <h2>Agent Playground</h2>
            <label class="span-2">Prompt<textarea v-model="agentForm.prompt" rows="7" /></label>
            <label>Provider<input v-model="agentForm.provider" /></label>
            <label>Model<input v-model="agentForm.model" /></label>
            <label>Session<input v-model="agentForm.sessionId" /></label>
            <label>Tool Profile<select v-model="agentForm.toolProfile"><option v-for="p in toolProfiles" :key="p">{{ p }}</option></select></label>
            <div class="form-actions"><button class="btn btn-primary">Run</button></div>
          </form>
          <Panel title="Response">
            <pre>{{ agentResult.text || 'Waiting for result' }}</pre>
            <details><summary>Raw JSON</summary><pre>{{ pretty(agentResult.raw) }}</pre></details>
          </Panel>
        </section>

        <section v-if="state.view==='tokens'" class="stack">
          <form class="panel form-grid compact-form" @submit.prevent="createToken">
            <h2>Create Token</h2>
            <label>Name<input v-model="tokenForm.name" /></label>
            <label>Expires<input v-model="tokenForm.expiresAt" /></label>
            <AuthorityPicker v-model="tokenForm.scopes" class="span-2" title="Scopes" />
            <div class="form-actions"><button class="btn btn-primary">Create</button></div>
          </form>
          <DataTable title="Tokens" :rows="tokens" :columns="tokenColumns"><template #actions="{row}"><button class="btn btn-danger" @click="revokeToken(row.id)">Revoke</button></template></DataTable>
        </section>

        <section v-if="state.view==='users'" class="stack">
          <form class="panel form-grid compact-form" @submit.prevent="createUser">
            <h2>Create User</h2>
            <label>Username<input v-model="userForm.username" /></label>
            <label>Password<input v-model="userForm.password" type="password" /></label>
            <label>Display<input v-model="userForm.displayName" /></label>
            <AuthorityPicker v-model="userForm.authorities" class="span-2" title="Authorities" />
            <div class="form-actions"><button class="btn btn-primary">Create</button></div>
          </form>
          <DataTable title="Users" :rows="users" :columns="userColumns"><template #actions="{row}"><button class="btn btn-danger" @click="disableUser(row.id)">Disable</button></template></DataTable>
        </section>

        <section v-if="state.view==='providers'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveProvider">
            <h2>{{ providerForm.id?'Edit':'Create' }} Provider</h2>
            <label>Name<input v-model="providerForm.name" /></label>
            <label>Type<select v-model="providerForm.providerType"><option v-for="type in providerTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></label>
            <label class="span-2">Base URL<input v-model="providerForm.baseUrl" /></label>
            <label>Model<input v-model="providerForm.model" /></label>
            <label>API Mode<select v-model="providerForm.apiMode"><option>chat_completions</option><option>responses</option><option>auto</option></select></label>
            <label>Secret Ref<input v-model="providerForm.secretRef" /></label>
            <label>API Key<input v-model="providerForm.apiKey" type="password" /></label>
            <label class="check-field"><input v-model="providerForm.enabled" type="checkbox" /> Enabled</label>
            <label v-if="providerForm.id&&providerForm.apiKeyConfigured" class="check-field"><input v-model="providerForm.clearApiKey" type="checkbox" /> Clear key</label>
            <div class="form-actions"><button class="btn btn-primary">Save</button><button class="btn btn-outline" type="button" @click="resetProviderForm">Reset</button></div>
          </form>
          <DataTable title="Providers" :rows="providers" :columns="providerColumns"><template #actions="{row}"><button class="btn btn-outline" @click="editProvider(row)">Edit</button><button class="btn btn-danger" @click="deleteProvider(row.id)">Delete</button></template></DataTable>
        </section>

        <section v-if="state.view==='channels'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveChannel">
            <h2>{{ channelForm.id?'Edit':'Create' }} Channel</h2>
            <label>Type<select v-model="channelForm.channelType"><option>wechat</option><option>feishu</option></select></label>
            <label>Name<input v-model="channelForm.name" /></label>
            <label>Secret Ref<input v-model="channelForm.secretRef" /></label>
            <label>Reply Mode<select v-model="channelForm.replyMode"><option v-for="m in channelReplyModes" :key="m.value" :value="m.value">{{ m.label }}</option></select></label>
            <label class="check-field"><input v-model="channelForm.enabled" type="checkbox" /> Enabled</label>
            <label class="span-2">Config JSON<textarea v-model="channelForm.configJson" rows="7" /></label>
            <div class="form-actions"><button class="btn btn-primary">Save</button><button class="btn btn-outline" type="button" @click="resetChannelForm">Reset</button></div>
          </form>
          <DataTable title="Channels" :rows="channels" :columns="channelColumns"><template #actions="{row}"><button class="btn btn-outline" @click="editChannel(row)">Edit</button><button class="btn btn-danger" @click="deleteChannel(row.id)">Delete</button></template></DataTable>
        </section>

        <section v-if="state.view==='agents'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveAgent">
            <h2>{{ agentForm2.id?'Edit':'Create' }} Agent</h2>
            <label>Key<input v-model="agentForm2.agentKey" /></label>
            <label>Name<input v-model="agentForm2.name" /></label>
            <label>Provider<input v-model="agentForm2.provider" /></label>
            <label>Provider Config<select v-model="agentForm2.providerId"><option value="">Environment</option><option v-for="p in providerOptions" :key="p.id" :value="p.id">{{ p.name }} - {{ p.model }}</option></select></label>
            <label>Model<input v-model="agentForm2.model" /></label>
            <label>Workspace<input v-model="agentForm2.workspaceDir" /></label>
            <label>Profile<select v-model="agentForm2.toolProfile"><option v-for="p in toolProfiles" :key="p">{{ p }}</option></select></label>
            <label>Shell Approval<select v-model="agentForm2.shellApproval"><option>deny</option><option>require</option><option>auto</option></select></label>
            <label class="check-field"><input v-model="agentForm2.enabled" type="checkbox" /> Enabled</label>
            <label class="check-field"><input v-model="agentForm2.readonly" type="checkbox" /> Readonly</label>
            <label class="check-field"><input v-model="agentForm2.workspaceOnly" type="checkbox" /> Workspace only</label>
            <label class="check-field"><input v-model="agentForm2.webAccess" type="checkbox" /> Web access</label>
            <label class="span-2">Description<input v-model="agentForm2.description" /></label>
            <label class="span-2">System<textarea v-model="agentForm2.systemPrompt" rows="5" /></label>
            <ToolPicker v-model="agentForm2.toolsAllow" class="span-2" title="Allow" :tools="toolCatalog" :allow-empty="true" />
            <ToolPicker v-model="agentForm2.toolsDeny" class="span-2" title="Deny" :tools="toolCatalog" />
            <ToolPicker v-model="agentForm2.toolsAlsoAllow" class="span-2" title="Also Allow" :tools="toolCatalog" />
            <div class="form-actions"><button class="btn btn-primary">Save</button><button class="btn btn-outline" type="button" @click="resetAgentForm">Reset</button></div>
          </form>
          <DataTable title="Agents" :rows="agents" :columns="agentColumns"><template #actions="{row}"><button class="btn btn-outline" @click="editAgent(row)">Edit</button><button class="btn btn-danger" @click="deleteAgent(row.id)">Delete</button></template></DataTable>
        </section>

        <section v-if="state.view==='tools'" class="stack">
          <form class="panel form-grid" @submit.prevent="previewTools">
            <h2>Effective Tools</h2>
            <label>Profile<select v-model="toolForm.profile"><option v-for="p in toolProfiles" :key="p">{{ p }}</option></select></label>
            <label>Allow<input v-model="toolForm.allow" /></label>
            <label>Deny<input v-model="toolForm.deny" /></label>
            <label>Also Allow<input v-model="toolForm.alsoAllow" /></label>
            <label class="check-field"><input v-model="toolForm.readonly" type="checkbox" /> Readonly</label>
            <label class="span-2">Effective<textarea :value="toolPreview.effectiveTools.join(', ')" rows="4" readonly /></label>
            <div class="form-actions"><button class="btn btn-primary">Preview</button></div>
          </form>
          <DataTable title="Tool Catalog" :rows="toolCatalog" :columns="toolColumns" />
        </section>

        <section v-if="state.view==='routes'" class="stack">
          <form class="panel form-grid" @submit.prevent="saveRoute">
            <h2>{{ routeForm.id?'Edit':'Create' }} Route</h2>
            <label class="span-2">Agent<select v-model="routeForm.agentId"><option value="">Select</option><option v-for="a in agents" :key="a.id" :value="a.id">{{ a.agentKey }} - {{ a.name }}</option></select></label>
            <label>Priority<input v-model.number="routeForm.priority" type="number" /></label>
            <label>Claw ID<input v-model="routeForm.clawId" /></label>
            <label>Channel<input v-model="routeForm.channel" /></label>
            <label>Account<input v-model="routeForm.accountId" /></label>
            <label>Peer Kind<select v-model="routeForm.peerKind"><option value="">Any</option><option>direct</option><option>group</option><option>channel</option><option>thread</option></select></label>
            <label>Peer ID<input v-model="routeForm.peerId" /></label>
            <label>Mention Aliases<input v-model="routeForm.mentionAliases" /></label>
            <label>Command Prefixes<input v-model="routeForm.commandPrefixes" /></label>
            <label>Sender IDs<input v-model="routeForm.senderIds" /></label>
            <label>DM Scope<select v-model="routeForm.dmScope"><option>main</option><option>per-peer</option><option>per-channel-peer</option><option>per-account-channel-peer</option></select></label>
            <label class="check-field"><input v-model="routeForm.enabled" type="checkbox" /> Enabled</label>
            <label class="span-2">Comment<input v-model="routeForm.comment" /></label>
            <div class="form-actions"><button class="btn btn-primary">Save</button><button class="btn btn-outline" type="button" @click="resetRouteForm">Reset</button></div>
          </form>
          <DataTable title="Routes" :rows="routes" :columns="routeColumns"><template #actions="{row}"><button class="btn btn-outline" @click="editRoute(row)">Edit</button><button class="btn btn-danger" @click="deleteRoute(row.id)">Delete</button></template></DataTable>
        </section>

        <section v-if="state.view==='audit'" class="stack"><DataTable title="Audit" :rows="auditLogs" :columns="auditColumns" /></section>
        <section v-if="state.view==='usage'" class="stack"><DataTable title="Usage" :rows="usageRecords" :columns="usageColumns" /></section>
      </template>
    </main>

    <div v-if="createdToken.token" class="modal">
      <section>
        <h2>{{ createdToken.tokenId }}</h2>
        <pre>{{ createdToken.token }}</pre>
        <div class="form-actions"><button class="btn btn-outline" @click="copy(createdToken.token)">Copy</button><button class="btn btn-primary" @click="createdToken.token=''">Saved</button></div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue';
const TOKEN_KEY='pyclaw.console.token', BASE_KEY='pyclaw.console.baseUrl';
const nav=[['dashboard','Home','01'],['claws','Claws','02','claw:read'],['agent','Run','03','agent:run'],['tokens','Tokens','04','token:manage_self'],['users','Users','05','user:manage'],['providers','Providers','06','provider:manage'],['channels','Channels','07','channel:manage'],['agents','Agents','08','agent:read'],['tools','Tools','09','tool:catalog:read'],['routes','Routes','10','agent:route:manage'],['audit','Audit','11','audit:read'],['usage','Usage','12','audit:read']].map(([key,label,icon,authority])=>({key,label,icon,authority}));
const providerTypes=[
  {value:'openai-compatible',label:'OpenAI Compatible'},
  {value:'openai',label:'OpenAI'},
  {value:'mock',label:'Mock'}
];
const authorityGroups=[
  {title:'用户与 Token',items:['user:manage','token:manage_self']},
  {title:'Provider 与 Channel',items:['provider:manage','channel:manage']},
  {title:'Claw',items:['claw:read','claw:create','claw:update','claw:delete']},
  {title:'Agent',items:['agent:read','agent:create','agent:update','agent:delete','agent:run','agent:route:manage']},
  {title:'工具授权',items:['tool:catalog:read','tool:grant:minimal','tool:grant:readonly','tool:grant:messaging','tool:grant:coding','tool:grant:full','tool:grant:shell','tool:grant:web','tool:grant:host']},
  {title:'审计',items:['audit:read','approval:resolve']}
];
const state=reactive({apiBase:localStorage.getItem(BASE_KEY)||'',token:localStorage.getItem(TOKEN_KEY)||'',me:null,view:'dashboard',authMode:'login',loading:false,error:'',notice:''});
const loginForm=reactive({username:'admin',password:''}), registerForm=reactive({username:'',password:'',displayName:''}), dashboard=reactive({health:'unknown'}), createdToken=reactive({tokenId:'',token:''});
const agentForm=reactive({prompt:'hello',provider:'openai',sessionId:'web-demo',toolProfile:'minimal',model:''}), agentResult=reactive({text:'',raw:null});
const tokenForm=reactive({name:'frontend-token',expiresAt:'',scopes:'agent:run'}), userForm=reactive({username:'',password:'',displayName:'',authorities:'agent:run,agent:read,token:manage_self'});
const providerForm=reactive(defProvider()), channelForm=reactive(defChannel()), agentForm2=reactive(defAgent()), clawForm=reactive(defClaw()), toolForm=reactive({profile:'coding',allow:'',deny:'',alsoAllow:'',readonly:false}), routeForm=reactive(defRoute());
const tokens=ref([]),users=ref([]),providers=ref([]),providerOptions=ref([]),channels=ref([]),agents=ref([]),claws=ref([]),toolCatalog=ref([]),routes=ref([]),auditLogs=ref([]),usageRecords=ref([]),toolProfiles=ref(['minimal','readonly','messaging','coding','full']);
const toolPreview=reactive({effectiveTools:[],deniedTools:[]});
const visibleNav=computed(()=>nav.filter(i=>!i.authority||has(i.authority))), currentTitle=computed(()=>nav.find(i=>i.key===state.view)?.label||'Console'), currentSubtitle=computed(()=>({dashboard:'Workspace',claws:'My Claws',agent:'Run agent',tokens:'API tokens',users:'Users',providers:'Providers',channels:'Channels',agents:'Agent registry',tools:'Tool policy preview',routes:'Route bindings',audit:'Audit logs',usage:'Usage records'}[state.view]||''));
const channelReplyModes=computed(()=>channelForm.channelType==='wechat'?[{value:'passive_xml',label:'Passive XML'},{value:'async_worker',label:'Async Worker'}]:[{value:'async_worker',label:'Async Worker'}]);
const usageStats=computed(()=>({totalRuns:usageRecords.value.length,totalTokens:usageRecords.value.reduce((s,r)=>s+Number(r.totalTokens||0),0)}));
const tokenColumns=['name','scopes','expiresAt','revokedAt','createdAt'], userColumns=['username','displayName','status','authorities'], providerColumns=['name','providerType','baseUrl','model','apiMode','apiKeyConfigured','enabled'], channelColumns=['channelType','name','secretRef','enabled','updatedAt'];
const agentColumns=['agentKey','name','providerConfigName','provider','model','workspaceDir','enabled'], clawColumns=['name','status','roleCount','feishuBinding','defaultAgentName','updatedAt'], toolColumns=['name','sectionId','profiles','tags','risk'], routeColumns=['priority','clawId','agentKey','channel','accountId','peerKind','peerId','mentionAliases','commandPrefixes','dmScope','enabled'], auditColumns=['createdAt','actorType','actorId','action','resourceType','resourceId','success'], usageColumns=['createdAt','sessionId','provider','model','totalTokens','success','latencyMs'];
const Panel={props:{title:String},setup(p,{slots}){return()=>h('article',{class:'panel'},[h('h2',p.title),slots.default?.()])}};
const DataTable={props:{title:String,rows:Array,columns:Array},setup(p,{slots}){const cell=v=>v==null||v===''?'-':typeof v==='object'?JSON.stringify(v):String(v);return()=>h('article',{class:'panel table'},[h('h2',`${p.title} (${p.rows.length})`),h('div',{class:'scroll'},[h('table',[h('thead',[h('tr',[...p.columns.map(c=>h('th',c)),slots.actions?h('th','Actions'):null])]),h('tbody',p.rows.length?p.rows.map(r=>h('tr',{key:r.id||JSON.stringify(r)},[...p.columns.map(c=>h('td',cell(r[c]))),slots.actions?h('td',slots.actions({row:r})):null])):[h('tr',[h('td',{colspan:p.columns.length+(slots.actions?1:0)},'No data')])])])])])}};
const AuthorityPicker={props:{modelValue:{type:String,default:''},title:{type:String,default:'Authorities'}},emits:['update:modelValue'],setup(p,{emit,attrs}){const values=()=>new Set(csv(p.modelValue));const update=set=>emit('update:modelValue',[...set].sort().join(','));const toggle=item=>{const set=values();set.has(item)?set.delete(item):set.add(item);update(set)};const setGroup=(items,checked)=>{const set=values();items.forEach(item=>checked?set.add(item):set.delete(item));update(set)};return()=>{const selected=values();return h('section',{class:['picker',attrs.class]},[h('div',{class:'picker-head'},[h('span',p.title),h('code',selected.size?`${selected.size} selected`:'none')]),h('div',{class:'picker-groups'},authorityGroups.map(group=>{const all=group.items.every(item=>selected.has(item));return h('fieldset',{class:'picker-group',key:group.title},[h('legend',[group.title,h('button',{type:'button',class:'link-btn',onClick:()=>setGroup(group.items,!all)},all?'Clear':'All')]),h('div',{class:'picker-options'},group.items.map(item=>h('label',{class:'choice',key:item},[h('input',{type:'checkbox',checked:selected.has(item),onChange:()=>toggle(item)}),h('span',item)])))])}))])}}};
const ToolPicker={props:{modelValue:{type:String,default:''},title:{type:String,default:'Tools'},tools:{type:Array,default:()=>[]},allowEmpty:{type:Boolean,default:false}},emits:['update:modelValue'],setup(p,{emit,attrs}){const values=()=>new Set(csv(p.modelValue));const update=set=>emit('update:modelValue',[...set].sort().join(','));const groups=()=>{const out={};for(const tool of p.tools||[]){const key=tool.sectionId||'other';(out[key] ||= []).push(tool)}return out};const toggle=name=>{const set=values();set.has(name)?set.delete(name):set.add(name);update(set)};const setGroup=(tools,checked)=>{const set=values();tools.forEach(tool=>checked?set.add(tool.name):set.delete(tool.name));update(set)};const groupTool=section=>({name:`group:${section}`,description:`Select every tool in the ${section} section through the backend group policy.`,risk:'low',profiles:['group'],isGroup:true});return()=>{const selected=values();const grouped=groups();const sections=Object.keys(grouped);return h('section',{class:['picker',attrs.class]},[h('div',{class:'picker-head'},[h('span',p.title),h('code',selected.size?`${selected.size} selected`:p.allowEmpty?'inherit profile':'none')]),sections.length?h('div',{class:'picker-groups'},sections.map(section=>{const tools=grouped[section];const all=tools.every(tool=>selected.has(tool.name));const choices=[groupTool(section),...tools];return h('fieldset',{class:'picker-group',key:section},[h('legend',[section,h('button',{type:'button',class:'link-btn',onClick:()=>setGroup(tools,!all)},all?'Clear':'All')]),h('div',{class:'picker-options'},choices.map(tool=>h('label',{class:['choice','tool-choice',tool.isGroup?'risk-low':`risk-${tool.risk}`],key:tool.name,title:tool.description},[h('input',{type:'checkbox',checked:selected.has(tool.name),onChange:()=>toggle(tool.name)}),h('span',[h('b',tool.name),h('small',tool.isGroup?'group':`${tool.risk} · ${(tool.profiles||[]).join('/')}`)])])))])})):h('p',{class:'picker-empty'},'Open Tools page once or refresh Agents after tool catalog is available.')])}}};onMounted(()=>{if(state.token)loadMe()});
function defProvider(){return{id:'',name:'',providerType:'openai-compatible',baseUrl:'https://api.deepseek.com',model:'deepseek-chat',apiMode:'chat_completions',secretRef:'pyclaw-provider-secret',apiKey:'',clearApiKey:false,apiKeyConfigured:false,enabled:true}}
function defChannel(){return{id:'',channelType:'wechat',name:'',configJson:'{\n  "callbackPath": "/api/webhooks/wechat",\n  "reply_mode": "passive_xml"\n}',replyMode:'passive_xml',secretRef:'',enabled:true}}
function defAgent(){return{id:'',agentKey:'default',name:'Default Agent',description:'',enabled:true,providerId:'',provider:'openai',model:'',systemPrompt:'',workspaceDir:'',runtimeType:'agent_session',toolProfile:'messaging',toolsAllow:'',toolsDeny:'',toolsAlsoAllow:'',workspaceOnly:true,readonly:false,shellApproval:'deny',webAccess:false}}
function defClaw(){return{id:'',name:'My Claw',description:'',status:'active',defaultAgentId:'',feishuEnabled:true,feishuAccountId:'',feishuPeerKind:'group',feishuPeerId:'',feishuComment:'',roles:[defClawRole(0)]}}
function defClawRole(index=0){return{localId:String(Date.now())+'-'+String(Math.random()),roleKey:index?'role-'+(index+1):'default',displayName:index?'Role '+(index+1):'默认',agentId:'',mentionAliases:'',commandPrefixes:'',defaultRole:index===0,enabled:true,sortOrder:index*10}}
function defRoute(){return{id:'',enabled:true,priority:0,clawId:'',agentId:'',channel:'feishu',accountId:'',peerKind:'',peerId:'',mentionAliases:'',commandPrefixes:'',senderIds:'',dmScope:'per-account-channel-peer',comment:''}}
function has(a){return Boolean(state.me?.authorities?.includes(a))} function ep(p){return`${state.apiBase.trim().replace(/\/$/,'')}${p}`}
async function api(path,opt={}){const headers={...(opt.headers||{})};if(!(opt.body instanceof FormData))headers['Content-Type']=headers['Content-Type']||'application/json';if(state.token)headers.Authorization=`Bearer ${state.token}`;const r=await fetch(ep(path),{...opt,headers});if(!r.ok)throw new Error(await err(r));if(r.status===204)return null;const t=await r.text();return t?JSON.parse(t):null}
async function err(r){const t=await r.text();try{const d=JSON.parse(t);return d.message||d.detail||t}catch{return t||`${r.status} ${r.statusText}`}}
async function login(){await load(async()=>{localStorage.setItem(BASE_KEY,state.apiBase.trim());const d=await api('/api/auth/login',{method:'POST',body:JSON.stringify({username:loginForm.username,password:loginForm.password})});state.token=d.accessToken;localStorage.setItem(TOKEN_KEY,state.token);await loadMe()})}
async function register(){await load(async()=>{localStorage.setItem(BASE_KEY,state.apiBase.trim());const d=await api('/api/auth/register',{method:'POST',body:JSON.stringify({username:registerForm.username,password:registerForm.password,displayName:registerForm.displayName||null})});state.token=d.accessToken;localStorage.setItem(TOKEN_KEY,state.token);Object.assign(registerForm,{username:'',password:'',displayName:''});await loadMe()})}
async function loadMe(){try{state.me=await api('/api/auth/me');await refresh()}catch(e){state.me=null;state.token='';localStorage.removeItem(TOKEN_KEY);state.error=e.message}}
function logout(){state.me=null;state.token='';localStorage.removeItem(TOKEN_KEY)} async function setView(v){state.view=v;await refresh()}
async function refresh(){const m={dashboard:dash,claws:loadClaws,tokens:loadTokens,users:loadUsers,providers:loadProviders,channels:loadChannels,agents:loadAgents,tools:loadTools,routes:loadRoutes,audit:()=>fetchRows('/api/audit-logs',auditLogs),usage:()=>fetchRows('/api/usage-records',usageRecords)};if(m[state.view])await m[state.view]()}
async function dash(){await safe(async()=>{try{dashboard.health=(await api(state.apiBase.trim()?'/healthz':'/backend-healthz'))?.status||'ok'}catch{dashboard.health='unavailable'}if(has('claw:read'))await loadClaws();if(has('audit:read'))await fetchRows('/api/usage-records',usageRecords)})}
async function runAgent(){await load(async()=>{const d=await api('/api/agent/run',{method:'POST',body:JSON.stringify({prompt:agentForm.prompt,provider:agentForm.provider,sessionId:agentForm.sessionId,toolProfile:agentForm.toolProfile,model:agentForm.model||undefined})});agentResult.text=d.text||'';agentResult.raw=d})}
async function fetchRows(path,refv){await safe(async()=>{refv.value=await api(path)})} async function loadTokens(){return fetchRows('/api/tokens',tokens)} async function loadUsers(){return fetchRows('/api/users',users)} async function loadProviderOptions(){return fetchRows('/api/providers/options',providerOptions)} async function loadProviders(){await fetchRows('/api/providers',providers);providerOptions.value=providers.value.map(p=>({id:p.id,name:p.name,providerType:p.providerType,model:p.model,apiMode:p.apiMode,enabled:p.enabled}))} async function loadChannels(){return fetchRows('/api/channels',channels)}
async function loadClaws(){await safe(async()=>{if(has('agent:read')&&!agents.value.length){try{agents.value=(await api('/api/agents')).map(enrichAgent)}catch{agents.value=[]}}claws.value=(await api('/api/claws')).map(enrichClaw)})}
function enrichClaw(claw){const roles=claw.roles||[];const defaultRole=roles.find(r=>r.defaultRole)||roles[0];const defaultAgentId=claw.defaultAgentId||defaultRole?.agentId;return{...claw,roleCount:roles.length,feishuBinding:claw.feishuEnabled?`${claw.feishuPeerKind||'group'}:${claw.feishuPeerId||'-'}`:'disabled',defaultAgentName:agentLabel(defaultAgentId)}}
function agentLabel(id){if(!id)return '-';const agent=agents.value.find(item=>item.id===id);return agent?`${agent.agentKey} - ${agent.name}`:id}
function resetClawForm(){Object.assign(clawForm,defClaw())}
function editClaw(row){Object.assign(clawForm,{...defClaw(),...row,roles:(row.roles||[]).map((role,index)=>({...defClawRole(index),...role,mentionAliases:(role.mentionAliases||[]).join(','),commandPrefixes:(role.commandPrefixes||[]).join(',')}))})}
function addClawRole(){clawForm.roles.push(defClawRole(clawForm.roles.length))}
function removeClawRole(index){clawForm.roles.splice(index,1)}
function clawPayload(){const roles=clawForm.roles.filter(role=>role.agentId&&role.roleKey&&role.displayName).map((role,index)=>({id:role.id||null,agentId:role.agentId,roleKey:role.roleKey,displayName:role.displayName,mentionAliases:csv(role.mentionAliases),commandPrefixes:csv(role.commandPrefixes),defaultRole:role.defaultRole,enabled:role.enabled,sortOrder:role.sortOrder??index}));return{name:clawForm.name,description:clawForm.description||null,status:clawForm.status||'active',defaultAgentId:clawForm.defaultAgentId||null,feishuEnabled:clawForm.feishuEnabled,feishuAccountId:clawForm.feishuAccountId||null,feishuPeerKind:clawForm.feishuPeerKind||'group',feishuPeerId:clawForm.feishuPeerId||null,feishuComment:clawForm.feishuComment||null,roles}}
async function saveClaw(){await load(async()=>{await api(clawForm.id?`/api/claws/${clawForm.id}`:'/api/claws',{method:clawForm.id?'PUT':'POST',body:JSON.stringify(clawPayload())});resetClawForm();await loadClaws();if(has('agent:route:manage'))await loadRoutes()})}
async function syncClawRoutes(id){await load(async()=>{await api(`/api/claws/${id}/sync-routes`,{method:'POST'});notice('Claw routes synced');await loadClaws();if(has('agent:route:manage'))await loadRoutes()})}
async function deleteClaw(id){if(confirm('Delete Claw?'))await load(async()=>{await api(`/api/claws/${id}`,{method:'DELETE'});await loadClaws();if(has('agent:route:manage'))await loadRoutes()})}
async function createToken(){await load(async()=>{const d=await api('/api/tokens',{method:'POST',body:JSON.stringify({name:tokenForm.name,expiresAt:tokenForm.expiresAt||null,scopes:csv(tokenForm.scopes)})});createdToken.tokenId=d.tokenId;createdToken.token=d.token;await loadTokens()})}
async function revokeToken(id){if(confirm('Revoke token?'))await load(async()=>{await api(`/api/tokens/${id}`,{method:'DELETE'});await loadTokens()})}
async function createUser(){await load(async()=>{await api('/api/users',{method:'POST',body:JSON.stringify(userForm)});Object.assign(userForm,{username:'',password:'',displayName:'',authorities:userForm.authorities});await loadUsers()})} async function disableUser(id){if(confirm('Disable user?'))await load(async()=>{await api(`/api/users/${id}/disable`,{method:'PUT'});await loadUsers()})}
function editProvider(r){Object.assign(providerForm,r,{apiKey:'',clearApiKey:false})} function resetProviderForm(){Object.assign(providerForm,defProvider())}
async function saveProvider(){await load(async()=>{const p={name:providerForm.name,providerType:providerForm.providerType,baseUrl:providerForm.baseUrl||null,model:providerForm.model,apiMode:providerForm.apiMode,secretRef:providerForm.secretRef||null,apiKey:providerForm.apiKey||null,clearApiKey:providerForm.clearApiKey,enabled:providerForm.enabled};await api(providerForm.id?`/api/providers/${providerForm.id}`:'/api/providers',{method:providerForm.id?'PUT':'POST',body:JSON.stringify(p)});resetProviderForm();await loadProviders();await loadProviderOptions()})} async function deleteProvider(id){if(confirm('Delete provider?'))await load(async()=>{await api(`/api/providers/${id}`,{method:'DELETE'});await loadProviders();await loadProviderOptions()})}
function editChannel(r){const cfg=parse(r.configJson||'{}');Object.assign(channelForm,{...r,replyMode:cfg.reply_mode||'async_worker',configJson:fmt(r.configJson)})} function resetChannelForm(){Object.assign(channelForm,defChannel())}
async function saveChannel(){await load(async()=>{const cfg=parse(channelForm.configJson);cfg.reply_mode=channelForm.replyMode;await api(channelForm.id?`/api/channels/${channelForm.id}`:'/api/channels',{method:channelForm.id?'PUT':'POST',body:JSON.stringify({channelType:channelForm.channelType,name:channelForm.name,config:cfg,secretRef:channelForm.secretRef||null,enabled:channelForm.enabled})});resetChannelForm();await loadChannels()})} async function deleteChannel(id){if(confirm('Delete channel?'))await load(async()=>{await api(`/api/channels/${id}`,{method:'DELETE'});await loadChannels()})}
async function loadAgents(){await safe(async()=>{if(!providerOptions.value.length)await loadProviderOptions();if(!toolCatalog.value.length)await loadToolCatalogOnly();const rows=await api('/api/agents');agents.value=rows.map(enrichAgent)})} function enrichAgent(agent){return{...agent,providerConfigName:providerOptionName(agent.providerId)}} function providerOptionName(id){if(!id)return 'Environment';const p=providerOptions.value.find(x=>x.id===id);return p?p.name+' ('+p.model+')':'Unknown provider'} function resetAgentForm(){Object.assign(agentForm2,defAgent())}
function editAgent(r){const p=r.toolPolicy||{};Object.assign(agentForm2,{...defAgent(),...r,toolProfile:p.profile||'messaging',toolsAllow:(p.toolsAllow||[]).join(','),toolsDeny:(p.toolsDeny||[]).join(','),toolsAlsoAllow:(p.toolsAlsoAllow||[]).join(','),workspaceOnly:p.workspaceOnly??true,readonly:p.readonly??false,shellApproval:p.shellApproval||'deny',webAccess:p.webAccess??false})}
function agentPayload(){return{agentKey:agentForm2.agentKey,name:agentForm2.name,description:agentForm2.description||null,enabled:agentForm2.enabled,providerId:agentForm2.providerId||null,provider:agentForm2.provider||null,model:agentForm2.model||null,systemPrompt:agentForm2.systemPrompt||null,workspaceDir:agentForm2.workspaceDir||null,runtimeType:agentForm2.runtimeType,toolPolicy:{profile:agentForm2.toolProfile,toolsAllow:empty(agentForm2.toolsAllow),toolsDeny:csv(agentForm2.toolsDeny),toolsAlsoAllow:csv(agentForm2.toolsAlsoAllow),workspaceOnly:agentForm2.workspaceOnly,readonly:agentForm2.readonly,shellApproval:agentForm2.shellApproval,webAccess:agentForm2.webAccess}}}
async function saveAgent(){await load(async()=>{await api(agentForm2.id?`/api/agents/${agentForm2.id}`:'/api/agents',{method:agentForm2.id?'PUT':'POST',body:JSON.stringify(agentPayload())});resetAgentForm();await loadAgents()})}
async function deleteAgent(id){
  await load(async()=>{
    const agent=agents.value.find(item=>item.id===id);
    const allRoutes=await api('/api/route-bindings');
    const relatedRoutes=allRoutes.filter(route=>route.agentId===id);
    const agentLabel=agent?`${agent.agentKey} - ${agent.name}`:id;
    if(relatedRoutes.length){
      const ok=confirm(`同时删除该 Agent 对应的 Route？\n\nAgent: ${agentLabel}\nRoute 数量: ${relatedRoutes.length}\n\n点击确认后将先删除这些 Route，再删除 Agent。`);
      if(!ok)return;
    }else if(!confirm(`Delete agent?\n\nAgent: ${agentLabel}`)){
      return;
    }
    for(const route of relatedRoutes){
      await api(`/api/route-bindings/${route.id}`,{method:'DELETE'});
    }
    await api(`/api/agents/${id}`,{method:'DELETE'});
    await loadAgents();
    routes.value=routes.value.filter(route=>route.agentId!==id);
    notice(relatedRoutes.length?`Deleted agent and ${relatedRoutes.length} route(s).`:'Deleted agent.');
  })
}
async function loadToolCatalogOnly(){toolCatalog.value=await api('/api/tools/catalog')} async function loadTools(){await safe(async()=>{toolProfiles.value=await api('/api/tools/profiles');await loadToolCatalogOnly();await previewTools()})} async function previewTools(){await safe(async()=>{const d=await api('/api/tools/effective',{method:'POST',body:JSON.stringify({profile:toolForm.profile,allow:empty(toolForm.allow),deny:csv(toolForm.deny),alsoAllow:csv(toolForm.alsoAllow),readonly:toolForm.readonly})});toolPreview.effectiveTools=d.effectiveTools||[];toolPreview.deniedTools=d.deniedTools||[]})}
async function loadRoutes(){await safe(async()=>{if(!agents.value.length)await loadAgents();routes.value=await api('/api/route-bindings')})} function resetRouteForm(){Object.assign(routeForm,defRoute())} function editRoute(r){Object.assign(routeForm,{...defRoute(),...r,mentionAliases:(r.mentionAliases||[]).join(','),commandPrefixes:(r.commandPrefixes||[]).join(','),senderIds:(r.senderIds||[]).join(',')})}
function routePayload(){return{enabled:routeForm.enabled,priority:routeForm.priority,clawId:routeForm.clawId||null,agentId:routeForm.agentId,channel:routeForm.channel||null,accountId:routeForm.accountId||null,peerKind:routeForm.peerKind||null,peerId:routeForm.peerId||null,mentionAliases:csv(routeForm.mentionAliases),commandPrefixes:csv(routeForm.commandPrefixes),senderIds:csv(routeForm.senderIds),dmScope:routeForm.dmScope,comment:routeForm.comment||null}}
async function saveRoute(){await load(async()=>{await api(routeForm.id?`/api/route-bindings/${routeForm.id}`:'/api/route-bindings',{method:routeForm.id?'PUT':'POST',body:JSON.stringify(routePayload())});resetRouteForm();await loadRoutes()})} async function deleteRoute(id){if(confirm('Delete route?'))await load(async()=>{await api(`/api/route-bindings/${id}`,{method:'DELETE'});await loadRoutes()})}
function csv(v){return String(v||'').split(',').map(x=>x.trim()).filter(Boolean)} function empty(v){const a=csv(v);return a.length?a:null} function parse(v){try{return String(v||'').trim()?JSON.parse(v):{}}catch{throw new Error('Invalid JSON')}} function fmt(v){try{return JSON.stringify(typeof v==='string'?JSON.parse(v):v,null,2)}catch{return v||'{}'}} function pretty(v){return v?JSON.stringify(v,null,2):'{}'} async function copy(v){await navigator.clipboard?.writeText(v)}
async function load(fn){state.loading=true;await safe(fn);state.loading=false} async function safe(fn){state.error='';try{await fn()}catch(e){state.error=e.message||String(e)}finally{state.loading=false}} function notice(m){state.notice=m;setTimeout(()=>{if(state.notice===m)state.notice=''},2500)}
</script>
<style>
:root{
  --bs-blue:#0d6efd;
  --bs-indigo:#6610f2;
  --bs-cyan:#0dcaf0;
  --bs-green:#198754;
  --bs-red:#dc3545;
  --bs-dark:#212529;
  --bs-body:#f8f9fa;
  --surface:#ffffff;
  --border:#dee2e6;
  --muted:#6c757d;
  --text:#212529;
  --sidebar:#111827;
  --sidebar-active:#1f6feb;
  --shadow:0 12px 30px rgba(15,23,42,.08);
}

*{box-sizing:border-box}
body{margin:0;background:var(--bs-body);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,"Segoe UI",Arial,sans-serif}
button,input,select,textarea{font:inherit}
button{cursor:pointer}

.app-shell{min-height:100vh;display:grid;grid-template-columns:264px minmax(0,1fr)}
.auth-shell{grid-template-columns:1fr;background:#212529;color:#fff}
.main{min-width:0;padding:24px}
.centered{display:block;padding:0}

.sidebar{position:sticky;top:0;height:100vh;background:var(--sidebar);color:#e9ecef;padding:18px 14px;display:flex;flex-direction:column;border-right:1px solid rgba(255,255,255,.08)}
.brand{display:flex;align-items:center;gap:10px;font-weight:800;font-size:1.12rem;letter-spacing:.2px}
.brand b{width:38px;height:38px;display:grid;place-items:center;border-radius:8px;background:linear-gradient(135deg,var(--bs-blue),var(--bs-cyan));color:#fff;box-shadow:0 8px 20px rgba(13,110,253,.28)}
.cover-brand{color:#fff}
.nav-list{margin-top:22px;display:grid;gap:4px}
.nav-link{width:100%;height:42px;display:flex;align-items:center;gap:10px;border:0;border-radius:6px;background:transparent;color:#cbd5e1;text-align:left;padding:0 12px}
.nav-link:hover{background:rgba(255,255,255,.08);color:#fff}
.nav-link.active{background:var(--sidebar-active);color:#fff;box-shadow:0 10px 22px rgba(31,111,235,.24)}
.nav-icon{width:26px;height:26px;display:grid;place-items:center;border-radius:6px;background:rgba(255,255,255,.08);font-size:.76rem;font-weight:800}
.nav-link.active .nav-icon{background:rgba(255,255,255,.18)}

.topbar{max-width:1480px;margin:0 auto 20px;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 20px;background:var(--surface);border:1px solid var(--border);border-radius:8px;box-shadow:var(--shadow)}
.topbar p{margin:0 0 4px;color:var(--muted);font-size:.78rem;text-transform:uppercase;font-weight:800}
.topbar h1{margin:0;font-size:clamp(1.35rem,2vw,1.9rem);line-height:1.15}
.user-menu{display:flex;align-items:center;gap:10px;min-width:max-content}
.status-dot{width:9px;height:9px;border-radius:50%;background:var(--bs-green);box-shadow:0 0 0 4px rgba(25,135,84,.14)}

.cover-shell{min-height:100vh;width:100%;display:flex;flex-direction:column;background:#212529;color:#fff;box-shadow:inset 0 0 0 100vmax rgba(0,0,0,.18);overflow:hidden}
.cover-header{width:min(1120px,calc(100% - 40px));margin:0 auto;display:flex;align-items:center;justify-content:space-between;gap:20px;padding:28px 0}
.cover-nav{display:flex;align-items:center;gap:8px}
.cover-nav button{border:0;border-bottom:3px solid transparent;background:transparent;color:rgba(255,255,255,.72);padding:8px 4px;font-weight:800}
.cover-nav button:hover,.cover-nav button.active{color:#fff;border-bottom-color:#fff}
.cover-hero{width:min(1120px,calc(100% - 40px));margin:auto;display:grid;grid-template-columns:minmax(0,1fr) minmax(360px,420px);align-items:center;gap:48px;padding:32px 0 72px}
.cover-copy{display:grid;gap:22px;max-width:680px;text-shadow:0 1px 3px rgba(0,0,0,.45)}
.cover-kicker{margin:0;color:#9ec5fe;text-transform:uppercase;font-size:.82rem;font-weight:900;letter-spacing:.08em}
.cover-copy h1{margin:0;font-size:3.2rem;line-height:1.05;letter-spacing:0}
.cover-lead{margin:0;color:rgba(255,255,255,.78);font-size:1.18rem;line-height:1.72;max-width:620px}
.auth-card{width:100%;display:grid;gap:15px;background:rgba(255,255,255,.96);border:1px solid rgba(255,255,255,.2);border-radius:8px;padding:28px;box-shadow:0 24px 70px rgba(0,0,0,.32);color:var(--text)}
.auth-card h2{margin:0;font-size:1.55rem}
.auth-card p{margin:0;color:var(--muted);line-height:1.5}
.auth-switch{font-size:.9rem;text-align:center}
.auth-switch button{border:0;background:transparent;color:var(--bs-blue);font-weight:850;padding:0}
.form-error{margin:0;color:#b02a37;font-weight:700}

.stack,.content-grid{max-width:1480px;margin:0 auto}
.stack{display:grid;gap:18px}
.content-grid{display:grid;grid-template-columns:minmax(360px,.9fr) minmax(0,1.1fr);gap:18px;align-items:start}
.panel,.metrics article{background:var(--surface);border:1px solid var(--border);border-radius:8px;box-shadow:var(--shadow)}
.panel{padding:20px}
.panel h2{margin:0 0 16px;font-size:1.05rem}

.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}
.metrics article{padding:18px;min-height:112px;display:grid;align-content:space-between}
.metrics span{color:var(--muted);font-size:.82rem;font-weight:800;text-transform:uppercase}
.metrics b{font-size:1.75rem;line-height:1.1;overflow-wrap:anywhere}

.action-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}
.action-card{min-height:132px;text-align:left;border:1px solid var(--border);border-radius:8px;background:#fff;padding:18px;display:grid;align-content:space-between;gap:12px;box-shadow:var(--shadow);color:var(--text)}
.action-card b{font-size:1rem;line-height:1.25}
.action-card span{color:var(--muted);font-size:.86rem;line-height:1.45}
.action-card:hover{border-color:#86b7fe;box-shadow:0 14px 34px rgba(13,110,253,.12)}
.role-editor{display:grid;gap:12px;border:1px solid var(--border);border-radius:8px;background:#f8f9fa;padding:14px}
.role-editor-head{display:flex;align-items:center;justify-content:space-between;gap:12px}
.role-editor-head h3{margin:0;font-size:.95rem}
.role-card{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;border:1px solid #dee2e6;border-radius:8px;background:#fff;padding:14px}
.role-actions{display:flex;align-items:end;justify-content:flex-end}
.empty-state{border:1px dashed #ced4da;border-radius:8px;background:#fff;color:var(--muted);padding:18px;text-align:center;font-weight:750}
.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}
.compact-form{grid-template-columns:repeat(4,minmax(0,1fr));align-items:end}
.form-grid h2,.form-actions,.span-2{grid-column:1/-1}
label{display:grid;gap:7px;color:#495057;font-size:.86rem;font-weight:750}
input,select,textarea{width:100%;min-width:0;border:1px solid #ced4da;border-radius:6px;background:#fff;color:var(--text);padding:10px 12px;outline:none;transition:border-color .15s,box-shadow .15s}
textarea{resize:vertical;line-height:1.45}
input:focus,select:focus,textarea:focus{border-color:#86b7fe;box-shadow:0 0 0 .22rem rgba(13,110,253,.15)}
.check-field{display:flex;align-items:center;gap:10px;min-height:42px;padding:10px 12px;border:1px solid var(--border);border-radius:6px;background:#f8f9fa}
.check-field input{width:1rem;height:1rem}
.form-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:2px}

.picker{display:grid;gap:10px}
.picker-head{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#495057;font-size:.86rem;font-weight:800}
.picker-head code{border:1px solid #b6d4fe;background:#eef6ff;color:#084298;border-radius:999px;padding:3px 8px;font-size:.76rem}
.picker-groups{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.picker-group{min-width:0;margin:0;border:1px solid var(--border);border-radius:8px;background:#f8f9fa;padding:12px}
.picker-group legend{float:none;width:100%;display:flex;align-items:center;justify-content:space-between;margin:0 0 8px;padding:0;color:#212529;font-size:.85rem;font-weight:850}
.picker-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}
.choice{min-width:0;display:flex;align-items:center;gap:8px;border:1px solid #e9ecef;background:#fff;border-radius:6px;padding:8px 9px;color:#343a40;font-size:.82rem;font-weight:700}
.choice input{width:1rem;height:1rem;flex:0 0 auto}
.choice span{min-width:0;overflow-wrap:anywhere}
.tool-choice span{display:grid;gap:2px}
.tool-choice small{color:var(--muted);font-size:.72rem;font-weight:700}
.risk-high{border-color:#f1aeb5;background:#fff8f8}
.risk-medium{border-color:#ffe69c;background:#fffdf5}
.risk-low{border-color:#badbcc;background:#fbfffc}
.link-btn{border:0;background:transparent;color:var(--bs-blue);font-size:.76rem;font-weight:850;padding:0}
.picker-empty{margin:0;color:var(--muted);font-size:.86rem}

.btn{border:1px solid var(--border);border-radius:6px;background:#fff;color:var(--text);padding:9px 13px;font-weight:750;line-height:1.2;min-height:38px}
.btn:hover{filter:brightness(.98)}
.btn-primary{background:var(--bs-blue);border-color:var(--bs-blue);color:#fff}
.btn-outline{background:#fff;color:#495057}
.btn-danger{background:#fff;border-color:#f1aeb5;color:#b02a37}
.btn-danger:hover{background:#fff5f5}

.chips{display:flex;flex-wrap:wrap;gap:8px}
.chips span{display:inline-flex;align-items:center;min-height:30px;border:1px solid #b6d4fe;background:#eef6ff;color:#084298;border-radius:999px;padding:4px 10px;font-size:.84rem;font-weight:750}
.toast{max-width:1480px;margin:0 auto 16px;display:flex;align-items:center;justify-content:space-between;gap:12px;border-radius:8px;padding:12px 14px;font-weight:700}
.toast button{border:0;background:transparent;color:inherit;font-weight:800}
.toast-error{background:#f8d7da;color:#842029;border:1px solid #f5c2c7}
.toast-ok{background:#d1e7dd;color:#0f5132;border:1px solid #badbcc}
.loading-bar{max-width:1480px;height:3px;margin:0 auto 16px;border-radius:999px;background:linear-gradient(90deg,var(--bs-blue),var(--bs-cyan));animation:pulse 1.2s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:.35}50%{opacity:1}}

.scroll{overflow:auto;border:1px solid var(--border);border-radius:8px}
table{width:100%;border-collapse:separate;border-spacing:0;min-width:900px;background:#fff}
th,td{border-bottom:1px solid #e9ecef;padding:12px 14px;text-align:left;vertical-align:top;max-width:340px;overflow-wrap:anywhere}
th{position:sticky;top:0;background:#f1f3f5;color:#495057;font-size:.78rem;text-transform:uppercase;z-index:1}
tbody tr:hover{background:#f8fbff}
tbody tr:last-child td{border-bottom:0}
td:last-child{white-space:nowrap}

pre{margin:0;background:#0f172a;color:#e2e8f0;border-radius:8px;padding:14px;white-space:pre-wrap;overflow:auto;line-height:1.5}
details{margin-top:14px}
summary{cursor:pointer;color:#495057;font-weight:800}
.modal{position:fixed;inset:0;background:rgba(15,23,42,.48);display:grid;place-items:center;padding:24px;z-index:20}
.modal section{width:min(680px,100%);background:#fff;border-radius:8px;border:1px solid var(--border);box-shadow:0 24px 70px rgba(15,23,42,.3);padding:20px}

@media(max-width:1100px){
  .app-shell{grid-template-columns:220px minmax(0,1fr)}
  .cover-hero{grid-template-columns:1fr;gap:28px}
  .cover-copy{max-width:none}
  .cover-card{max-width:520px}
  .metrics,.action-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .content-grid{grid-template-columns:1fr}
  .compact-form{grid-template-columns:repeat(2,minmax(0,1fr))}
}
@media(max-width:760px){
  .app-shell{grid-template-columns:1fr}
  .cover-header{width:calc(100% - 28px);padding:18px 0;align-items:flex-start;flex-direction:column}
  .cover-hero{width:calc(100% - 28px);padding:18px 0 36px}
  .cover-copy h1{font-size:2.25rem}
  .cover-lead{font-size:1rem}
  .auth-card{padding:20px}
  .main{padding:14px}
  .sidebar{position:relative;height:auto}
  .nav-list{grid-template-columns:repeat(2,minmax(0,1fr));margin-top:14px}
  .topbar{align-items:flex-start;flex-direction:column}
  .user-menu{width:100%;justify-content:space-between}
  .metrics,.action-grid,.form-grid,.compact-form,.role-card{grid-template-columns:1fr}
  .picker-groups,.picker-options{grid-template-columns:1fr}
  .panel{padding:16px}
}
</style>
