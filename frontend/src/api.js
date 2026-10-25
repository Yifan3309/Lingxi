/**
 * 后端 API 封装 + SSE 流式解析。
 * 开发态走 Vite 代理，发布态同源直连，统一用相对路径。
 */

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function request(url, options = {}) {
  const res = await fetch(url, options)
  if (!res.ok) {
    let detail = ''
    try {
      detail = (await res.json()).message || ''
    } catch {
      // 错误响应无 JSON body
    }
    throw new Error(`${res.status}${detail ? ' ' + detail : ''}`)
  }
  if (res.status === 204) {
    return null
  }
  return res.json()
}

function toQuery(params = {}) {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') {
      q.set(k, v)
    }
  }
  const s = q.toString()
  return s ? '?' + s : ''
}

export const api = {
  listNotes: (params) => request('/api/notes' + toQuery(params)),
  getNote: (id) => request(`/api/notes/${id}`),
  createNote: (body) =>
    request('/api/notes', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
  updateNote: (id, body) =>
    request(`/api/notes/${id}`, { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }),
  deleteNote: (id) => request(`/api/notes/${id}`, { method: 'DELETE' }),
  related: (id) => request(`/api/notes/${id}/related`),
  config: () => request('/api/admin/config'),
  /** 热切换模型（无需重启）。body: {chat:{model,baseUrl?,apiKey?}, vision:{model}} */
  updateConfig: (body) =>
    request('/api/admin/config', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
  history: (sessionId) => request(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`),

  /** M3 知识图谱：节点（笔记+标签）与边（共标签/语义近邻）。 */
  graph: () => request('/api/graph'),

  /** M3 回顾：range=daily|weekly。summary 在模型不可用时为 null。 */
  review: (range) => request('/api/review' + toQuery({ range })),

  /** M3 全量重嵌入（R1）：触发返回当前进度；轮询 status 直到 running=false。 */
  reembedStart: () => request('/api/admin/reembed', { method: 'POST' }),
  reembedStatus: () => request('/api/admin/reembed/status'),

  /** 上传图片附件，返回 { url: '/api/assets/xxx.png' }。multipart 由浏览器自动设 boundary。 */
  async uploadAsset(file) {
    const fd = new FormData()
    fd.append('file', file)
    return request('/api/assets', { method: 'POST', body: fd })
  },

  /** 多模态导入：分级解析（Tika/视觉 LLM）建笔记，返回 Note。 */
  async importFile(file) {
    const fd = new FormData()
    fd.append('file', file)
    return request('/api/notes/import', { method: 'POST', body: fd })
  },

  /** 网页剪藏：URL → 抓取正文建笔记（source=clip），可能需数秒。 */
  clipUrl: (url) =>
    request('/api/notes/clip', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ url }) }),

  /**
   * RAG 问答（SSE）。fetch + ReadableStream 手动解析（EventSource 不支持 POST）。
   * 帧格式：event: message/done/error + data:。data 尝试 JSON 解析，失败按纯文本（token）。
   */
  async chatStream(body, onEvent) {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body)
    })
    if (!res.ok || !res.body) {
      throw new Error(`chat ${res.status}`)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const parsed = parseFrame(frame)
        if (parsed) {
          onEvent(parsed.event, parsed.data)
        }
      }
    }
  }
}

function parseFrame(frame) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    const l = line.endsWith('\r') ? line.slice(0, -1) : line
    if (l.startsWith('event:')) {
      event = l.slice(6).trim()
    } else if (l.startsWith('data:')) {
      dataLines.push(l.slice(5).replace(/^ /, ''))
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  const raw = dataLines.join('\n')
  let data = raw
  try {
    data = JSON.parse(raw)
  } catch {
    // 增量 token 是纯文本
  }
  return { event, data }
}

/** 分数展示：向量相似度 0~1 直接用；重排 logit 超界时按 sigmoid 折算。 */
export function fmtScore(s) {
  if (s === null || s === undefined) {
    return ''
  }
  const v = s >= 0 && s <= 1 ? s : 1 / (1 + Math.exp(-s))
  return (v * 100).toFixed(0) + '%'
}
