<script setup>
/** @附件 chip 列表：输入框上方可删除；消息气泡里只读展示。 */
const props = defineProps({
  attachments: { type: Array, default: () => [] },
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['remove'])

const TYPE_LABEL = { note: '笔记', folder: '文件夹', localFile: '本地', upload: '文件' }
</script>

<template>
  <div v-if="props.attachments.length" class="flex flex-wrap gap-1.5">
    <span
      v-for="(a, i) in props.attachments"
      :key="i"
      class="inline-flex items-center gap-1 max-w-[200px] px-2 py-0.5 rounded-full text-xs
             bg-accent-light text-accent-strong ring-1 ring-accent/30"
      :title="a.path || a.name || ''"
    >
      <span class="opacity-60 shrink-0">{{ TYPE_LABEL[a.type] || a.type }}</span>
      <span class="truncate">{{ a.name || a.path || '' }}</span>
      <span v-if="a.status === 'uploading'" class="opacity-60 shrink-0">解析中…</span>
      <span v-else-if="a.status === 'error'" class="text-red-500 shrink-0" :title="a.error || ''">失败</span>
      <span v-else-if="a.chars" class="opacity-50 shrink-0">{{ (a.chars / 1000).toFixed(1) }}k</span>
      <button
        v-if="!props.readonly && a.status !== 'uploading'"
        type="button"
        class="shrink-0 opacity-60 hover:text-red-500 hover:opacity-100"
        title="移除"
        @click="emit('remove', i)"
      >×</button>
    </span>
  </div>
</template>
