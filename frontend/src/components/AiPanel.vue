<script setup>
import { ref, watch } from 'vue'
import { api, fmtScore } from '../api'
import { useNotes, parseTags } from '../composables/useNotes'

const { current, currentId, adoptTag, select } = useNotes()
const related = ref([])

watch(currentId, async (id) => {
  related.value = []
  if (id) {
    related.value = await api.related(id).catch(() => [])
  }
})
</script>

<template>
  <div v-if="current && currentId" class="flex flex-col gap-3">
    <div v-if="current.summary" class="bg-surface rounded-xl shadow-sm ring-1 ring-line/70 p-4">
      <div class="flex items-center gap-1.5 mb-2">
        <el-icon class="text-violet-500"><MagicStick /></el-icon>
        <span class="text-xs font-semibold ai-grad-text tracking-wide">AI 摘要</span>
      </div>
      <div class="text-sm text-tx2 leading-relaxed">{{ current.summary }}</div>
    </div>

    <div v-if="parseTags(current.suggestedTags).length" class="bg-surface rounded-xl shadow-sm ring-1 ring-line/70 p-4">
      <div class="flex items-center gap-1.5 mb-2.5">
        <el-icon class="text-violet-500"><MagicStick /></el-icon>
        <span class="text-xs font-semibold ai-grad-text tracking-wide">建议标签（点击采纳）</span>
      </div>
      <div class="flex flex-wrap gap-2">
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
