<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useChat } from '../composables/useChat'
import { useNotes } from '../composables/useNotes'
import { renderMarkdown } from '../markdown'
import { api, fmtScore } from '../api'
import LingXiMark from './LingXiMark.vue'
import TokenPalette from './TokenPalette.vue'
import ChatAttachmentChips from './ChatAttachmentChips.vue'
import { commands, GROUP_ORDER } from '../commands/registry'
import { filterCommands, isSlashBrowsing, parseCommand } from '../commands/match'
import { useCommands } from '../commands/useCommands'

const { visible, sessionId, messages, streaming, loadHistory, newSession, ask } = useChat()
const { select } = useNotes()
const router = useRouter()

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

/* ---- / 命令：面板、参数提示、help 常驻、首次露出（doc/07 §2.2 状态机） ---- */
const slashOpen = ref(false)
const helpMode = ref(false)
const introMode = ref(false)
const paramHint = ref('')
const paletteActive = ref('')
let introTimer = null

const commandsApi = useCommands({
  openHelp: () => {
    helpMode.value = true
    slashOpen.value = true
    paramHint.value = ''
  },
  openAttachment: openAttachmentEntry
})

/** 输入中末尾出现 @xxx（行首或空白后）时打开选择浮层；/ 命令优先（两者互斥）。 */
function onComposerInput(v) {
  const text = v || ''
  if (composing.value) {
    return
  }
  stopIntro()
  helpMode.value = false
  if (text.startsWith('/')) {
    // 位置铁律：必须从第 0 个字符开始；首词内没出现空格 = 面板浏览阶段
    mentionOpen.value = false
    if (isSlashBrowsing(text)) {
      slashOpen.value = true
      paramHint.value = ''
      return
    }
    slashOpen.value = false
    // 空格之后：有参命令换成一行浅色参数提示；无参/未知命令不提示
    const parsed = parseCommand(text)
    const cmd = parsed && commandsApi.byName(parsed.name)
    paramHint.value = cmd?.arg?.hint || ''
    return
  }
  slashOpen.value = false
  paramHint.value = ''
  const m = /(?:^|\s)@([^\s@]{0,40})$/.exec(text)
  if (m) {
    mentionStart.value = m.index + m[0].indexOf('@')
    mentionQuery.value = m[1]
    mentionOpen.value = true
  } else {
    mentionOpen.value = false
  }
}

/* IME 组词期间不判定（拼音候选阶段的瞬态字符会引发面板闪烁） */
const composing = ref(false)
function onCompositionEnd() {
  composing.value = false
  onComposerInput(input.value)
}

/** 命令面板候选：浏览阶段按 query 打分；/help 固定展开全量 */
const slashQuery = computed(() => {
  if (!slashOpen.value) {
    return ''
  }
  const text = input.value
  return isSlashBrowsing(text) ? text.slice(1) : ''
})
const slashCandidates = computed(() =>
  filterCommands(commands, slashQuery.value, { expandAll: helpMode.value })
)
const slashGroups = computed(() => {
  const items = slashCandidates.value.map((cmd) => ({
    key: 'cmd:' + cmd.name,
    kind: 'cmd',
    cmd,
    main: '/' + cmd.name + (cmd.arg ? ' ' + cmd.arg.placeholder : ''),
    meta: cmd.title,
    sub: cmd.hint || ''
  }))
  return GROUP_ORDER.map((label) => ({ label, items: items.filter((i) => i.cmd.group === label) }))
})

/* ---- @ 浮层候选（原 ChatAttachmentPicker 的数据逻辑移到宿主，展示共用 TokenPalette） ---- */
const attachNotes = ref([])
const attachLoaded = ref(false)

watch(mentionOpen, async (v) => {
  if (v && !attachLoaded.value) {
    try {
      attachNotes.value = await api.listNotes()
    } catch {
      attachNotes.value = []
    }
    attachLoaded.value = true
  }
})

