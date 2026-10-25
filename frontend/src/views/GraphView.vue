<template>
  <div class="h-full flex flex-col gap-4 overflow-hidden">
    <div class="flex items-center gap-3">
      <h2 class="text-lg font-semibold text-tx1">知识图谱</h2>
      <span class="text-xs text-tx3">共标签为结构边，语义近邻（向量相似 ≥ 0.60）为虚线边；点笔记节点直达</span>
      <div class="flex-1"></div>
      <el-button size="small" round :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="error" class="flex-1 flex items-center justify-center">
      <el-result icon="warning" title="图谱加载失败" :sub-text="error">
        <template #extra>
          <el-button type="primary" round @click="load">重试</el-button>
        </template>
      </el-result>
    </div>
    <div v-else-if="!loading && empty" class="flex-1 flex items-center justify-center">
      <div class="flex flex-col items-center gap-3 text-center">
        <div class="w-14 h-14 rounded-2xl bg-accent-light flex items-center justify-center text-2xl">🕸️</div>
        <div class="text-sm font-medium text-tx2">图谱还没有内容</div>
        <div class="text-xs text-tx3 max-w-[280px] leading-relaxed">
          写下几篇笔记（给同类主题打上相同标签，效果更好），图谱会自动把它们连起来
        </div>
        <el-button type="primary" round @click="goNotes">去写笔记</el-button>
      </div>
    </div>
    <div v-show="!error && !empty" ref="chartEl" v-loading="loading" class="flex-1 min-h-0 rounded-xl bg-sidebar border border-line"></div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useNotes } from '../composables/useNotes'

const router = useRouter()
const { select } = useNotes()

const chartEl = ref(null)
const loading = ref(true)
const error = ref(null)
const empty = ref(false)
let chart = null

onMounted(load)

onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const data = await api.graph()
    empty.value = !data.nodes?.length
    if (!empty.value) {
      await render(data)
    }
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

async function render({ nodes, edges }) {
  // ECharts 只在图谱页 chunk 里加载（router 懒加载 + 按需引入）；use() 注册缺一不可，
  // 否则 setOption 报 "series type graph not exists"
  const [{ init, use }, { GraphChart }, { CanvasRenderer }] = await Promise.all([
    import('echarts/core'),
    import('echarts/charts'),
    import('echarts/renderers')
  ])
  use([GraphChart, CanvasRenderer])
  if (!chart) {
    chart = init(chartEl.value)
    window.addEventListener('resize', resize)
  }
  const folders = [...new Set(nodes.filter((n) => n.type === 'note').map((n) => n.folder || '未归类'))]
  const palette = ['#6366f1', '#06b6d4', '#10b8a6', '#f59e0b', '#ec4899', '#8b5cf6', '#14b8a6', '#f43f5e']
  // canvas 不解析 CSS 变量：按当前主题取标签色
  const labelColor = document.documentElement.classList.contains('dark') ? '#cbd5e1' : '#334155'
  chart.setOption(
    {
      tooltip: {
        formatter: (p) =>
          p.dataType === 'edge'
            ? `${p.data.sourceName} ↔ ${p.data.targetName}<br/>${p.data.edgeType === 'semantic' ? '语义相关 ' : '共同标签 '}${(p.data.weight * 100).toFixed(0)}%`
            : `${p.name}${p.data.folder ? `<br/><span style="opacity:.6">${p.data.folder}</span>` : ''}`
      },
      legend: [{ data: folders, top: 8, textStyle: { color: '#94a3b8' } }],
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          force: { repulsion: 220, edgeLength: [60, 140], gravity: 0.08 },
          categories: folders.map((f) => ({ name: f })),
          data: nodes.map((n) => ({
            id: n.id,
            name: n.label,
            folder: n.folder,
            symbolSize: n.size,
            category: Math.max(0, folders.indexOf(n.folder || '未归类')),
            itemStyle: n.type === 'tag'
              ? { color: 'transparent', borderColor: '#94a3b8', borderWidth: 1.5, borderType: 'dashed' }
              : undefined,
            label: { show: true, color: labelColor, fontSize: n.type === 'tag' ? 10 : 11 }
          })),
          links: edges.map((e) => ({
            source: e.source,
            target: e.target,
            edgeType: e.type,
            weight: e.weight,
            sourceName: nodeName(nodes, e.source),
            targetName: nodeName(nodes, e.target),
            lineStyle:
              e.type === 'semantic'
                ? { width: 1 + e.weight, type: 'dashed', color: '#818cf8', opacity: 0.5, curveness: 0.15 }
                : { width: 1.2, color: '#475569', opacity: 0.6 }
          })),
          emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
        }
      ]
    },
    true
  )
  chart.off('click')
  chart.on('click', (p) => {
    if (p.dataType === 'node' && p.data?.id && !p.data.id.startsWith('tag:')) {
      goNote(p.data.id)
    }
  })
}

function nodeName(nodes, id) {
  return nodes.find((n) => n.id === id)?.label || id
}

function resize() {
  chart?.resize()
}

function goNote(id) {
  select(id).finally(() => router.push('/notes'))
}

function goNotes() {
  router.push('/notes')
}
</script>
