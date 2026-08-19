import { http } from './http'
import { ssePost } from './sse'
import type { AxiosRequestConfig } from 'axios'
import type {
  ChatCompletionRequest,
  ConversationMeta,
  ConversationMessagesVO,
  ConversationTraceVO,
  ListWrap,
} from '@/types/api'

/**
 * 推理与会话（走 /v1/**，经网关动态路由到用户 Claw Pod）。
 * 注意：这些接口不带 /api 前缀，也不走 Result 信封——
 * /v1 GET 均需传 { baseURL: '' } 绕开 axios 实例的 /api 前缀，否则会打到 /api/v1/** 被 backend 404。
 */
/** /v1 原始路径请求：绕开实例 baseURL('/api')，响应仍用实例拦截器（非信封透传 / 401 跳登录）。 */
const raw = (config: AxiosRequestConfig): AxiosRequestConfig => ({ ...config, baseURL: '' })

export const chatApi = {
  /** 流式对话；model 字段承载 agent alias。 */
  streamChat: (
    body: ChatCompletionRequest,
    handlers: {
      onMessage: (data: string) => void
      onError?: (err: unknown) => void
      onDone?: () => void
      signal?: AbortSignal
    },
  ) =>
    ssePost({
      url: '/v1/chat/completions',
      body: { ...body, stream: true },
      onMessage: handlers.onMessage,
      onError: handlers.onError,
      onDone: handlers.onDone,
      signal: handlers.signal,
    }),

  listConversations: () => http.get<ListWrap<ConversationMeta>>('/v1/conversations', raw({})),
  getMessages: (conversationId: string) =>
    http.get<ConversationMessagesVO>(`/v1/conversations/${conversationId}/messages`, raw({})),
  getTrace: (conversationId: string) =>
    http.get<ConversationTraceVO>(`/v1/conversations/${conversationId}/trace`, raw({})),
}
