import { ref } from 'vue'
import { api } from '../api'

const KEY = 'sb_session'

/** 聊天状态域（模块级单例）。sessionId 持久化在 localStorage。 */
const visible = ref(false)
const sessionId = ref(localStorage.getItem(KEY) || newSessionId())
const messages = ref([])
const streaming = ref(false)

function newSessionId() {
  return 'web-' + Math.random().toString(36).slice(2, 8)
}

export function useChat() {
  function open() {
    visible.value = true
    loadHistory()
  }

  async function loadHistory() {
    try {
      const history = await api.history(sessionId.value)
      messages.value = history.map((m) => ({
        role: m.role,
        content: m.content,
        sources: m.role === 'assistant' && m.refs ? safeParse(m.refs) : null
      }))
    } catch {
      messages.value = []
    }
  }

  function newSession() {
    sessionId.value = newSessionId()
    localStorage.setItem(KEY, sessionId.value)
    messages.value = []
  }

  /**
   * 提问。attachments 可选：本轮上下文附件
   * （[{type:'note'|'folder'|'localFile'|'upload', id?, name?, path?, uploadId?}]），
   * 只进本轮请求，历史里不保留。
   */
  async function ask(question, attachments) {
    const payload = (attachments || []).map((a) => ({
      type: a.type,
      id: a.id || undefined,
      name: a.name || undefined,
      path: a.path || undefined,
      uploadId: a.uploadId || undefined
    }))
    messages.value.push({
      role: 'user',
      content: question,
      attachments: payload.length ? payload : null
    })
    messages.value.push({ role: 'assistant', content: '', sources: null })
    // 取数组里的响应式代理，保证流式追加触发渲染
    const assistant = messages.value[messages.value.length - 1]
    streaming.value = true
    const body = { sessionId: sessionId.value, question }
    if (payload.length) {
      body.attachments = payload
    }
    try {
      await api.chatStream(body, (event, data) => {
        if (event === 'message') {
          assistant.content += typeof data === 'string' ? data : JSON.stringify(data)
        } else if (event === 'done') {
          assistant.sources = (data && data.sources) || []
        } else if (event === 'error') {
          assistant.error = typeof data === 'string' ? data : JSON.stringify(data)
        }
      })
    } catch (e) {
      assistant.error = String(e)
    } finally {
      streaming.value = false
    }
  }

  return { visible, sessionId, messages, streaming, open, loadHistory, newSession, ask }
}

function safeParse(json) {
  try {
    return JSON.parse(json)
  } catch {
    return null
  }
}
