import { getToken } from './http'

/**
 * SSE over POST（浏览器原生 EventSource 不支持自定义 body/header，改用 fetch + ReadableStream）。
 * 用于 /v1/chat/completions 流式推理。
 *
 * 逐条解析 `data: <payload>\n\n` 帧，回调 onMessage；`[DONE]` 收尾。
 */
export interface SsePostOptions {
  url: string
  body: unknown
  signal?: AbortSignal
  onMessage: (data: string) => void
  onError?: (err: unknown) => void
  onDone?: () => void
}

export async function ssePost(opts: SsePostOptions): Promise<void> {
  const { url, body, signal, onMessage, onError, onDone } = opts
  const token = getToken()
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal,
    })

    if (!resp.ok || !resp.body) {
      const text = await resp.text().catch(() => '')
      throw new Error(`SSE 请求失败: ${resp.status} ${text}`)
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 以空行分割 SSE 事件块
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        for (const line of rawEvent.split('\n')) {
          const trimmed = line.trimStart()
          if (!trimmed.startsWith('data:')) continue
          const data = trimmed.slice(5).trimStart()
          if (data === '[DONE]') {
            onDone?.()
            return
          }
          onMessage(data)
        }
      }
    }
    onDone?.()
  } catch (err) {
    if ((err as Error)?.name === 'AbortError') return
    onError?.(err)
  }
}