const attachFolders = computed(() => {
  const set = new Set()
  for (const n of attachNotes.value) {
    const segs = (n.folder || '').split('/').map((s) => s.trim()).filter(Boolean)
    let path = ''
    for (const seg of segs) {
      path = path ? path + '/' + seg : seg
      set.add(path)
    }
  }
  const q = mentionQuery.value.trim().toLowerCase()
  return [...set]
    .sort((a, b) => a.localeCompare(b, 'zh'))
    .filter((f) => !q || f.toLowerCase().includes(q))
    .slice(0, 5)
})

const attachMatched = computed(() => {
  const q = mentionQuery.value.trim().toLowerCase()
  const list = q
    ? attachNotes.value.filter((n) => (n.title || '').toLowerCase().includes(q))
    : attachNotes.value
  return list.slice(0, 8)
})

const attachGroups = computed(() => [
  {
    label: '文件夹',
    items: attachFolders.value.map((f) => ({
      key: 'att:f:' + f,
      kind: 'attach',
      att: { type: 'folder', name: f },
      main: f
    }))
  },
  {
    label: '笔记',
    items: attachMatched.value.map((n) => ({
      key: 'att:n:' + n.id,
      kind: 'attach',
      att: { type: 'note', id: n.id, name: n.title || '未命名' },
      main: n.title || '未命名'
    }))
  },
  {
    label: '',
    items: [
      { key: 'att:a:local', kind: 'attach-action', action: 'local', main: '链接本地文件…' },
      { key: 'att:a:upload', kind: 'attach-action', action: 'upload', main: '上传文件…' }
    ]
  }
])

/* ---- 浮层状态归一：@ 与 / 共用一个高亮项 ---- */
const paletteOpen = computed(() => mentionOpen.value || slashOpen.value)
const paletteGroups = computed(() => (mentionOpen.value ? attachGroups.value : slashGroups.value))
const paletteItems = computed(() => paletteGroups.value.flatMap((g) => g.items))

watch(paletteItems, (items) => {
  if (!items.length) {
    paletteActive.value = ''
    return
  }
  if (!items.some((i) => i.key === paletteActive.value)) {
    // 命令模式锚定打分最高的候选（面板按分组渲染，显示序 ≠ 分数序）；@ 模式取第一项
    const preferred = mentionOpen.value ? items[0].key : 'cmd:' + (slashCandidates.value[0]?.name ?? '')
    paletteActive.value = items.some((i) => i.key === preferred) ? preferred : items[0].key
  }
})

/** 面板/提示永不修改输入文本：关闭后 Enter 按当前文本重新裁决（兜底原则）。 */
function closePalette() {
  slashOpen.value = false
  mentionOpen.value = false
  helpMode.value = false
  stopIntro()
  paramHint.value = ''
}

/** /help 固定展开 / 首次露出（3 秒收起，仅一次） */
function stopIntro() {
  if (introMode.value) {
    introMode.value = false
    clearTimeout(introTimer)
    introTimer = null
  }
}

watch(visible, (v) => {
  if (!v || localStorage.getItem('lx_cmd_intro')) {
    return
  }
  localStorage.setItem('lx_cmd_intro', '1')
  introMode.value = true
  slashOpen.value = true
  introTimer = setTimeout(() => {
    introMode.value = false
    slashOpen.value = false
    introTimer = null
  }, 3000)
})

/* ---- 键盘：↑↓/Enter/Esc 全部可用，焦点不逃出输入框 ---- */
function isPlainEnter(e) {
  return !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey
}

function onComposerKeydown(e) {
  if (e.isComposing || composing.value) {
    return
  }
  if (e.key === 'Escape') {
    if (paletteOpen.value || paramHint.value) {
      // 只关面板/提示、保留文本；拦住冒泡，避免 el-drawer 的 Esc 把整个抽屉关掉
      e.preventDefault()
      e.stopPropagation()
      closePalette()
    }
    return
  }
  if (paletteOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const items = paletteItems.value
      if (!items.length) {
        return
      }
      const idx = items.findIndex((i) => i.key === paletteActive.value)
      const step = e.key === 'ArrowDown' ? 1 : items.length - 1
      paletteActive.value = items[(idx < 0 ? 0 : idx + step) % items.length].key
      return
    }
    if (e.key === 'Enter' && isPlainEnter(e)) {
      e.preventDefault()
      const item = paletteItems.value.find((i) => i.key === paletteActive.value)
      if (item) {
        onPaletteSelect(item)
      } else {
        // 空候选（词表闸门）：未知命令不拦截，原样发送
        closePalette()
        submitComposer()
      }
    }
    return
  }
  if (e.key === 'Enter' && isPlainEnter(e)) {
    e.preventDefault()
    submitComposer()
  }
}

