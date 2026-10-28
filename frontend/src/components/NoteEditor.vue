<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useNotes, useFolders, relTime } from '../composables/useNotes'
import { api } from '../api'
import { decoratePreview, renderMarkdown } from '../markdown'

const { current, currentId, save, remove, saveState, notes } = useNotes()
const folders = useFolders(notes)

/**
 * 面板收起态：只留紧凑顶栏（标题 + 文件夹 chip + 保存态），内容区最大化；
 * 点击顶栏展开标题/文件夹/标签设置。保存成功后自动收起（内容优先的工作流）。
 */
const PANEL_KEY = 'lx_editor_panel_collapsed'
const collapsed = ref(localStorage.getItem(PANEL_KEY) === '1')

function togglePanel() {
  collapsed.value = !collapsed.value
  localStorage.setItem(PANEL_KEY, collapsed.value ? '1' : '0')
}

function collapse() {
  collapsed.value = true
  localStorage.setItem(PANEL_KEY, '1')
}

/* ---- 编辑 / 预览 ---- */
const mode = ref('edit')
const previewHtml = computed(() => renderMarkdown(current.value?.content))
const contentRef = ref(null)
const previewRef = ref(null)

/* ---- 预览增强：标题锚点 + 代码块复制 + 右侧目录 ---- */
const toc = ref([])
const activeHeading = ref('')
let headingObserver = null

function refreshPreview() {
  if (mode.value !== 'preview') {
    toc.value = []
    activeHeading.value = ''
    headingObserver?.disconnect()
    headingObserver = null
    return
  }
  const root = previewRef.value
  toc.value = decoratePreview(root)
  observeHeadings(root)
}

/** 目录高亮：以视口上方 120px 处为参考线，最后一个越过的标题即当前节。 */
function observeHeadings(root) {
  headingObserver?.disconnect()
  headingObserver = null
  if (!root) {
    return
  }
  const heads = [...root.querySelectorAll('h1, h2, h3')]
  if (!heads.length) {
    return
  }
  const sync = () => {
    let current = heads[0].id
    for (const h of heads) {
      if (h.getBoundingClientRect().top <= 120) {
        current = h.id
      }
    }
    activeHeading.value = current
  }
  headingObserver = new IntersectionObserver(sync, { threshold: [0, 0.25, 0.5, 1] })
  heads.forEach((h) => headingObserver.observe(h))
  sync()
}

watch([previewHtml, mode], () => nextTick(refreshPreview), { immediate: true })

onBeforeUnmount(() => headingObserver?.disconnect())

