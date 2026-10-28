<template>
  <div class="h-full flex flex-col gap-4 lg:gap-6">
    <div class="flex items-center gap-3">
      <!-- 窄屏：侧栏改成抽屉式浮层，用这个按钮唤起 -->
      <button
        class="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-line text-tx2 transition-colors hover:text-tx1 md:hidden"
        aria-label="打开笔记列表"
        @click="asideOpen = true"
      >
        <el-icon :size="16"><Menu /></el-icon>
      </button>
      <h2 class="text-lg font-semibold text-tx1">笔记</h2>
      <span class="hidden text-xs text-tx3 sm:inline">捕捉 → 组织 → 连接 → 回顾 的工作台</span>
    </div>

    <el-container class="relative min-h-0 flex-1">
      <div
        v-if="asideOpen"
        class="fixed inset-x-0 bottom-0 top-14 z-20 bg-black/20 md:hidden"
        @click="asideOpen = false"
      ></div>

      <el-aside
        width="300px"
        class="!bg-sidebar overflow-hidden border-r border-line transition-transform duration-200
               max-md:fixed max-md:bottom-0 max-md:left-0 max-md:top-14 max-md:z-30
               md:static md:translate-x-0"
        :class="asideOpen ? 'max-md:translate-x-0' : 'max-md:-translate-x-full'"
      >
        <NoteList />
      </el-aside>

      <el-main class="!p-4 flex flex-col gap-4 overflow-y-auto lg:!p-6">
        <NoteEditor />
        <AiPanel />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import NoteList from '../components/NoteList.vue'
import NoteEditor from '../components/NoteEditor.vue'
import AiPanel from '../components/AiPanel.vue'
import { useNotes } from '../composables/useNotes'

const { notes, currentId, refresh } = useNotes()
/** 窄屏侧栏（<md 才生效）：选中笔记后自动收起 */
const asideOpen = ref(false)
watch(currentId, () => {
  asideOpen.value = false
})

/** 首屏兜底：App 挂载时的加载可能早于后端就绪，列表为空就自己再拉一次 */
onMounted(() => {
  if (!notes.value.length) {
    refresh().catch(() => {})
  }
})
</script>
