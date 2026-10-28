<template>
  <div class="h-full flex flex-col gap-4 overflow-hidden">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
      <el-tooltip placement="bottom-start" :show-after="200">
        <template #content>
          <div>{{ view.noteCount }} 篇笔记 · {{ view.tagCount }} 标签 · {{ view.edgeCount }} 关联</div>
          <div class="mt-1 opacity-80">实线 = 标签归属，虚线 = 语义相似</div>
        </template>
        <el-icon class="cursor-help text-tx3 transition-colors hover:text-tx1" :size="15"><InfoFilled /></el-icon>
      </el-tooltip>
      <div class="flex-1"></div>
      <el-checkbox
        v-model="showSingletonTags"
        size="small"
        :disabled="empty || !lastData"
        @change="rerender"
      >
        显示单例标签<template v-if="view.hiddenTags">（{{ view.hiddenTags }}）</template>
      </el-checkbox>
      <el-button size="small" round :disabled="empty || !lastData" @click="resetView">重置视图</el-button>
      <el-button size="small" round :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="!error && !empty && !loading && lastData && view.edgeCount === 0"
      type="info"
      :closable="false"
      show-icon
      title="暂无关联边"
      description="给同类笔记打上相同标签，或等待语义索引建立后，这里会把笔记连起来。"
    />

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
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useNotes } from '../composables/useNotes'

const router = useRouter()
const { select } = useNotes()

const chartEl = ref(null)
const loading = ref(true)
const error = ref(null)
const empty = ref(false)
const lastData = ref(null)
const showSingletonTags = ref(false)
let chart = null
let themeObserver = null

/** 可见视图：单例标签（只挂 1 篇笔记的标签）默认隐藏，连同其边一起过滤 */
const view = computed(() => buildView(lastData.value, showSingletonTags.value))

function buildView(data, showSingleton) {
  const none = { nodes: [], edges: [], noteCount: 0, tagCount: 0, edgeCount: 0, hiddenTags: 0 }
  if (!data?.nodes?.length) {
    return none
  }
  const degree = new Map()
  for (const e of data.edges) {
    degree.set(e.source, (degree.get(e.source) || 0) + 1)
    degree.set(e.target, (degree.get(e.target) || 0) + 1)
  }
  const hiddenIds = new Set()
  if (!showSingleton) {
    for (const n of data.nodes) {
      if (n.type === 'tag' && (degree.get(n.id) || 0) < 2) {
        hiddenIds.add(n.id)
      }
    }
  }
  const nodes = data.nodes.filter((n) => !hiddenIds.has(n.id))
  const edges = data.edges.filter((e) => !hiddenIds.has(e.source) && !hiddenIds.has(e.target))
  return {
    nodes,
    edges,
    noteCount: nodes.filter((n) => n.type === 'note').length,
    tagCount: nodes.filter((n) => n.type === 'tag').length,
    edgeCount: edges.length,
    hiddenTags: hiddenIds.size
  }
}

onMounted(() => {
  load()
  // canvas 不解析 CSS 变量：主题 class 变化后重绘，标签/边颜色才跟着换
  themeObserver = new MutationObserver(() => rerender())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})

onBeforeUnmount(() => {
  themeObserver?.disconnect()
  themeObserver = null
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const data = await api.graph()
    lastData.value = data
    empty.value = !data.nodes?.some((n) => n.type === 'note')
    if (!empty.value) {
      await nextTick()
      await render()
    }
  } catch (e) {
    lastData.value = null
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

async function rerender() {
  if (empty.value || error.value || !lastData.value) {
    return
  }
  await nextTick()
  await render()
}

/** 重置：清掉拖拽/缩放/力导状态后按当前视图重排 */
async function resetView() {
  if (empty.value || error.value || !lastData.value) {
    return
  }
  chart?.clear()
  await rerender()
}

async function render() {
  const { nodes, edges } = view.value
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
  const tagCategory = folders.length
  // canvas 不解析 CSS 变量：按当前主题取色
  const dark = document.documentElement.classList.contains('dark')
  const labelColor = dark ? '#cbd5e1' : '#334155'
  const lineColor = dark ? '#94a3b8' : '#64748b'
  const nameById = new Map(nodes.map((n) => [n.id, n.label]))
  chart.setOption(
    {
      tooltip: {
        formatter: (p) =>
          p.dataType === 'edge'
            ? `${p.data.sourceName} ↔ ${p.data.targetName}<br/>${p.data.kindLabel}`
            : `${p.name}${p.data.folder ? `<br/><span style="opacity:.6">${p.data.folder}</span>` : ''}`
      },
      legend: [
        {
          data: folders,
          type: 'scroll',
          top: 8,
          textStyle: { color: lineColor },
          formatter: (name) => (name.length > 12 ? name.slice(0, 12) + '…' : name)
        }
      ],
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          force: { repulsion: 220, edgeLength: [60, 140], gravity: 0.08 },
          categories: [...folders.map((f) => ({ name: f })), { name: '标签' }],
          labelLayout: { hideOverlap: true },
          data: nodes.map((n) => ({
            id: n.id,
            name: n.label,
            folder: n.folder,
            symbolSize: n.size,
            category: n.type === 'tag' ? tagCategory : Math.max(0, folders.indexOf(n.folder || '未归类')),
            itemStyle:
              n.type === 'tag'
                ? { color: 'transparent', borderColor: lineColor, borderWidth: 1.5, borderType: 'dashed' }
                : undefined,
            label: {
              show: true,
              color: labelColor,
              fontSize: n.type === 'tag' ? 10 : 11,
              width: n.type === 'tag' ? 90 : 120,
              overflow: 'truncate'
            }
          })),
          links: edges.map((e) => ({
            source: e.source,
            target: e.target,
            edgeType: e.type,
            weight: e.weight,
            sourceName: nameById.get(e.source) || e.source,
            targetName: nameById.get(e.target) || e.target,
            kindLabel:
              e.type === 'semantic'
                ? `相似度 ${(e.weight * 100).toFixed(1)}%`
                : `标签：${e.source.startsWith('tag:') ? nameById.get(e.source) : nameById.get(e.target)}`,
            lineStyle:
              e.type === 'semantic'
                ? { width: 1 + e.weight, type: 'dashed', color: '#818cf8', opacity: 0.5, curveness: 0.15 }
                : { width: 1.2, color: lineColor, opacity: 0.6 }
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
