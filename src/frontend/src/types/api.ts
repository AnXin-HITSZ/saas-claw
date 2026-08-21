/**
 * 后端契约类型（全局 SNAKE_CASE，字段名与 backend Result.data / runtime JSON 原样一致）。
 * 不做驼峰转换，避免多一层映射产生偏差。
 */

/** 统一响应信封（除 /v1/** 外）。成功 code=200，失败 code=业务码。 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** 分页信封（runtime 会话列表等使用 { list } 结构）。 */
export interface ListWrap<T> {
  list: T[]
}

// ---------------- 认证 ----------------
export interface LoginRequest {
  username: string
  password: string
}
export interface RegisterRequest {
  username: string
  password: string
}
/** role: 0=普通用户 1=管理员 */
export interface LoginVO {
  token: string
  user_id: number
  username: string
  nickname: string | null
  role: number
}

// ---------------- API Key ----------------
export interface CreateApiKeyRequest {
  name: string
}
/** api_key 明文仅创建时返回一次 */
export interface CreateApiKeyVO {
  id: number
  api_key: string
  name: string
  key_suffix: string
  created_at: string
}
export interface ApiKeyVO {
  id: number
  name: string
  key_suffix: string
  status: number
  created_at: string
  updated_at: string
}

// ---------------- Claw ----------------
export interface ClawCreateRequest {
  name: string
}
export interface Claw {
  id: number
  user_id: number
  name: string
  namespace: string
  status: number
  created_at: string
  updated_at: string
}

// ---------------- Agent ----------------
export interface AgentCreateRequest {
  claw_id: number
  alias: string
  name: string
  description?: string
  system_prompt?: string
  base_model: string
  temperature?: number
  max_tokens?: number
}
export interface AgentUpdateRequest {
  name?: string
  description?: string
  system_prompt?: string
  base_model?: string
  temperature?: number
  max_tokens?: number
}
export interface Agent {
  id: number
  user_id: number
  claw_id: number
  alias: string
  name: string
  description: string | null
  system_prompt: string | null
  base_model: string
  temperature: number | null
  max_tokens: number | null
  status: number
  created_at: string
  updated_at: string
}
export interface BindSkillRequest {
  skill_id: number
}

// ---------------- Agent / Skill 文件 ----------------
export interface AgentFileVO {
  id: number
  file_name: string
  file_url: string
  file_type: string
  file_size: number
  file_hash: string
  created_at: string
}
export interface SkillFileVO {
  id: number
  file_name: string
  file_url: string
  file_type: string
  file_size: number
  file_hash: string
  created_at: string
}

// ---------------- 模型配置（管理员写）----------------
export interface ModelConfigCreateRequest {
  name: string
  provider: string
  model_name: string
  endpoint: string
  api_key: string
}
export interface ModelConfigUpdateRequest {
  endpoint?: string
  api_key?: string
  status?: number
}
/** 路由模型（router）配置：仅管理员通过 /model-configs/router 读写，不进入业务模型列表 */
export interface RouterConfigUpdateRequest {
  provider?: string
  model_name?: string
  endpoint?: string
  api_key?: string
  status?: number
}
export interface ModelConfig {
  id: number
  name: string
  provider: string
  model_name: string
  endpoint: string
  /** 明文永不回传，展示为掩码 */
  api_key: string | null
  status: number
  created_at: string
  updated_at: string
}

// ---------------- Skill ----------------
export interface SkillCreateRequest {
  name: string
  description: string
  version?: string
  author?: string
}
export interface SkillUpdateRequest {
  name?: string
  description?: string
  version?: string
  author?: string
}
export interface Skill {
  id: number
  user_id: number
  name: string
  description: string
  version: string | null
  author: string | null
  status: number
  created_at: string
  updated_at: string
}

// ---------------- 工具 ----------------
export interface Tool {
  id: number
  name: string
  description: string | null
  schema_json: string | null
  is_sensitive: number
  status: number
  created_at: string
  updated_at: string
}

/** 管理员创建工具；name 须与 runtime 已注册的 @tool 名一致，否则不可执行 */
export interface ToolCreateRequest {
  name: string
  description?: string
  schema_json?: string
  is_sensitive?: number
  status?: number
}

export interface ToolUpdateRequest {
  description?: string
  schema_json?: string
  is_sensitive?: number
  status?: number
}

// ---------------- Agent 市场 ----------------
export interface ShopAgentVO {
  agent_id: number
  name: string
  alias: string
  description: string | null
  version: string | null
  author: string | null
  base_model: string
  publisher_id: number
  publisher_nickname: string | null
  installs: number
  created_at: string
}
export interface InstallAgentRequest {
  claw_id: number
}
export interface MissingSkillVO {
  skill_id: number
  name: string
  installable: boolean
}
export interface AgentInstallVO {
  installation_id: number
  local_agent_id: number
  claw_id: number
  missing_skills: MissingSkillVO[]
}
export interface MyAgentInstallationVO {
  installation_id: number
  agent_id: number
  name: string
  alias: string
  description: string | null
  version: string | null
  author: string | null
  base_model: string
  claw_id: number
  installed_at: string
}

