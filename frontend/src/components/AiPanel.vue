<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtScore } from '../api'
import { useNotes, parseTags } from '../composables/useNotes'

const { current, currentId, adoptTag, select } = useNotes()
const related = ref([])
const enriching = ref(false)
/** 默认收起：AI 内容按需展开，编辑区保持干净 */
const open = ref(false)
/** 已为哪篇笔记拉过相关推荐：避免收起状态下也发请求，也避免重复拉 */
let relatedFor = null

async function loadRelated(id) {
  relatedFor = id
  related.value = await api.related(id).catch(() => [])
}

watch(currentId, (id) => {
  related.value = []
  relatedFor = null
  if (open.value && id) {
    loadRelated(id)
  }
})

/** 首次展开时才去取相关笔记（多数会话根本不展开，这一发请求可以省掉） */
watch(open, (expanded) => {
  const id = currentId.value
  if (expanded && id && relatedFor !== id) {
    loadRelated(id)
  }
})

/** 显式 AI 富化（标签建议+摘要）：LLM 调用时机由用户决定。 */
async function onEnrich() {
  if (!currentId.value || enriching.value) {
    return
  }
  enriching.value = true
  try {
    current.value = await api.enrichNote(currentId.value)
  } catch (e) {
    ElMessage.error(`AI 富化失败：${e?.message || e}`)
  } finally {
    enriching.value = false
  }
}
</script>

<template>
  <div v-if="current && currentId" class="w-full">
    <!-- 收起态只有一行：箭头 + 标签 + 刷新；点箭头/标签展开摘要、建议标签与相关笔记 -->
    <div class="flex items-center gap-1.5 py-2">
      <el-icon
        class="cursor-pointer text-tx3 transition-transform duration-150 hover:text-tx1"
        :class="open ? 'rotate-90' : ''"
        @click="open = !open"
      >
        <CaretRight />
      </el-icon>
      <el-tooltip
        content="点标签即并入 tags；AI 只给建议，采纳与否由你决定"
        placement="top-start"
        :show-after="250"
      >
        <span class="flex cursor-pointer items-center gap-1.5" @click="open = !open">
          <el-icon class="text-violet-500"><MagicStick /></el-icon>
          <span class="text-xs font-semibold ai-grad-text tracking-wide">AI 摘要与建议标签</span>
        </span>
      </el-tooltip>
      <el-tooltip
        :content="current.summary || parseTags(current.suggestedTags).length ? '重新生成摘要与标签' : '生成摘要与建议标签'"
        placement="top"
        :show-after="250"
      >
        <el-button class="!ml-auto" text circle :loading="enriching" @click="onEnrich">
          <el-icon :size="15"><Refresh /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <div v-show="open" class="flex flex-col gap-5 pb-6">
      <div>
      <template v-if="current.summary || parseTags(current.suggestedTags).length">
        <div v-if="current.summary" class="text-sm text-tx2 leading-relaxed mb-2.5">{{ current.summary }}</div>
        <div v-if="parseTags(current.suggestedTags).length" class="flex flex-wrap gap-2">
          <el-tag
            v-for="t in parseTags(current.suggestedTags)"
            :key="t"
            class="cursor-pointer !active:scale-95 transition-transform"
            effect="light"
            round
            @click="adoptTag(t)"
          >
            {{ t }} <span class="ml-0.5 font-bold">+</span>
          </el-tag>
        </div>
      </template>
      <div v-else class="text-xs text-tx3 leading-relaxed">
        尚未生成。点右上角刷新图标让 AI 提炼一句话摘要并建议 2-5 个标签——只在你需要时调用。
      </div>
      </div>

      <div v-if="related.length" class="border-t border-line/70 pt-4">
        <div class="mb-1.5 text-xs font-semibold tracking-wide text-tx2">相关笔记</div>
        <div
          v-for="r in related"
          :key="r.noteId"
          class="hover-accent px-2 py-1.5 cursor-pointer text-sm text-tx1"
          @click="select(r.noteId)"
        >
          <span class="text-tx3 text-xs float-right">{{ fmtScore(r.score) }}</span>
          <span class="truncate">{{ r.title || '未命名' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
