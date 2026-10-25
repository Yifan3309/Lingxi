<script setup>
import { computed, nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useNotes, useFolders, relTime } from '../composables/useNotes'
import { api } from '../api'
import { renderMarkdown } from '../markdown'

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

function textareaEl() {
  return contentRef.value?.textarea || contentRef.value?.input || null
}

/* ---- 图片：按钮上传 + 粘贴上传，插入光标处 ---- */
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
    <!-- 收起态顶栏：点击展开设置；始终可见标题与保存状态 -->
    <div
      class="flex items-center gap-2 px-4 py-2.5 cursor-pointer select-none hover:bg-app/60 transition-colors"
      @click="togglePanel"
    >
      <el-icon
        class="text-tx3 transition-transform duration-150"
        :class="collapsed ? '' : 'rotate-90'"
      >
        <CaretRight />
      </el-icon>
      <span class="text-base font-semibold text-tx1 truncate flex-1">{{ current.title || '未命名' }}</span>
      <el-tag v-if="current.folder" size="small" effect="plain" round class="!text-xs">{{ current.folder }}</el-tag>
      <span v-if="saveState === 'saving'" class="text-xs text-tx3 shrink-0">保存中…</span>
      <span v-else-if="saveState === 'saved'" class="text-xs text-emerald-600 shrink-0">✓ 已保存</span>
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

    <!-- 内容区：编辑/预览切换 + 插入图片 + 正文 -->
    <div class="px-5 py-4 flex flex-col gap-3.5">
      <!-- OCR 扩展位（M4 后续）：插入图片按钮旁挂 el-upload 拍照/OCR 入口 -->
      <div class="flex items-center justify-between">
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="edit">编辑</el-radio-button>
          <el-radio-button value="preview">预览</el-radio-button>
        </el-radio-group>
        <el-button
          size="small"
          :loading="uploading"
          :disabled="mode === 'preview'"
          class="!active:scale-95 transition-transform"
          @click="pickImage"
        >
          <el-icon class="mr-1"><Picture /></el-icon>插入图片
        </el-button>
        <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileChosen" />
      </div>

      <el-input
        v-if="mode === 'edit'"
        ref="contentRef"
        v-model="current.content"
        type="textarea"
        :rows="collapsed ? 20 : 13"
        resize="vertical"
        placeholder="记点什么…（支持 Markdown、$公式$，可直接粘贴图片）"
        @paste="onPaste"
      />
      <div
        v-else
        class="prose prose-sm max-w-none bg-app/40 rounded-lg p-5 min-h-[280px]"
        v-html="previewHtml"
      ></div>

      <div class="flex gap-2">
        <el-button type="primary" class="!active:scale-95 transition-transform" @click="onSave">保存</el-button>
        <el-button type="danger" plain :disabled="!currentId" class="!active:scale-95 transition-transform" @click="onDelete">
          删除
        </el-button>
      </div>
    </div>
  </div>
  <div v-else class="py-20">
    <el-empty description="选择左侧笔记，或新建一篇" />
  </div>
</template>
