<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useNotes, parseTags, relTime } from '../composables/useNotes'
import { useUi } from '../composables/useUi'
import { api } from '../api'
import VaultImportDialog from './VaultImportDialog.vue'

const { notes, currentId, select, newNote, searchMode, searchText, refresh, loading, loadError, moveToFolder } = useNotes()

/* ---- 斜杠命令入口（/import /vault）：useUi 请求计数通知属主组件响应 ---- */
const { importTick, vaultTick } = useUi()
watch(importTick, () => pickImport())
watch(vaultTick, () => {
  vaultVisible.value = true
})

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

/* ---- 旧仓库一键迁移 ---- */
const vaultVisible = ref(false)

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

/* ---- 多层文件夹：folder 为 / 分隔路径，前端派生树 ---- */
const collator = new Intl.Collator('zh')

/** 路径建树：中间层级自动创建（children 以完整路径为键），笔记挂在末段节点 */
const tree = computed(() => {
  const root = new Map()
  for (const n of notes.value) {
    const segs = (n.folder || '').split('/').map((s) => s.trim()).filter(Boolean)
    if (!segs.length) {
      continue
    }
    let level = root
    let path = ''
    let node = null
    for (const name of segs) {
      path = path ? path + '/' + name : name
      if (!level.has(path)) {
        level.set(path, { path, name, notes: [], children: new Map() })
      }
      node = level.get(path)
      level = node.children
    }
    node.notes.push(n)
  }
  return root
})

/** 聚合计数：本层笔记 + 全部后代 */
function totalOf(node) {
  let total = node.notes.length
  for (const child of node.children.values()) {
    total += totalOf(child)
  }
  return total
}

/** 展平渲染行：兄弟文件夹按名称 zh 排序；展开时先本层笔记、后子文件夹块；「未归类」排最后 */
const rows = computed(() => {
  const out = []
  const walk = (children, depth) => {
    const nodes = [...children.values()].sort((a, b) => collator.compare(a.name, b.name))
    for (const node of nodes) {
      out.push({ type: 'folder', key: node.path, path: node.path, name: node.name, depth, count: totalOf(node) })
      if (!isCollapsed(node.path)) {
        for (const n of node.notes) {
          out.push({ type: 'note', key: n.id, note: n, depth: depth + 1 })
        }
        walk(node.children, depth + 1)
      }
    }
  }
  walk(tree.value, 0)
  const unfiled = notes.value.filter((n) => !(n.folder || '').trim())
  if (unfiled.length) {
    out.push({ type: 'folder', key: '__unfiled__', path: null, name: '未归类', depth: 0, count: unfiled.length })
    if (!isCollapsed(null)) {
      for (const n of unfiled) {
        out.push({ type: 'note', key: n.id, note: n, depth: 1 })
      }
    }
  }
  return out
})
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="p-3 border-b border-line flex flex-col gap-2.5">
      <div class="flex flex-wrap items-center justify-between gap-x-2 gap-y-1.5">
        <el-radio-group v-model="searchMode" size="small">
          <el-radio-button value="keyword">关键词</el-radio-button>
          <el-radio-button value="tag">标签</el-radio-button>
        </el-radio-group>
        <div class="flex flex-wrap items-center gap-1.5">
          <el-button type="primary" size="small" round class="!active:scale-95 transition-transform" @click="newNote()">
            + 新建
          </el-button>
          <el-button size="small" round :loading="importing" class="!active:scale-95 transition-transform" title="导入文档（md/docx/pptx/pdf/html/代码/图片）" @click="pickImport">
            导入
          </el-button>
          <el-button size="small" round class="!active:scale-95 transition-transform" title="网页剪藏：粘贴 URL 存为笔记" @click="clipVisible = true">
            剪藏
          </el-button>
          <el-button size="small" round class="!active:scale-95 transition-transform" title="旧仓库一键迁移：本机 md 文件夹 → 多层文件夹笔记" @click="vaultVisible = true">
            迁移
          </el-button>
        </div>
        <input ref="importInput" type="file" multiple :accept="IMPORT_ACCEPT" class="hidden" @change="onImportChosen" />
      </div>

      <VaultImportDialog v-model="vaultVisible" />

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

      <!-- 浏览态：多层文件夹树（缩进随深度，折叠/拖拽迁移到任意层级） -->
      <template v-else>
        <template v-for="row in rows" :key="(row.type === 'folder' ? 'f:' : 'n:') + row.key">
          <div
            v-if="row.type === 'folder'"
            class="flex items-center gap-1.5 pr-2 py-1.5 cursor-pointer select-none text-[11px] font-medium tracking-[0.05em] text-tx3 hover:text-tx1 transition-colors rounded-md"
            :class="[
              dropKey === row.key ? 'bg-accent-light ring-1 ring-accent/40 text-accent-strong' : '',
              row.depth === 0 ? 'mt-1' : ''
            ]"
            :style="{ paddingLeft: 8 + row.depth * 16 + 'px' }"
            title="点击折叠/展开；拖拽笔记到此移动进该文件夹"
            @click="toggle(row.path)"
            @dragover.prevent="dropKey = row.key"
            @dragleave="dropKey = null"
            @drop.prevent="onDrop(row.path)"
          >
            <el-icon
              class="transition-transform duration-150"
              :class="isCollapsed(row.path) ? '-rotate-90' : ''"
            >
              <CaretBottom />
            </el-icon>
            <el-icon class="text-tx3">
              <FolderOpened v-if="!isCollapsed(row.path)" />
              <Folder v-else />
            </el-icon>
            <span class="truncate">{{ row.name }}</span>
            <span class="text-tx3 font-normal">{{ row.count }}</span>
          </div>

          <div
            v-else
            draggable="true"
            class="hover-accent pr-3 py-2 cursor-pointer"
            :class="[
              { 'is-active': row.note.id === currentId },
              draggingId === row.note.id ? 'opacity-40' : ''
            ]"
            :style="{ paddingLeft: 28 + (row.depth - 1) * 16 + 'px' }"
            title="拖拽到文件夹行可移动"
            @click="select(row.note.id)"
            @dragstart="onDragStart($event, row.note)"
            @dragend="onDragEnd"
          >
            <div class="text-sm font-medium truncate" :class="row.note.id === currentId ? 'text-accent-strong' : 'text-tx1'">
              {{ row.note.title || '未命名' }}
            </div>
            <div class="text-xs text-tx3 mt-0.5" :title="new Date(row.note.updatedAt).toLocaleString()">
              {{ relTime(row.note.updatedAt) }}
            </div>
            <div v-if="parseTags(row.note.tags).length" class="flex flex-wrap gap-1 mt-1.5">
              <el-tag v-for="t in parseTags(row.note.tags)" :key="t" size="small" effect="plain" round>{{ t }}</el-tag>
            </div>
          </div>
        </template>
      </template>

      <!-- 空态：加载失败与"确实没有笔记"必须分开，否则排查时会被误认为空库 -->
      <div v-if="!notes.length && !loading && loadError" class="flex flex-col items-center pt-16 gap-3 px-6">
        <div class="w-14 h-14 rounded-2xl bg-red-50 flex items-center justify-center text-2xl">!</div>
        <div class="text-sm font-medium text-tx2">笔记加载失败</div>
        <div class="text-xs text-tx3 text-center max-w-[220px] leading-relaxed break-all">{{ loadError }}</div>
        <el-button type="primary" round class="!active:scale-95 transition-transform" @click="refresh">重试</el-button>
      </div>
      <div v-else-if="!notes.length && !loading" class="flex flex-col items-center pt-16 gap-3 px-6">
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
