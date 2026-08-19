/**
 * 轻量 Markdown 渲染（聊天流式文本用）。
 * - 先 HTML 转义再套样式，防注入
 * - 支持：```code```、`inline`、**bold**、*italic*、[link]、标题、列表、引用、分割线、换行
 */

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function inline(s: string): string {
  return escapeHtml(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
}

/** 流式渲染：可能在没有墙住代码块的中间态，尽量把已闭合的块先渲染 */
export function renderMarkdown(src: string): string {
  if (!src) return ''
  const text = src.replace(/\r\n/g, '\n')
  const isFenced = (text.match(/```/g) || []).length % 2 === 1
  const body = isFenced ? text + '\n```' : text

  const lines = body.split('\n')
  const out: string[] = []
  let inCode = false
  let codeBuf: string[] = []
  let inList = false
  let listType = 'ul'

  const closeList = () => {
    if (inList) {
      out.push(`</${listType}>`)
      inList = false
    }
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // 代码块
    if (line.trim().startsWith('```')) {
      if (inCode) {
        out.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`)
        codeBuf = []
        inCode = false
      } else {
        closeList()
        inCode = true
        codeBuf = []
      }
      continue
    }
    if (inCode) {
      codeBuf.push(escapeHtml(line))
      continue
    }

    const t = line.trim()

    // 空行
    if (!t) {
      closeList()
      if (out.length && !out[out.length - 1].startsWith('<')) out.push('')
      continue
    }

    // 标题
    const h = t.match(/^(#{1,4})\s+(.*)$/)
    if (h) {
      closeList()
      const level = h[1].length
      out.push(`<h${level}>${inline(h[2])}</h${level}>`)
      continue
    }

    // 分割线
    if (/^(\*\s*){3,}$|^(-{3,})$|^(_{3,})$/.test(t)) {
      closeList()
      out.push('<hr />')
      continue
    }

    // 引用
    if (t.startsWith('>')) {
      closeList()
      out.push(`<blockquote>${inline(t.replace(/^>\s?/, ''))}</blockquote>`)
      continue
    }

    // 无序列表
    const ul = t.match(/^[-*]\s+(.*)$/)
    if (ul) {
      if (!inList || listType !== 'ul') {
        closeList()
        out.push('<ul>')
        inList = true
        listType = 'ul'
      }
      out.push(`<li>${inline(ul[1])}</li>`)
      continue
    }

    // 有序列表
    const ol = t.match(/^\d+\.\s+(.*)$/)
    if (ol) {
      if (!inList || listType !== 'ol') {
        closeList()
        out.push('<ol>')
        inList = true
        listType = 'ol'
      }
      out.push(`<li>${inline(ol[1])}</li>`)
      continue
    }

    closeList()
    out.push(`<p>${inline(t)}</p>`)
  }
  closeList()
  if (inCode && codeBuf.length) {
    out.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`)
  }

  return out.filter((l) => l !== '').join('')
}

export default renderMarkdown