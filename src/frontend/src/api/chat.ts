import { http } from './http'
import { ssePost } from './sse'
import type {
  ChatCompletionRequest,
  ConversationMeta,
  ConversationMessagesVO,
  ConversationTraceVO,
  ListWrap,
} from '@/types/api'

/**
 * 推理与会话（走 /v1/**，经网关动态路由到用户 Claw Pod）。
 * 注意：这些接口不带 /api 前缀，也不走 Result 信封。
 */
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

  listConversations: () => http.get<ListWrap<ConversationMeta>>('/v1/conversations'),
  getMessages: (conversationId: string) =>
    http.get<ConversationMessagesVO>(`/v1/conversations/${conversationId}/messages`),
  getTrace: (conversationId: string) =>
    http.get<ConversationTraceVO>(`/v1/conversations/${conversationId}/trace`),
}
