<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../api'

/**
 * @ 附件选择浮层：文件夹 / 笔记（按 query 过滤）+ 本地文件 / 上传入口。
 * pick 事件带 {type,id,name}；action 事件带 'local' | 'upload'（由父组件弹对话框/文件选择）。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  query: { type: String, default: '' }
})
const emit = defineEmits(['pick', 'action', 'close'])

const notes = ref([])
const loaded = ref(false)

watch(
  () => props.open,
  async (v) => {
    if (v && !loaded.value) {
      try {
        notes.value = await api.listNotes()
      } catch {
        notes.value = []
      }
      loaded.value = true
    }
  }
)

const folders = computed(() => {
  const set = new Set()
  for (const n of notes.value) {
    const segs = (n.folder || '')
      .split('/')
      .map((s) => s.trim())
      .filter(Boolean)
    let path = ''
    for (const seg of segs) {
      path = path ? path + '/' + seg : seg
      set.add(path)
    }
  }
  const q = props.query.trim().toLowerCase()
  return [...set]
    .sort((a, b) => a.localeCompare(b, 'zh'))
    .filter((f) => !q || f.toLowerCase().includes(q))
    .slice(0, 5)
})

const matched = computed(() => {
  const q = props.query.trim().toLowerCase()
  const list = q
    ? notes.value.filter((n) => (n.title || '').toLowerCase().includes(q))
    : notes.value
  return list.slice(0, 8)
})
</script>

<template>
  <div
    v-if="props.open"
    class="absolute bottom-full left-0 mb-2 w-full max-h-72 overflow-y-auto
           rounded-xl bg-surface ring-1 ring-line shadow-lg z-20 py-1"
  >
    <div v-if="!folders.length && !matched.length" class="px-3 py-2 text-xs text-tx3">
      没有匹配的笔记/文件夹
    </div>

    <template v-if="folders.length">
      <div class="px-3 pt-1.5 pb-1 text-[11px] tracking-wide text-tx3">文件夹</div>
      <div
        v-for="f in folders"
        :key="'f:' + f"
        class="px-3 py-1.5 text-sm text-tx1 hover-accent cursor-pointer truncate"
        :title="f"
        @click="emit('pick', { type: 'folder', name: f })"
      >
        {{ f }}
      </div>
    </template>

    <template v-if="matched.length">
      <div class="px-3 pt-2 pb-1 text-[11px] tracking-wide text-tx3">笔记</div>
      <div
        v-for="n in matched"
        :key="n.id"
        class="px-3 py-1.5 text-sm text-tx1 hover-accent cursor-pointer truncate"
        :title="n.title || '未命名'"
        @click="emit('pick', { type: 'note', id: n.id, name: n.title || '未命名' })"
      >
        {{ n.title || '未命名' }}
      </div>
    </template>

    <div class="my-1 border-t border-line"></div>
    <div
      class="px-3 py-1.5 text-sm text-tx1 hover-accent cursor-pointer"
      @click="emit('action', 'local')"
    >
      链接本地文件…
    </div>
    <div
      class="px-3 py-1.5 text-sm text-tx1 hover-accent cursor-pointer"
      @click="emit('action', 'upload')"
    >
      上传文件…
    </div>
  </div>
</template>