function goTo(id) {
  activeHeading.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 代码块复制：事件委托，避免给每个按钮单独解绑。 */
async function onPreviewClick(e) {
  const btn = e.target.closest('[data-copy]')
  if (!btn) {
    return
  }
  const code = btn.closest('.code-block')?.querySelector('code')
  try {
    await navigator.clipboard.writeText(code?.innerText ?? '')
    btn.textContent = '已复制'
    btn.classList.add('is-done')
    setTimeout(() => {
      btn.textContent = '复制'
      btn.classList.remove('is-done')
    }, 1600)
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function textareaEl() {
  return contentRef.value?.textarea || contentRef.value?.input || null
}

/** 编辑 ⇄ 预览：顶栏一个图标按钮循环切换（图标即下一个动作） */
function toggleMode() {
  mode.value = mode.value === 'edit' ? 'preview' : 'edit'
}

/* ---- 图片：按钮上传 + 粘贴上传，插入光标处 ---- */
// OCR 扩展位（M4 后续）：pickImage 旁边挂拍照/OCR 入口，仍走「更多」菜单或图标按钮，不占正文区
const fileInput = ref(null)
const uploading = ref(false)

function pickImage() {
  fileInput.value?.click()
}

async function onFileChosen(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (file) {
    await uploadAndInsert(file)
  }
}

async function onPaste(e) {
  const files = e.clipboardData?.files
  const image = files && [...files].find((f) => f.type.startsWith('image/'))
  if (image) {
    e.preventDefault()
    await uploadAndInsert(image)
  }
}

async function uploadAndInsert(file) {
  uploading.value = true
  try {
    const { url } = await api.uploadAsset(file)
    insertAtCursor(`![](${url})\n`)
  } catch (err) {
    ElMessage.error(`图片上传失败：${err?.message || err}`)
  } finally {
    uploading.value = false
  }
}

function insertAtCursor(text) {
  const el = textareaEl()
  const content = current.value.content || ''
  if (!el) {
    current.value.content = content + text
    return
  }
  const start = el.selectionStart ?? content.length
  const end = el.selectionEnd ?? start
  current.value.content = content.slice(0, start) + text + content.slice(end)
  nextTick(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + text.length
  })
}

/* ---- 保存 / 删除 ---- */
async function onSave() {
  if (!current.value || !current.value.content || !current.value.content.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  try {
    await save({
      title: current.value.title,
      content: current.value.content,
      tags: current.value.tags,
      folder: current.value.folder
    })
    collapse()
  } catch (e) {
    ElMessage.error(`保存失败：${e && e.message ? e.message : e}`)
  }
}

async function onDelete() {
  await ElMessageBox.confirm('删除这篇笔记？', '提示', { type: 'warning' })
  await remove(currentId.value)
  ElMessage.success('已删除')
}

/** 「更多」菜单：低频操作集中在这里，正文区保持干净 */
function onCommand(command) {
  if (command === 'settings') {
    togglePanel()
  } else if (command === 'image') {
    pickImage()
  } else if (command === 'delete') {
    onDelete()
  }
}

const charCount = () => (current.value?.content || '').length

/** 文件夹输入建议：前缀/包含匹配已有文件夹 */
const queryFolders = (q, cb) => {
  const hits = folders.value
    .filter((f) => !q || f.includes(q))
    .map((f) => ({ value: f }))
  cb(hits)
}
</script>

<template>
  <div v-if="current" class="bg-surface rounded-xl shadow-sm ring-1 ring-line/70 flex flex-col overflow-hidden">
    <!--
      单行顶栏：只放「标题 + 一个保存 + 图标簇」。
      编辑/预览、插入图片、属性、删除全部收进图标按钮与「更多」菜单，正文区不再有任何工具条。
    -->
    <div class="flex items-center gap-1.5 px-4 py-2 select-none">
      <el-icon
        class="cursor-pointer text-tx3 transition-transform duration-150 hover:text-tx1"
        :class="collapsed ? '' : 'rotate-90'"
        @click="togglePanel"
      >
        <CaretRight />
      </el-icon>
      <span
        class="min-w-0 flex-1 cursor-pointer truncate text-base font-semibold text-tx1"
        @click="togglePanel"
      >
        {{ current.title || '未命名' }}
      </span>
      <span v-if="current.folder" class="hidden max-w-[30%] truncate text-[11px] text-tx3 lg:inline">
        {{ current.folder.split('/').join(' / ') }}
      </span>
      <span v-if="saveState === 'saving'" class="ml-1 shrink-0 text-[11px] text-tx3">保存中…</span>
      <span v-else-if="saveState === 'saved'" class="ml-1 shrink-0 text-[11px] text-emerald-600">已保存</span>

      <div class="ml-1 flex shrink-0 items-center gap-0.5">
        <el-button type="primary" size="small" round class="!px-3 !active:scale-95 transition-transform" @click="onSave">
          保存
        </el-button>
        <el-tooltip :content="mode === 'edit' ? '预览' : '编辑'" placement="bottom" :show-after="250">
          <el-button text circle @click="toggleMode">
            <el-icon :size="16"><View v-if="mode === 'edit'" /><EditPen v-else /></el-icon>
          </el-button>
        </el-tooltip>
        <el-tooltip v-if="mode === 'edit'" content="插入图片" placement="bottom" :show-after="250">
          <el-button text circle :loading="uploading" @click="pickImage">
            <el-icon :size="16"><Picture /></el-icon>
          </el-button>
        </el-tooltip>
        <el-dropdown trigger="click" @command="onCommand">
          <el-button text circle>
            <el-icon :size="16"><MoreFilled /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="settings">{{ collapsed ? '编辑属性（文件夹 / 标签）' : '收起属性' }}</el-dropdown-item>
              <el-dropdown-item command="image" :disabled="mode === 'preview'">插入图片</el-dropdown-item>
              <el-dropdown-item command="delete" divided :disabled="!currentId">删除笔记</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileChosen" />
    </div>

    <!-- 展开态设置面板：标题 / 元数据 / 文件夹+标签 -->
    <div v-show="!collapsed" class="px-5 pb-4 flex flex-col gap-3 border-b border-line">
      <el-input
        v-model="current.title"
        placeholder="标题（留空自动取内容首行）"
        class="!mt-4"
        :input-style="{ fontWeight: 600 }"
      />
      <div class="text-xs text-tx3 flex gap-3">
        <span v-if="currentId">{{ relTime(current.updatedAt) }}更新</span>
        <span>{{ charCount() }} 字</span>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <el-autocomplete
          v-model="current.folder"
          :fetch-suggestions="queryFolders"
          placeholder="如：工作 / 学习"
          clearable
          class="w-full"
        >
          <template #prepend>文件夹</template>
        </el-autocomplete>
        <el-input v-model="current.tags" placeholder="逗号分隔">
          <template #prepend>标签</template>
        </el-input>
      </div>
    </div>

    <!-- 正文：编辑态是一块纯文本域，预览态是排版 + 右侧目录；不再有工具条与底部按钮 -->
    <div class="flex flex-col gap-3 px-5 pb-5 pt-1.5">
      <el-input
        v-if="mode === 'edit'"
        ref="contentRef"
        v-model="current.content"
        type="textarea"
        :rows="collapsed ? 22 : 14"
        resize="vertical"
        class="mx-auto w-full max-w-[768px]"
        placeholder="记点什么…（支持 Markdown、$公式$，可直接粘贴图片）"
        @paste="onPaste"
      />
      <div v-else class="flex justify-center gap-8">
        <!-- 宽度由外层容器定（768px ≈ 72 中文字符），prose 自身的 65ch 上限在这里不需要 -->
        <div class="w-full min-w-0 max-w-[768px]">
          <div
            ref="previewRef"
            class="prose prose-sm min-h-[280px] max-w-none rounded-lg bg-app/40 p-5"
            v-html="previewHtml"
            @click="onPreviewClick"
          ></div>
        </div>
        <aside v-if="toc.length" class="sticky top-2 hidden w-[176px] shrink-0 self-start xl:block">
          <div class="mb-2 text-[11px] tracking-[0.08em] text-tx3">本页目录</div>
          <ul class="border-l border-line text-xs">
            <li v-for="t in toc" :key="t.id">
              <button
                type="button"
                class="-ml-px block w-full border-l-2 border-transparent py-1 pr-1 text-left transition-colors hover:text-tx1"
                :class="[
                  t.level > 1 ? 'pl-6' : 'pl-3',
                  activeHeading === t.id ? 'border-accent font-medium text-accent-strong' : 'text-tx3'
                ]"
                @click="goTo(t.id)"
              >
                {{ t.text }}
              </button>
            </li>
          </ul>
        </aside>
      </div>
    </div>
  </div>
  <div v-else class="py-20">
    <el-empty description="选择左侧笔记，或新建一篇" />
  </div>
</template>
