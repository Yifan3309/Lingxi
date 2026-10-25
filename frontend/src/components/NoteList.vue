<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useNotes, parseTags, relTime } from '../composables/useNotes'
import { api } from '../api'

const { notes, currentId, select, newNote, searchMode, searchText, refresh, loading, moveToFolder } = useNotes()

/* ---- 多模态导入（Tika + 视觉 LLM 分级解析） ---- */
const importInput = ref(null)
const importing = ref(false)
const IMPORT_ACCEPT = '.md,.markdown,.txt,.docx,.doc,.pptx,.ppt,.pdf,.html,.htm,.json,.yaml,.yml,.xml,.csv,.java,.py,.js,.ts,.sql,.sh,.go,.rs,.c,.cpp,.h,.cs,.png,.jpg,.jpeg,.webp'

function pickImport() {
  importInput.value?.click()
}

async function onImportChosen(e) {
  const files = [...(e.target.files || [])]
  e.target.value = ''
  if (!files.length) {
    return
  }
  importing.value = true
  let lastId = null
  let ok = 0
  try {
    for (const f of files) {
      try {
        const note = await api.importFile(f)
        lastId = note.id
        ok++
      } catch (err) {
        ElMessage.error(`${f.name} 导入失败：${err?.message || err}`)
      }
    }
  } finally {
    importing.value = false
  }
  if (ok > 0) {
    ElMessage.success(`已导入 ${ok} 篇笔记`)
    await refresh()
    if (lastId) {
      await select(lastId)
    }
  }
}

/** 搜索/过滤时展平为结果列表，避免折叠的组把命中藏住 */
const filtering = computed(() => !!searchText.value.trim())

/* ---- 网页剪藏（URL → 笔记） ---- */
const clipVisible = ref(false)
const clipUrlInput = ref('')
const clipping = ref(false)

async function doClip() {
  const url = clipUrlInput.value.trim()
  if (!url) {
    return
  }
  clipping.value = true
  try {
    const note = await api.clipUrl(url)
    clipVisible.value = false
    clipUrlInput.value = ''
    ElMessage.success('剪藏成功')
    await refresh()
    await select(note.id)
  } catch (err) {
    ElMessage.error(`剪藏失败：${err?.message || err}`)
  } finally {
    clipping.value = false
  }
}

/* ---- 拖拽移动到文件夹 ---- */
const draggingId = ref(null)
const dropKey = ref(null)

function onDragStart(e, n) {
  draggingId.value = n.id
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', n.id)
}

function onDragEnd() {
  draggingId.value = null
  dropKey.value = null
}

function onDrop(folder) {
  const id = draggingId.value
  onDragEnd()
  if (id) {
    moveToFolder(id, folder).catch(() => {})
  }
}

const FOLDER_KEY = 'lx_collapsed_folders'
const collapsed = ref(new Set(JSON.parse(localStorage.getItem(FOLDER_KEY) || '[]')))

function isCollapsed(folder) {
  return collapsed.value.has(folder ?? '__unfiled__')
}