/** 浮层点选（select 事件带 key，键盘路径带 item）：统一归到 onPaletteSelect。 */
function onPaletteSelectKey(key) {
  onPaletteSelect(paletteItems.value.find((i) => i.key === key))
}

/** 浮层点选：@ 选中附件/入口；/ 补全令牌（有参）或直接执行（无参）。 */
function onPaletteSelect(item) {
  if (!item) {
    return
  }
  if (item.kind === 'attach-action') {
    clearMentionToken()
    mentionOpen.value = false
    if (item.action === 'local') {
      localVisible.value = true
    } else {
      uploadInput.value?.click()
    }
    return
  }
  if (item.kind === 'attach') {
    pickMention(item.att)
    return
  }
  const cmd = item.cmd
  if (cmd.arg) {
    // 有参：补成 "/name " 并把光标放到参数位；面板位置换成参数提示
    closePalette()
    paramHint.value = cmd.arg.hint || ''
    input.value = '/' + cmd.name + ' '
    nextTick(focusInputEnd)
  } else {
    runCommand(cmd, '')
  }
}

function focusInputEnd() {
  const el = inputRef.value
  el?.focus?.()
  const t = el?.textarea
  if (t) {
    t.selectionStart = t.selectionEnd = t.value.length
  }
}

/** 命令已成立：清输入与面板态；导航/有结果的命令顺手收抽屉，让用户看到落地效果 */
function runCommand(cmd, args) {
  resetComposer()
  if (cmd.close) {
    visible.value = false
  }
  commandsApi.execute(cmd, args)
}

function resetComposer() {
  input.value = ''
  closePalette()
}

