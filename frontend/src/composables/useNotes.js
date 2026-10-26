import { computed, ref } from 'vue'
import { api } from '../api'

/** 笔记状态域（模块级单例）。 */
const notes = ref([])
const currentId = ref(null)
const current = ref(null)
const searchMode = ref('keyword')
const searchText = ref('')
const loading = ref(false)
/** 保存反馈（借鉴 NoteDiscovery 无弹窗三态）：saving → saved（4s 后回落） */
const saveState = ref('idle')

export function useNotes() {
  async function refresh() {
    loading.value = true
    try {
      const params = {}
      if (searchText.value) {
        if (searchMode.value === 'keyword') {
          params.keyword = searchText.value
        } else {
          params.tag = searchText.value
        }
      }
      notes.value = await api.listNotes(params)
    } finally {
      loading.value = false
    }
  }

  async function select(id) {
    currentId.value = id
    current.value = await api.getNote(id)
  }

  function newNote() {
    currentId.value = null
    current.value = { title: '', content: '', tags: '', folder: '' }
  }

  async function save(note) {
    saveState.value = 'saving'
    const saved = currentId.value
      ? await api.updateNote(currentId.value, note)
      : await api.createNote(note)
    await refresh()
    await select(saved.id)
    saveState.value = 'saved'
    setTimeout(() => {
      if (saveState.value === 'saved') {
        saveState.value = 'idle'
      }
    }, 4000)
    // 富化是异步的：稍后重拉详情，摘要/建议标签到达即展示
    setTimeout(async () => {
      if (currentId.value === saved.id) {
        try {
          current.value = await api.getNote(saved.id)
        } catch {
          // 笔记可能已被删除，忽略
        }
      }
    }, 3000)
    return saved
  }

  async function remove(id) {
    await api.deleteNote(id)
    if (currentId.value === id) {
      currentId.value = null
      current.value = null
    }
    await refresh()
  }

  /** 拖拽移动到文件夹：folder 为 null 表示移出到未归类（空串语义）。 */
  async function moveToFolder(noteId, folder) {
    await api.updateNote(noteId, { folder: folder ?? '' })
    await refresh()
    if (currentId.value === noteId) {
      current.value = await api.getNote(noteId)
    }
  }

  /** 采纳建议标签：并入 tags 后保存（AI 建议者，用户决策者）。 */
  async function adoptTag(tag) {
    const tags = parseTags(current.value.tags)
    if (!tags.includes(tag)) {
      tags.push(tag)
    }
    current.value = await api.updateNote(current.value.id, { tags: joinTags(tags) })
  }

  return {
    notes,
    currentId,
    current,
    searchMode,
    searchText,
    loading,
    saveState,
    refresh,
    select,
    newNote,
    save,
    remove,
    adoptTag,
    moveToFolder
  }
}

/** 现有文件夹路径（含中间层级，去重、zh 排序），供编辑器自动补全；侧栏树另行派生。 */
export function useFolders(notesRef) {
  return computed(() => {
    const set = new Set()
    for (const n of notesRef.value || []) {
      const segs = (n.folder || '').split('/').map((s) => s.trim()).filter(Boolean)
      let path = ''
      for (const seg of segs) {
        path = path ? path + '/' + seg : seg
        set.add(path)
      }
    }
    return [...set].sort((a, b) => a.localeCompare(b, 'zh'))
  })
}

/** 相对时间：刚刚 / N 分钟前 / 具体时间 */
export function relTime(ts) {
  if (!ts) {
    return ''
  }
  const diff = Date.now() - new Date(ts).getTime()
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3600_000) {
    return Math.floor(diff / 60_000) + ' 分钟前'
  }
  if (diff < 86_400_000) {
    return Math.floor(diff / 3600_000) + ' 小时前'
  }
  return new Date(ts).toLocaleDateString()
}

export function parseTags(str) {
  return (str || '')
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

export function joinTags(arr) {
  return (arr || []).join(',')
}
