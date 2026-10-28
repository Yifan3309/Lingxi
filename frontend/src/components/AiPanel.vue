<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtScore } from '../api'
import { useNotes, parseTags } from '../composables/useNotes'

const { current, currentId, adoptTag, select } = useNotes()
const related = ref([])
const enriching = ref(false)

watch(currentId, async (id) => {
  related.value = []
  if (id) {
    related.value = await api.related(id).catch(() => [])
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
  <div v-if="current && currentId" class="flex flex-col gap-3">
    <div class="bg-surface rounded-xl shadow-sm ring-1 ring-line/70 p-4">
      <div class="flex items-center gap-1.5 mb-2">
        <el-icon class="text-violet-500"><MagicStick /></el-icon>
        <span class="text-xs font-semibold ai-grad-text tracking-wide">AI 摘要与建议标签</span>
        <el-button
          size="small"
          text
          type="primary"
          class="!ml-auto"
          :loading="enriching"
          @click="onEnrich"
        >
          {{ current.summary || parseTags(current.suggestedTags).length ? '刷新' : '生成' }}
        </el-button>
      </div>
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
        <div class="text-xs text-tx3 mt-2">建议标签点击采纳（并入 tags，AI 建议你决策）</div>
      </template>
      <div v-else class="text-xs text-tx3 leading-relaxed">
        尚未生成。点「生成」让 AI 提炼一句话摘要并建议 2-5 个标签——只在你需要时调用。
      </div>
    </div>

    <div v-if="related.length" class="bg-surface rounded-xl shadow-sm ring-1 ring-line/70 p-4">
      <div class="text-xs font-semibold text-tx2 mb-1.5 tracking-wide">🔗 相关笔记</div>
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
</template>
