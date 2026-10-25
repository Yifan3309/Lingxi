<template>
  <div class="h-full overflow-y-auto">
    <div class="max-w-3xl mx-auto flex flex-col gap-5 pb-10">
      <div class="flex items-center gap-3">
        <h2 class="text-lg font-semibold text-tx1">回顾</h2>
        <el-radio-group v-model="range" size="small" @change="load(false)">
          <el-radio-button value="daily">今日</el-radio-button>
          <el-radio-button value="weekly">本周（7 天）</el-radio-button>
        </el-radio-group>
        <div class="flex-1"></div>
        <span v-if="loadedAt" class="text-xs text-tx3" :title="'每天每范围自动加载一次，之后手动刷新'">数据截至 {{ loadedAt }}</span>
        <el-button size="small" round :loading="loading" title="重新拉取统计与 AI 回顾（会调用一次模型）" @click="load(true)">刷新</el-button>
      </div>

      <template v-if="data">
        <!-- 统计卡片 -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
          <div class="rounded-xl bg-sidebar border border-line p-4">
            <div class="text-2xl font-semibold text-accent-strong">{{ data.count }}</div>
            <div class="text-xs text-tx3 mt-1">更新笔记</div>
          </div>
          <div class="rounded-xl bg-sidebar border border-line p-4">
            <div class="text-2xl font-semibold text-tx1">{{ Object.keys(data.tags || {}).length }}</div>
            <div class="text-xs text-tx3 mt-1">涉及标签</div>
          </div>
          <div class="rounded-xl bg-sidebar border border-line p-4">
            <div class="text-2xl font-semibold text-tx1">{{ Object.keys(data.folders || {}).length }}</div>
            <div class="text-xs text-tx3 mt-1">活跃文件夹</div>
          </div>
          <div class="rounded-xl bg-sidebar border border-line p-4">
            <div class="text-2xl font-semibold text-tx1">{{ topTag || '—' }}</div>
            <div class="text-xs text-tx3 mt-1">最热标签</div>
          </div>
        </div>

        <!-- AI 回顾叙述 -->
        <div v-if="data.summary" class="rounded-xl bg-sidebar border border-line p-5">
          <div class="flex items-center gap-2 mb-3">
            <el-icon class="text-accent-strong"><MagicStick /></el-icon>
            <span class="text-sm font-semibold ai-gradient-text">AI 回顾</span>
          </div>
          <div class="text-sm text-tx2 leading-relaxed whitespace-pre-wrap">{{ data.summary }}</div>
        </div>
        <el-alert v-else-if="!loading && data.count > 0" type="info" :closable="false" show-icon
                  title="AI 回顾暂时不可用（模型未配置或调用失败），先看统计吧" />

        <!-- 笔记列表 -->
        <div v-if="data.notes?.length" class="rounded-xl bg-sidebar border border-line p-2">
          <div
            v-for="n in data.notes"
            :key="n.id"
            class="hover-accent px-3 py-2.5 cursor-pointer rounded-md"
            @click="goNote(n.id)"
          >
            <div class="text-sm font-medium text-tx1 truncate">{{ n.title || '未命名' }}</div>
            <div class="text-xs text-tx3 mt-0.5 flex items-center gap-2">
              <span>{{ relTime(n.updatedAt) }}</span>
              <span v-if="n.folder">· {{ n.folder }}</span>
              <el-tag v-for="t in parseTags(n.tags).slice(0, 3)" :key="t" size="small" effect="plain" round>{{ t }}</el-tag>
            </div>
          </div>
        </div>
        <div v-else-if="!loading" class="flex flex-col items-center pt-16 gap-3">
          <div class="w-14 h-14 rounded-2xl bg-accent-light flex items-center justify-center text-2xl">🗓️</div>
          <div class="text-sm font-medium text-tx2">{{ range === 'daily' ? '今天还没有动过笔记' : '最近一周没有更新' }}</div>
          <div class="text-xs text-tx3">回顾范围按「最后更新时间」计算</div>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
// 模块级缓存（跨路由挂载/卸载保留，整个应用一份）：
// <script setup> 顶层是每实例作用域，缓存放那里切走就丢。
import { reactive } from 'vue'
const cache = reactive({})
</script>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { parseTags, relTime, useNotes } from '../composables/useNotes'

const router = useRouter()
const { select } = useNotes()

const range = ref('daily')
const loading = ref(false)
const loadedAt = ref('')

/* 每个范围一天只自动加载一次，之后进出页面/切换范围用缓存，
   想更新点「刷新」（LLM 叙述有成本，由用户决定何时花）。 */
function todayKey() {
  return new Date().toDateString()
}

const data = computed(() => cache[range.value]?.data ?? null)

const topTag = computed(() => {
  const entries = Object.entries(data.value?.tags || {})
  return entries.length ? entries[0][0] : ''
})

onMounted(() => load(false))

/** force=false：今天已自动加载过则直接用缓存；force=true：用户手动刷新。 */
async function load(force) {
  const hit = cache[range.value]
  if (!force && hit && hit.date === todayKey()) {
    loadedAt.value = hit.time
    return
  }
  loading.value = true
  try {
    const resp = await api.review(range.value)
    cache[range.value] = {
      data: resp,
      date: todayKey(),
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }
    loadedAt.value = cache[range.value].time
  } catch {
    cache[range.value] = { data: { count: 0, tags: {}, folders: {}, notes: [], summary: null }, date: todayKey(), time: '' }
  } finally {
    loading.value = false
  }
}

function goNote(id) {
  select(id).finally(() => router.push('/notes'))
}
</script>

<style scoped>
.ai-gradient-text {
  background: var(--ai-grad);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
</style>
