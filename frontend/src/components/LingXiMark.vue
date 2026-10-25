<script setup>
import { computed } from 'vue'

/**
 * 灵犀标志：知识图谱风的三角节点（三点连线，顶点为"洞察"），
 * 应用主渐变填充；mono 模式用 currentColor（放在渐变底上时用白色）。
 */
const props = defineProps({
  size: { type: Number, default: 24 },
  mono: { type: Boolean, default: false }
})

// 每实例唯一 id，避免页面上多个实例的 <defs> 冲突
const uid = Math.random().toString(36).slice(2, 8)
const gradId = computed(() => `lx-grad-${uid}`)
const paint = computed(() => (props.mono ? 'currentColor' : `url(#${gradId.value})`))
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <defs v-if="!mono">
      <linearGradient :id="gradId" x1="0" y1="0" x2="24" y2="24" gradientUnits="userSpaceOnUse">
        <stop stop-color="#6366f1" />
        <stop offset="1" stop-color="#8b5cf6" />
      </linearGradient>
    </defs>
    <path
      d="M6 18 L12 6 L18 18 Z"
      :stroke="paint"
      stroke-width="1.6"
      stroke-linejoin="round"
      opacity="0.5"
    />
    <circle cx="6" cy="18" r="2.3" :fill="paint" />
    <circle cx="12" cy="6" r="2.9" :fill="paint" />
    <circle cx="18" cy="18" r="2.3" :fill="paint" />
  </svg>
</template>
