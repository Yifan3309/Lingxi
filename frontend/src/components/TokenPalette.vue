<script setup>
import { nextTick, ref, watch } from 'vue'

/**
 * 通用候选浮层：@ 附件选择与 / 命令面板共用（doc/07 §4）。
 * 纯展示 + 鼠标交互：分组标题、hover 高亮、点击选中；
 * 键盘（↑↓/Enter/Esc）由宿主输入框统一处理——两种令牌的 Enter 语义不同，不收进这里。
 * groups: [{ label, items: [{ key, main, sub?, meta? }] }]，key 在全部 groups 内唯一。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  groups: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  emptyText: { type: String, default: '没有匹配项' },
  tip: { type: String, default: '↑↓ 选择 · Enter 执行 · Esc 关闭' },
  maxHeight: { type: String, default: '18rem' }
})
const emit = defineEmits(['select', 'activate'])

const bodyRef = ref(null)

/** 键盘移动高亮后把选中项滚进可视区 */
watch(
  () => props.activeKey,
  async (key) => {
    if (!key || !props.open) {
      return
    }
    await nextTick()
    bodyRef.value?.querySelector(`[data-key="${CSS.escape(key)}"]`)?.scrollIntoView({ block: 'nearest' })
  }
)
</script>

<template>
  <div
    v-if="open"
    ref="bodyRef"
    class="absolute bottom-full left-0 mb-2 w-full overflow-y-auto
           rounded-xl bg-surface ring-1 ring-line shadow-lg z-20 py-1"
    :style="{ maxHeight }"
  >
    <template v-for="g in groups" :key="g.label || 'plain'">
      <div v-if="g.label && g.items.length" class="px-3 pt-1.5 pb-1 text-[11px] tracking-wide text-tx3">
        {{ g.label }}
      </div>
      <div
        v-for="it in g.items"
        :key="it.key"
        :data-key="it.key"
        class="px-3 py-1.5 text-sm cursor-pointer"
        :class="it.key === activeKey ? 'bg-accent-light text-accent-strong' : 'text-tx1 hover-accent'"
        :title="it.sub || it.main"
        @mouseenter="emit('activate', it.key)"
        @click="emit('select', it.key)"
      >
        <span class="font-medium">{{ it.main }}</span>
        <span v-if="it.meta" class="ml-2 text-xs text-tx3">{{ it.meta }}</span>
        <div v-if="it.sub" class="text-xs text-tx3 mt-0.5 truncate">{{ it.sub }}</div>
      </div>
    </template>

    <div v-if="!groups.some((g) => g.items.length)" class="px-3 py-2 text-xs text-tx3">
      {{ emptyText }}
    </div>
    <div class="my-1 border-t border-line"></div>
    <div class="px-3 py-1 text-[11px] text-tx3">{{ tip }}</div>
  </div>
</template>
