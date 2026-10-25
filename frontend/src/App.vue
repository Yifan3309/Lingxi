<script setup>
import { onMounted, ref } from 'vue'
import ChatDrawer from './components/ChatDrawer.vue'
import LingXiMark from './components/LingXiMark.vue'
import ModelConfigDialog from './components/ModelConfigDialog.vue'
import { useChat } from './composables/useChat'
import { useNotes } from './composables/useNotes'
import { api } from './api'

const { refresh } = useNotes()
const chat = useChat()
const config = ref(null)
const configOpen = ref(false)

/* 主题切换：偏好存 localStorage，index.html 内联脚本已做渲染前防闪 */
const theme = ref(localStorage.getItem('lx_theme') || 'light')

function toggleTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
  document.documentElement.classList.toggle('dark', theme.value === 'dark')
  localStorage.setItem('lx_theme', theme.value)
}

onMounted(async () => {
  await refresh()
  config.value = await api.config().catch(() => null)
})
</script>

<template>
  <el-container class="h-screen">
    <el-header height="56px" class="!px-5 flex items-center gap-4 bg-surface/80 backdrop-blur border-b border-line sticky top-0 z-20">
      <div class="flex items-center gap-2 select-none" title="灵犀 · 与你心有灵犀的笔记">
        <LingXiMark :size="26" />
        <span class="text-lg font-semibold text-tx1 tracking-wide">灵犀</span>
      </div>

      <!-- 页面导航 -->
      <nav class="flex items-center gap-1 ml-2">
        <router-link v-for="item in [
          { to: '/notes', label: '笔记' },
          { to: '/graph', label: '图谱' },
          { to: '/review', label: '回顾' }
        ]" :key="item.to" :to="item.to" v-slot="{ isExactActive }" custom>
          <button
            class="px-3.5 py-1.5 rounded-full text-sm transition-colors"
            :class="isExactActive ? 'bg-accent-light text-accent-strong font-medium' : 'text-tx2 hover:text-tx1 hover:bg-app'"
            @click="$router.push(item.to)"
          >
            {{ item.label }}
          </button>
        </router-link>
      </nav>

      <div
        v-if="config"
        class="hidden md:block text-xs text-tx2 bg-app rounded-full px-3 py-1 cursor-pointer hover:text-tx1 transition-colors"
        :title="`chat: ${config.chat.provider} · ${config.chat.model}（点击配置模型）`"
        @click="configOpen = true"
      >
        {{ config.chat.provider }} · {{ config.chat.model }}
        <span class="mx-1.5 text-tx3">|</span>
        embed: {{ config.embedding.provider }}
      </div>
      <div class="flex-1"></div>
      <el-button text circle class="!text-tx2" :title="theme === 'light' ? '切到暗色' : '切到亮色'" @click="toggleTheme">
        <el-icon size="18"><Moon v-if="theme === 'light'" /><Sunny v-else /></el-icon>
      </el-button>
      <!-- AI 入口用渐变语法（借鉴 nowen-note：AI 能力与主色区分） -->
      <button
        class="flex items-center gap-1.5 text-white text-sm rounded-full pl-4 pr-5 py-2 shadow-sm
               transition-transform active:scale-95 hover:opacity-90"
        :style="{ background: 'var(--ai-grad)' }"
        @click="chat.open()"
      >
        <el-icon><MagicStick /></el-icon>
        问笔记
      </button>
    </el-header>
    <main class="h-[calc(100vh-56px)] overflow-hidden">
      <router-view />
    </main>
    <ChatDrawer />
    <ModelConfigDialog v-model="configOpen" @saved="config = $event" />
  </el-container>
</template>
