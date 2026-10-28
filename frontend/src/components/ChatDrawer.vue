<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useChat } from '../composables/useChat'
import { useNotes } from '../composables/useNotes'
import { api, fmtScore } from '../api'
import LingXiMark from './LingXiMark.vue'
import ChatAttachmentPicker from './ChatAttachmentPicker.vue'
import ChatAttachmentChips from './ChatAttachmentChips.vue'

const { visible, sessionId, messages, streaming, loadHistory, newSession, ask } = useChat()
const { select } = useNotes()

const input = ref('')
const scrollBox = ref(null)
const inputRef = ref(null)
const uploadInput = ref(null)

/* ---- @ 附件：检测 → 浮层 → chips ---- */
const attachments = ref([])
const mentionOpen = ref(false)
const mentionQuery = ref('')
const mentionStart = ref(-1)

const hasUploading = computed(() => attachments.value.some((a) => a.status === 'uploading'))
const readyAttachments = computed(() => attachments.value.filter((a) => a.status === 'ready'))

const suggestions = [
  '我们打算用什么数据库？',
  '我在学什么？总结一下',
  '帮我找找相关的笔记'
]

/** 输入中末尾出现 @xxx（行首或空白后）时打开选择浮层。 */
function onComposerInput(v) {
  const m = /(?:^|\s)@([^\s@]{0,40})$/.exec(v || '')
  if (m) {
    mentionStart.value = m.index + m[0].indexOf('@')
    mentionQuery.value = m[1]
    mentionOpen.value = true
  } else {
    mentionOpen.value = false
  }
}

/** 把输入框里的 @query 令牌删掉（选中附件/切换动作后调用）。 */
function clearMentionToken() {
  if (mentionStart.value >= 0) {
    const len = mentionQuery.value.length + 1
    input.value = input.value.slice(0, mentionStart.value) + input.value.slice(mentionStart.value + len)
  }
  mentionStart.value = -1
  mentionQuery.value = ''
}

function pickMention(item) {
  clearMentionToken()
  mentionOpen.value = false
  const exists = attachments.value.some(
    (a) => a.type === item.type && (a.id || a.name) === (item.id || item.name)
  )
  if (!exists) {
    attachments.value.push({ ...item, status: 'ready' })
  }
  nextTick(() => inputRef.value?.focus?.())
}

function onPickerAction(kind) {
  clearMentionToken()
  mentionOpen.value = false
  if (kind === 'local') {
    localVisible.value = true
  } else {
    uploadInput.value?.click()
  }
}

function removeAttachment(i) {
  attachments.value.splice(i, 1)
}

/* ---- 本地路径 ---- */
const localVisible = ref(false)
const localPath = ref('')
const recents = ref(loadRecents())

function loadRecents() {
  try {
    return JSON.parse(localStorage.getItem('lx_chat_local_paths') || '[]')
  } catch {
    return []
  }
}

function addLocalPath(p) {
  const path = (p || '').trim()
  if (!path) {
    return
  }
  if (!/^(?:[a-zA-Z]:[\\/]|\/|\\\\)/.test(path)) {
    ElMessage.error('请填写绝对路径（如 D:\\notes\\a.md）')
    return
  }
  const name = path.split(/[\\/]/).filter(Boolean).pop() || path
  if (!attachments.value.some((a) => a.type === 'localFile' && a.path === path)) {
    attachments.value.push({ type: 'localFile', path, name, status: 'ready' })
  }
  recents.value = [path, ...recents.value.filter((x) => x !== path)].slice(0, 5)
  localStorage.setItem('lx_chat_local_paths', JSON.stringify(recents.value))
  localVisible.value = false
  localPath.value = ''
  nextTick(() => inputRef.value?.focus?.())
}

/* ---- 上传 ---- */
const CHAT_ACCEPT =
  '.md,.markdown,.txt,.pdf,.docx,.doc,.pptx,.ppt,.html,.htm,.json,.yaml,.yml,.xml,.csv,' +
  '.java,.py,.js,.ts,.sql,.sh,.go,.rs,.c,.cpp,.h,.cs'

async function onUploadChosen(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  const item = { type: 'upload', name: file.name, status: 'uploading' }
  attachments.value.push(item)
  try {
    const r = await api.uploadChatAttachment(file)
    item.uploadId = r.uploadId
    item.chars = r.chars
    item.name = r.name || file.name
    item.status = 'ready'
  } catch (err) {
    item.status = 'error'
    item.error = String(err?.message || err)
    ElMessage.error(`附件解析失败：${item.error}`)
  }
}

/* ---- 发送 ---- */
async function send(q) {
  const question = (q || input.value).trim()
  if (!question || streaming.value) {
    return
  }
  if (hasUploading.value) {
    ElMessage.info('附件解析中，稍候再发送')
    return
  }
  input.value = ''
  mentionOpen.value = false
  const payload = readyAttachments.value.map((a) => ({ ...a }))
  attachments.value = []
  await ask(question, payload)
}

function openNote(noteId) {
  if (!noteId) {
    return
  }
  visible.value = false
  select(noteId)
}

watch(
  () => messages.value.map((m) => m.content).join(''),
  async () => {
    await nextTick()
    if (scrollBox.value) {
      scrollBox.value.scrollTop = scrollBox.value.scrollHeight
    }
  }
)
</script>

