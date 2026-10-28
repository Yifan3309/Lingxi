<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
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

/*
 * 页面导航：页内不再放标题，改用一枚在按钮之间滑动的胶囊指示当前页。
 * 三个按钮统一 font-medium（字重一致宽度才稳定，胶囊滑动不会抖），只靠颜色区分选中。
 */
const route = useRoute()
const navItems = [
  { to: '/notes', label: '笔记' },
  { to: '/graph', label: '图谱' },
  { to: '/review', label: '回顾' }
]
const navRef = ref(null)
const navEls = reactive({})
const pill = reactive({ left: 0, width: 0, ready: false })

function setNavEl(to, el) {
  if (el) {
    navEls[to] = el
  }
}

/** 量取当前按钮的位置，换算成相对导航容器的坐标 */
function syncPill() {
  const host = navRef.value
  const el = navEls[route.path]
  if (!host || !el) {
    return
  }
  const hostRect = host.getBoundingClientRect()
  const btnRect = el.getBoundingClientRect()
  pill.left = btnRect.left - hostRect.left
  pill.width = btnRect.width
  pill.ready = true
}

watch(() => route.path, () => nextTick(syncPill))
window.addEventListener('resize', syncPill)
onBeforeUnmount(() => window.removeEventListener('resize', syncPill))

onMounted(async () => {
  await refresh()
  config.value = await api.config().catch(() => null)
  await nextTick()
  syncPill()
  // 字体晚到会改变按钮宽度，落地后补量一次
  document.fonts?.ready.then(syncPill)
})
</script>

<template>
  <el-container class="h-screen">
    <el-header height="56px" class="!px-5 flex items-center gap-4 bg-surface/80 backdrop-blur border-b border-line sticky top-0 z-20">
      <div class="flex items-center gap-2 select-none" title="灵犀 · 与你心有灵犀的笔记">
        <LingXiMark :size="26" />
        <span class="text-lg font-semibold text-tx1 tracking-wide">灵犀</span>
      </div>

      <!-- 页面导航：胶囊滑动指示当前页 -->
      <nav ref="navRef" class="relative ml-2 flex items-center gap-1">
        <span
          class="nav-pill"
          :class="{ 'is-ready': pill.ready }"
          :style="{ left: pill.left + 'px', width: pill.width + 'px' }"
          aria-hidden="true"
        ></span>
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          v-slot="{ isExactActive }"
          custom
        >
          <button
            :ref="(el) => setNavEl(item.to, el)"
            class="relative z-[1] rounded-full px-3.5 py-1.5 text-sm font-medium transition-colors duration-200"
            :class="isExactActive ? 'text-accent-strong' : 'text-tx2 hover:text-tx1'"
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