/** 输入区 Enter / 发送按钮的统一入口：命令闸门在前，普通消息在后。 */
function submitComposer() {
  const text = input.value
  if (/^\/$/.test(text)) {
    // 单独一个 / 不发送，只展开面板
    slashOpen.value = true
    return
  }
  const r = commandsApi.resolve(text)
  if (r.kind === 'pass') {
    send(text)
    return
  }
  if (r.kind === 'noarg-with-args') {
    ElMessage.warning(`「/${r.cmd.name}」不接受参数`)
    return
  }
  if (r.kind === 'missing-arg') {
    // 缺参：只提示、不发送
    paramHint.value = r.message
    return
  }
  if (r.kind === 'invalid-arg') {
    ElMessage.warning(r.message)
    return
  }
  runCommand(r.cmd, r.args)
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

/** /attach：等价于敲 @ 后再选类目 */
function openAttachmentEntry(kind) {
  if (kind === 'file') {
    localVisible.value = true
  } else if (kind === 'upload') {
    uploadInput.value?.click()
  } else {
    // note / folder：列出笔记与文件夹的浮层
    mentionOpen.value = true
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

/** 点击来源：打开笔记（在图谱/回顾页点来源也落回工作台）。 */
async function openNote(source) {
  const noteId = source?.noteId
  if (!noteId) {
    return
  }
  visible.value = false
  if (router.currentRoute.value.path !== '/notes') {
    await router.push('/notes')
  }
  await select(noteId)
}

/* ---- <think> 折叠框：解析思考段；流式未闭合默认展开（限高滚动，见 think-body），闭合后默认收起，手动切换优先 ---- */
const thinkOpen = ref({})

/**
 * 把助手内容切成 [{think, text, unclosed?}] 段。
 * 流式逐字到达时结尾可能是 "<think>" 的半截前缀，先藏住避免闪现；
 * 空思考段（<think>\n\n</think>）不渲染。
 */
function parseThink(content) {
  let text = content || ''
  for (let k = Math.min(7, text.length); k > 0; k--) {
    if ('<think>'.startsWith(text.slice(text.length - k))) {
      text = text.slice(0, text.length - k)
      break
    }
  }
  const segments = []
  let cursor = 0
  while (true) {
    const open = text.indexOf('<think>', cursor)
    if (open < 0) {
      break
    }
    if (open > cursor) {
      segments.push({ think: false, text: text.slice(cursor, open) })
    }
    const close = text.indexOf('</think>', open + 7)
    if (close < 0) {
      const body = text.slice(open + 7)
      if (body.trim()) {
        segments.push({ think: true, text: body, unclosed: true })
      }
      return segments
    }
    const body = text.slice(open + 7, close)
    if (body.trim()) {
      segments.push({ think: true, text: body })
    }
    cursor = close + 8
  }
  const tail = text.slice(cursor)
  if (tail) {
    segments.push({ think: false, text: tail })
  }
  return segments
}

function thinkIsOpen(i, seg) {
  if (thinkOpen.value[i] !== undefined) {
    return thinkOpen.value[i]
  }
  // 默认：流式思考（最后一条消息且 <think> 未闭合）展开在限高滚动框里，闭合即收起
  return !!(seg.unclosed && streaming.value && i === messages.value.length - 1)
}

function thinkLabel(i, seg) {
  return seg.unclosed && streaming.value && i === messages.value.length - 1 ? 'Thinking...' : 'Thought for a moment'
}

function toggleThink(i, seg) {
  thinkOpen.value = { ...thinkOpen.value, [i]: !thinkIsOpen(i, seg) }
}

/* ---- 流式阶段状态行：缓解等待焦虑。阶段由真实信号推导（首 token / 思考流 / 正文流），
   检索期是真正的静默窗口，用轮换文案撑住观感——不求准确，只求"活着"。 ---- */
const sentAt = ref(0)
const nowTick = ref(0)
let stageTimer = null
watch(streaming, (v) => {
  if (v) {
    sentAt.value = Date.now()
    stageTimer = setInterval(() => {
      nowTick.value++
    }, 1500)
  } else if (stageTimer) {
    clearInterval(stageTimer)
    stageTimer = null
  }
})

const SEARCH_PHRASES = ['Searching your notes...', 'Reading related passages...', 'Gathering context...']

function streamStage(m) {
  nowTick.value // 触碰以驱动轮换重渲染（检索期没有 token 到达，靠定时器刷新文案）
  if (!m.content) {
    return SEARCH_PHRASES[Math.floor((Date.now() - sentAt.value) / 3000) % SEARCH_PHRASES.length]
  }
  const segs = parseThink(m.content)
  if (segs.some((s) => s.think && s.unclosed)) {
    return 'Thinking...'
  }
  if (segs.some((s) => !s.think && s.text.trim())) {
    return 'Writing answer...'
  }
  return 'Thinking...' // 思考闭合与正文首 token 之间的短暂空档
}

/* ---- 可发现性（doc/07 §6）：空输入时占位符轮换提示 ---- */
const PLACEHOLDERS = ['提问，或输入 @ 链接笔记/文件到本轮…', '输入 / 查看命令']
const placeholderIdx = ref(0)
let placeholderTimer = null
const placeholder = computed(() =>
  input.value || paletteOpen.value ? PLACEHOLDERS[0] : PLACEHOLDERS[placeholderIdx.value]
)

onMounted(() => {
  placeholderTimer = setInterval(() => {
    placeholderIdx.value = (placeholderIdx.value + 1) % PLACEHOLDERS.length
  }, 6000)
})

onBeforeUnmount(() => {
  clearInterval(placeholderTimer)
  clearTimeout(introTimer)
  if (stageTimer) {
    clearInterval(stageTimer)
  }
})

watch(
  () => messages.value.map((m) => m.content).join(''),
  async () => {
    await nextTick()
    if (scrollBox.value) {
      scrollBox.value.scrollTop = scrollBox.value.scrollHeight
      // 流式思考框限高（约 8 行）：把最新思考滚进可视底部，多余的往上滚出去
      const live = scrollBox.value.querySelector('.think-body[data-live]')
      if (live) {
        live.scrollTop = live.scrollHeight
      }
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
          <div class="text-xs text-tx3 text-center max-w-[240px] leading-relaxed">
            回答基于你的全部笔记生成，并附来源可跳转；输入 @ 可链接笔记/文件到本轮，输入 / 可直达常用动作
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
          <template v-else>
            <!-- 流式阶段状态行：检索 → 思考 → 撰写（完成后整行消失） -->
            <div
              v-if="streaming && i === messages.length - 1"
              class="flex items-center gap-2 text-sm text-tx3"
            >
              <el-icon :size="14" class="animate-pulse"><Opportunity /></el-icon>
              <span class="animate-pulse">{{ streamStage(m) }}</span>
            </div>
            <!-- 思考段 → 折叠框；正文段 → 裸排版 -->
            <template v-for="(seg, si) in parseThink(m.content)" :key="si">
              <div v-if="seg.think" class="max-w-[92%] w-full">
                <button
                  type="button"
                  class="flex cursor-pointer items-center gap-2 border-0 bg-transparent py-1 text-sm text-tx3 transition-colors hover:text-tx2"
                  @click="toggleThink(i, seg)"
                >
                  <el-icon v-if="thinkIsOpen(i, seg)" :size="12"><ArrowDown /></el-icon>
                  <el-icon v-else :size="16"><Opportunity /></el-icon>
                  {{ thinkLabel(i, seg) }}
                </button>
                <div
                  v-if="thinkIsOpen(i, seg)"
                  class="think-body mt-1 max-h-40 overflow-y-auto pl-5 text-sm leading-relaxed text-tx3 whitespace-pre-wrap break-words"
                  :data-live="seg.unclosed && streaming && i === messages.length - 1 ? '1' : undefined"
                >{{ seg.text.trim() }}</div>
              </div>
              <div
                v-else-if="seg.text"
                class="md-chat prose prose-sm max-w-[92%] text-sm leading-relaxed break-words text-tx1"
                v-html="renderMarkdown(seg.text)"
              ></div>
            </template>
          </template>

          <div v-if="m.error" class="text-xs text-red-500 mt-1 max-w-[88%]">⚠ {{ m.error }}</div>

          <div v-if="m.sources && m.sources.length" class="mt-1.5 max-w-[92%] pl-2.5 border-l-2 border-violet-300">
            <div class="text-xs text-tx3 mb-0.5">来源（点击跳转笔记）</div>
            <div
              v-for="(s, si) in m.sources"
              :key="si"
              class="hover-accent text-sm py-1 cursor-pointer text-tx1"
              @click="openNote(s)"
            >
              <span class="text-violet-400 font-medium">[{{ si + 1 }}]</span>
              {{ s.title || '未命名' }}
              <span class="text-xs text-tx3">· {{ fmtScore(s.score) }}</span>
              <div class="text-xs text-tx3 truncate">{{ s.excerpt }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区：附件 chips + @// 共用浮层 + 参数提示 + 多行输入 -->
      <div class="pt-2.5 border-t border-line">
        <ChatAttachmentChips
          v-if="attachments.length"
          class="mb-2"
          :attachments="attachments"
          @remove="removeAttachment"
        />
        <div class="relative">
          <TokenPalette
            :open="paletteOpen"
            :groups="paletteGroups"
            :active-key="paletteActive"
            :empty-text="mentionOpen ? '没有匹配的笔记/文件夹' : '没有匹配的命令，Enter 按普通消息发送'"
            :tip="mentionOpen ? '↑↓ 选择 · Enter 选中 · Esc 关闭' : '↑↓ 选择 · Enter 执行 · Esc 关闭'"
            @select="onPaletteSelectKey"
            @activate="paletteActive = $event"
          />
          <div
            v-if="paramHint"
            class="absolute bottom-full left-0 mb-2 w-full px-3 py-1.5 text-xs text-tx3
                   bg-surface ring-1 ring-line rounded-lg shadow-sm z-10 truncate"
          >
            {{ paramHint }}
          </div>
          <div class="flex gap-2 items-end">
            <el-input
              ref="inputRef"
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              :placeholder="placeholder"
              :disabled="streaming"
              @input="onComposerInput"
              @keydown="onComposerKeydown"
              @compositionstart="composing = true"
              @compositionend="onCompositionEnd"
            />
            <el-button type="primary" :loading="streaming" @click="submitComposer">发送</el-button>
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