<template>
  <el-drawer v-model="visible" title="与你的笔记对话" size="440px" @open="loadHistory">
    <div class="flex flex-col h-full">
      <div class="flex items-center gap-2 pb-2.5 border-b border-line text-xs text-tx3">
        会话 <span class="font-mono">{{ sessionId }}</span>
        <span class="flex-1"></span>
        <el-button size="small" text type="primary" @click="newSession">新会话</el-button>
        <el-button size="small" text @click="loadHistory">刷新</el-button>
      </div>

      <div ref="scrollBox" class="flex-1 overflow-y-auto py-3 flex flex-col gap-4">
        <!-- 空态欢迎页（借鉴 nowen-note）：渐变 logo + 建议问题卡片 -->
        <div v-if="!messages.length" class="flex flex-col items-center pt-10 gap-4 px-4">
          <div class="w-14 h-14 rounded-2xl bg-surface ring-1 ring-line flex items-center justify-center shadow-lg shadow-violet-500/15">
            <LingXiMark :size="34" />
          </div>
          <div class="text-sm font-medium text-tx2">问点关于笔记的问题</div>
          <div class="text-xs text-tx3 text-center max-w-[220px] leading-relaxed">
            回答基于你的全部笔记生成，并附来源可跳转；输入 @ 可链接笔记/文件到本轮对话
          </div>
          <div class="flex flex-col gap-2 w-full mt-2">
            <div
              v-for="s in suggestions"
              :key="s"
              class="w-full text-left text-sm text-tx2 bg-app border border-line rounded-xl px-3.5 py-2.5 cursor-pointer
                     transition-all hover:-translate-y-0.5 hover:border-violet-300 hover:text-tx1 active:scale-[0.98]"
              @click="send(s)"
            >
              {{ s }}
            </div>
          </div>
        </div>

        <!-- 消息流：用户 = 主色切角气泡；AI = 裸排版（借鉴 nowen-note 非对称布局） -->
        <div v-for="(m, i) in messages" :key="i" class="flex flex-col" :class="m.role === 'user' ? 'items-end' : 'items-start'">
          <template v-if="m.role === 'user'">
            <div class="max-w-[88%] px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap break-words text-white rounded-2xl rounded-br-md bg-accent">
              {{ m.content }}
            </div>
            <ChatAttachmentChips
              v-if="m.attachments && m.attachments.length"
              class="mt-1"
              :attachments="m.attachments"
              readonly
            />
          </template>
          <div v-else class="max-w-[92%] text-sm leading-relaxed whitespace-pre-wrap break-words text-tx1">
            <template v-if="m.content">{{ m.content }}</template>
            <span v-else-if="streaming && i === messages.length - 1">
              <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
            </span>
          </div>

          <div v-if="m.error" class="text-xs text-red-500 mt-1 max-w-[88%]">⚠ {{ m.error }}</div>

          <div v-if="m.sources && m.sources.length" class="mt-1.5 max-w-[92%] pl-2.5 border-l-2 border-violet-300">
            <div class="text-xs text-tx3 mb-0.5">来源（点击跳转笔记）</div>
            <div
              v-for="(s, si) in m.sources"
              :key="si"
              class="hover-accent text-sm py-1 cursor-pointer text-tx1"
              @click="openNote(s.noteId)"
            >
              <span class="text-violet-400 font-medium">[{{ si + 1 }}]</span>
              {{ s.title || '未命名' }}
              <span class="text-xs text-tx3">· {{ fmtScore(s.score) }}</span>
              <div class="text-xs text-tx3 truncate">{{ s.excerpt }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区：附件 chips + @ 浮层 + 多行输入 -->
      <div class="pt-2.5 border-t border-line">
        <ChatAttachmentChips
          v-if="attachments.length"
          class="mb-2"
          :attachments="attachments"
          @remove="removeAttachment"
        />
        <div class="relative">
          <ChatAttachmentPicker
            :open="mentionOpen"
            :query="mentionQuery"
            @pick="pickMention"
            @action="onPickerAction"
            @close="mentionOpen = false"
          />
          <div class="flex gap-2 items-end">
            <el-input
              ref="inputRef"
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              placeholder="提问，或输入 @ 链接笔记/文件到本轮…"
              :disabled="streaming"
              @input="onComposerInput"
              @keydown.enter.exact.prevent="send()"
            />
            <el-button type="primary" :loading="streaming" @click="send()">发送</el-button>
          </div>
        </div>
        <input ref="uploadInput" type="file" class="hidden" :accept="CHAT_ACCEPT" @change="onUploadChosen" />
      </div>
    </div>

    <!-- 本地文件路径（只读，后端仅放行本机请求） -->
    <el-dialog v-model="localVisible" title="链接本地文件" width="420px" append-to-body>
      <el-input
        v-model="localPath"
        placeholder="绝对路径，如 D:\papers\rag.pdf"
        clearable
        @keyup.enter="addLocalPath(localPath)"
      />
      <div v-if="recents.length" class="mt-2">
        <div class="text-xs text-tx3 mb-1">最近使用</div>
        <div
          v-for="p in recents"
          :key="p"
          class="text-xs text-tx2 hover-accent cursor-pointer truncate py-0.5"
          :title="p"
          @click="addLocalPath(p)"
        >
          {{ p }}
        </div>
      </div>
      <div class="text-xs text-tx3 mt-2 leading-relaxed">
        只读引用：不入库、不写入知识库；仅本机可读，支持 md/txt/pdf/docx 等文本文件（图片/扫描 PDF 暂不支持）
      </div>
      <template #footer>
        <el-button @click="localVisible = false">取消</el-button>
        <el-button type="primary" @click="addLocalPath(localPath)">链接</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>
