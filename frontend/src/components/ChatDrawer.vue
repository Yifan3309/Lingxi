<script setup>
import { nextTick, ref, watch } from 'vue'
import { useChat } from '../composables/useChat'
import { useNotes } from '../composables/useNotes'
import { fmtScore } from '../api'
import LingXiMark from './LingXiMark.vue'

const { visible, sessionId, messages, streaming, loadHistory, newSession, ask } = useChat()
const { select } = useNotes()

const input = ref('')
const scrollBox = ref(null)

const suggestions = [
  '我们打算用什么数据库？',
  '我在学什么？总结一下',
  '帮我找找相关的笔记'
]

async function send(q) {
  const question = (q || input.value).trim()
  if (!question || streaming.value) {
    return
  }
  input.value = ''
  await ask(question)
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
            回答基于你的全部笔记生成，并附来源可跳转
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
          <div v-if="m.role === 'user'" class="max-w-[88%] px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap break-words text-white rounded-2xl rounded-br-md bg-accent">
            {{ m.content }}
          </div>
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

      <div class="flex gap-2 pt-2.5 border-t border-line">
        <el-input
          v-model="input"
          placeholder="例如：我们打算用什么数据库？"
          :disabled="streaming"
          @keyup.enter="send()"
        />
        <el-button type="primary" :loading="streaming" @click="send()">发送</el-button>
      </div>
    </div>
  </el-drawer>
</template>