// ---------------- Skill 市场 ----------------
export interface ShopSkillVO {
  skill_id: number
  name: string
  description: string
  version: string | null
  author: string | null
  publisher_id: number
  publisher_nickname: string | null
  installs: number
  created_at: string
}
export interface InstallSkillsRequest {
  skill_ids: number[]
}
export interface SkillInstallation {
  id: number
  user_id: number
  skill_id: number
  installed_at: string
}
export interface BatchFailItemVO {
  skill_id: number
  reason: string
}
export interface InstallBatchResultVO {
  succeeded: SkillInstallation[]
  failed: BatchFailItemVO[]
}
export interface MySkillInstallationVO {
  installation_id: number
  skill_id: number
  name: string
  description: string
  version: string | null
  author: string | null
  bound_agent_count: number
  installed_at: string
}

// ---------------- 审批 ----------------
/** action: 1=允许 2=拒绝 3=自定义消息 */
export const APPROVAL_ACTION = { ALLOW: 1, DENY: 2, CUSTOM: 3 } as const
export interface HandleApprovalRequest {
  action: number
  custom_message?: string
}
export interface ApprovalRequestVO {
  approval_id: number
  request_id: string
  agent_id: number
  agent_name: string | null
  claw_id: number
  tool_id: number
  tool_name: string | null
  input_summary: string
  status: number
  action: number | null
  custom_message: string | null
  created_at: string
  handled_at: string | null
}

// ---------------- 批量审批（spawn_subagent 聚合，一张卡覆盖多路子请求）----------------
export interface ApprovalBatchSubRequestVO {
  request_id: string
  tool_id: number | null
  tool_name: string | null
  input_summary: string | null
}
export interface ApprovalBatchVO {
  batch_id: number
  request_id: string
  agent_id: number
  agent_name: string | null
  claw_id: number
  sub_requests: ApprovalBatchSubRequestVO[]
  status: number
  action: number | null
  custom_message: string | null
  created_at: string
  handled_at: string | null
}
/** 逐子请求决策（decisions 映射的值）：缺省按整体决策 */
export interface ApprovalBatchDecision {
  action: number
  custom_message?: string
}
export interface HandleApprovalBatchRequest {
  action: number
  custom_message?: string
  decisions?: Record<string, ApprovalBatchDecision>
}

// ---------------- 推理 / 会话（/v1/**，runtime 原始 JSON）----------------
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
}
export interface ChatCompletionRequest {
  /** 承载 agent alias（网关据此动态路由到用户 Claw） */
  model: string
  messages: ChatMessage[]
  /** 前端生成的会话 uuid，贯穿一次会话 */
  conversation_id: string
  /** 显式 agent alias，供 runtime prepare 精确解析（与 model 一致；缺省时 runtime 走 router） */
  alias?: string
  stream?: boolean
}
export interface ConversationMeta {
  conversation_id: string
  last_summary?: string
  [k: string]: unknown
}
export interface ConversationMessagesVO {
  conversation_id: string
  messages: ChatMessage[]
}
// ---------------- 追踪 / 时间轴（/v1/conversations/{id}/trace）----------------
export type TraceEventType =
  | 'chat_start'
  | 'chat_end'
  | 'tool_start'
  | 'tool_end'
  | 'subagent_start'
  | 'subagent_end'
  | 'approval_pending'
  | 'approval_resolved'

/** 单条追踪事件（trace 落盘与 SSE 过程帧共用同一结构） */
export interface TraceEvent {
  event_id: string
  type: TraceEventType | string
  claw_id: number
  agent_id: number | null
  user_id: number | null
  conversation_id: string
  span_id: string
  parent_id: string | null
  timestamp_ms: number
  data: Record<string, unknown>
}

/** 时间轴项：消息 或 事件（/trace 的 items 已按时间合并两种） */
export interface TraceMessageItem {
  kind: 'message'
  role: string
  content: string
  timestamp_ms: number | null
}
export interface TraceEventItem extends TraceEvent {
  kind: 'event'
}
export type TraceItem = TraceMessageItem | TraceEventItem

/** span 树节点：容器节点聚合该 span 全部事件 + 子节点 */
export interface TraceSpanNode extends TraceEvent {
  events: TraceEvent[]
  children: TraceSpanNode[]
}

export interface ConversationTraceVO {
  conversation_id: string
  events: TraceEvent[]
  items: TraceItem[]
  tree: TraceSpanNode[]
}