function toggle(folder) {
  const key = folder ?? '__unfiled__'
  const next = new Set(collapsed.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  collapsed.value = next
  localStorage.setItem(FOLDER_KEY, JSON.stringify([...next]))
}

/** 按文件夹分组：有文件夹的按组内最新更新时间降序，「未归类」排最后 */
const groups = computed(() => {
  const map = new Map()
  const unfiled = []
  for (const n of notes.value) {
    const f = (n.folder || '').trim()
    if (!f) {
      unfiled.push(n)
      continue
    }
    if (!map.has(f)) {
      map.set(f, [])
    }
    map.get(f).push(n)
  }
  const latest = (g) => Math.max(...g.items.map((n) => +new Date(n.updatedAt)))
  const arr = [...map.entries()].map(([folder, items]) => ({ folder, items }))
  arr.sort((a, b) => latest(b) - latest(a))
  if (unfiled.length) {
    arr.push({ folder: null, items: unfiled })
  }
  return arr
})
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="p-3 border-b border-line flex flex-col gap-2.5">
      <div class="flex items-center justify-between">
        <el-radio-group v-model="searchMode" size="small">
          <el-radio-button value="keyword">关键词</el-radio-button>
          <el-radio-button value="tag">标签</el-radio-button>
        </el-radio-group>
        <div class="flex items-center gap-1.5">
          <el-button type="primary" size="small" round class="!active:scale-95 transition-transform" @click="newNote()">
            + 新建
          </el-button>
          <el-button size="small" round :loading="importing" class="!active:scale-95 transition-transform" title="导入文档（md/docx/pptx/pdf/html/代码/图片）" @click="pickImport">
            导入
          </el-button>
          <el-button size="small" round class="!active:scale-95 transition-transform" title="网页剪藏：粘贴 URL 存为笔记" @click="clipVisible = true">
            剪藏
          </el-button>
        </div>
        <input ref="importInput" type="file" multiple :accept="IMPORT_ACCEPT" class="hidden" @change="onImportChosen" />
      </div>

      <el-dialog v-model="clipVisible" title="网页剪藏" width="420px" append-to-body>
        <el-input v-model="clipUrlInput" placeholder="https://… 粘贴文章链接" clearable @keyup.enter="doClip" />
        <div class="text-xs text-tx3 mt-2">抓取页面 + AI 清洗整理约 5-15 秒；图片自动存入本地，之后进入标签 / 摘要 / 检索管线</div>
        <template #footer>
          <el-button @click="clipVisible = false">取消</el-button>
          <el-button type="primary" :loading="clipping" @click="doClip">剪藏</el-button>
        </template>
      </el-dialog>
      <el-input
        v-model="searchText"
        placeholder="回车搜索，清空列出全部"
        clearable
        size="small"
        @keyup.enter="refresh"
        @clear="refresh"
      />
    </div>

    <div v-loading="loading" class="flex-1 overflow-y-auto p-2 flex flex-col gap-0.5">
      <!-- 搜索结果：展平列表 -->
      <template v-if="filtering">
        <div
          v-for="n in notes"
          :key="n.id"
          class="hover-accent px-3 py-2.5 cursor-pointer"
          :class="{ 'is-active': n.id === currentId }"
          @click="select(n.id)"
        >
          <div class="text-sm font-medium truncate" :class="n.id === currentId ? 'text-accent-strong' : 'text-tx1'">
            {{ n.title || '未命名' }}
          </div>
          <div class="text-xs text-tx3 mt-0.5">
            <span v-if="n.folder">{{ n.folder }} · </span>{{ relTime(n.updatedAt) }}
          </div>
        </div>
      </template>

      <!-- 浏览态：文件夹层级（折叠组头 + 缩进笔记项） -->
      <template v-else>
        <div v-for="g in groups" :key="g.folder ?? '__unfiled__'">
          <div
            class="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer select-none text-xs font-semibold text-tx2 hover:text-tx1 transition-colors rounded-md"
            :class="dropKey === (g.folder ?? '__unfiled__') ? 'bg-accent-light ring-1 ring-accent/40 text-accent-strong' : ''"
            title="点击折叠/展开；拖拽笔记到此移动进该文件夹"
            @click="toggle(g.folder)"
            @dragover.prevent="dropKey = g.folder ?? '__unfiled__'"
            @dragleave="dropKey = null"
            @drop.prevent="onDrop(g.folder)"
          >
            <el-icon
              class="transition-transform duration-150"
              :class="isCollapsed(g.folder) ? '-rotate-90' : ''"
            >
              <CaretBottom />
            </el-icon>
            <el-icon class="text-tx3">
              <FolderOpened v-if="!isCollapsed(g.folder)" />
              <Folder v-else />
            </el-icon>
            <span class="truncate">{{ g.folder ?? '未归类' }}</span>
            <span class="text-tx3 font-normal">{{ g.items.length }}</span>
          </div>

          <div v-show="!isCollapsed(g.folder)" class="flex flex-col gap-0.5">
            <div
              v-for="n in g.items"
              :key="n.id"
              draggable="true"
              class="hover-accent px-3 py-2 ml-4 cursor-pointer"
              :class="[
                { 'is-active': n.id === currentId },
                draggingId === n.id ? 'opacity-40' : ''
              ]"
              title="拖拽到文件夹组头可移动"
              @click="select(n.id)"
              @dragstart="onDragStart($event, n)"
              @dragend="onDragEnd"
            >
              <div class="text-sm font-medium truncate" :class="n.id === currentId ? 'text-accent-strong' : 'text-tx1'">
                {{ n.title || '未命名' }}
              </div>
              <div class="text-xs text-tx3 mt-0.5" :title="new Date(n.updatedAt).toLocaleString()">
                {{ relTime(n.updatedAt) }}
              </div>
              <div v-if="parseTags(n.tags).length" class="flex flex-wrap gap-1 mt-1.5">
                <el-tag v-for="t in parseTags(n.tags)" :key="t" size="small" effect="plain" round>{{ t }}</el-tag>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- 空态公式 -->
      <div v-if="!notes.length && !loading" class="flex flex-col items-center pt-16 gap-3 px-6">
        <div class="w-14 h-14 rounded-2xl bg-accent-light flex items-center justify-center text-2xl">📝</div>
        <div class="text-sm font-medium text-tx2">还没有笔记</div>
        <div class="text-xs text-tx3 text-center max-w-[200px] leading-relaxed">
          写下第一条想法吧，AI 会自动帮你打标签、写摘要、找关联
        </div>
        <el-button type="primary" round class="!active:scale-95 transition-transform" @click="newNote()">新建笔记</el-button>
      </div>
    </div>
  </div>
</template>
