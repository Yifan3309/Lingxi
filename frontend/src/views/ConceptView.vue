<template>
  <div class="concept-view h-full flex gap-5 overflow-hidden p-4 lg:p-6">
    <div v-if="!data" class="flex-1 flex items-center justify-center text-sm text-tx3">
      {{ error ? `概念层加载失败：${error}` : '加载中…' }}
    </div>
    <template v-else>
    <!-- 左：主题。不设边界线，靠间距与选中底色区分层级 -->
    <aside class="w-[190px] shrink-0 flex flex-col overflow-y-auto pr-1">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="flex items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors"
        :class="activeKey === item.key ? 'bg-accent-light text-accent-strong font-medium' : 'text-tx2 hover:bg-hover hover:text-tx1'"
        @click="pick(item.key)"
      >
        <span class="flex-1 truncate">{{ item.label }}</span>
        <span class="text-[11px] tabular-nums" :class="activeKey === item.key ? 'text-accent-strong/70' : 'text-tx3'">
          {{ item.count }}
        </span>
      </button>

      <div class="mt-3 mb-1 px-3 text-[11px] tracking-wide text-tx3">未归属主题</div>
      <button
        class="flex items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors"
        :class="activeKey === LOOSE ? 'bg-accent-light text-accent-strong font-medium' : 'text-tx2 hover:bg-hover hover:text-tx1'"
        @click="pick(LOOSE)"
      >
        <span class="flex-1 truncate">未分类</span>
        <span class="text-[11px] tabular-nums" :class="activeKey === LOOSE ? 'text-accent-strong/70' : 'text-tx3'">
          {{ data.meta.uncategorized }}
        </span>
      </button>
    </aside>

    <!-- 中：概念。卡片无边框，hover 才出现底色 -->
    <section class="flex-1 min-w-0 flex flex-col gap-3 overflow-hidden">
      <header class="flex flex-wrap items-center gap-3">
        <h2 class="text-[15px] font-medium text-tx1">{{ heading }}</h2>
        <span class="text-xs text-tx3">{{ sub }}</span>
        <div class="flex-1"></div>
        <el-input v-model="q" size="small" placeholder="筛选概念" clearable style="width: 168px">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </header>

      <div class="flex-1 min-h-0 overflow-y-auto">
        <div class="grid gap-1.5" style="grid-template-columns: repeat(auto-fill, minmax(198px, 1fr))">
          <button
            v-for="c in shown"
            :key="c.name"
            class="group rounded-lg px-3 py-2.5 text-left transition-colors"
            :class="selected === c.name ? 'bg-accent-light' : 'hover:bg-hover'"
            @click="open(c.name)"
          >
            <div class="flex items-center gap-1.5">
              <span class="truncate text-[13.5px] font-medium text-tx1">{{ c.name }}</span>
              <span
                v-if="c.topics.length > 1"
                class="shrink-0 rounded px-1 text-[10px] text-accent-strong ring-1 ring-accent/30"
                :title="`同属 ${c.topics.length} 个主题`"
              >{{ c.topics.length }}</span>
              <span v-else-if="!c.desc" class="shrink-0 text-[10px] text-tx3">仅名字</span>
            </div>
            <p class="mt-1 line-clamp-2 text-[11.5px] leading-[1.5]" :class="c.desc ? 'text-tx2' : 'text-tx3'">
              {{ c.desc || '—' }}
            </p>
          </button>
        </div>
        <!-- 空态分三种：还没抽过 / 这个主题空 / 筛选没命中。混成一句话会让人不知道下一步做什么 -->
        <div v-if="!data.concepts.length" class="py-24 flex flex-col items-center gap-3">
          <div class="text-sm text-tx2">概念层还没有内容</div>
          <div class="text-xs text-tx3 max-w-[300px] text-center leading-relaxed">
            抽取在每次保存后自动排队；已存在的笔记需要回填一次
          </div>
          <el-button size="small" round :loading="backfilling" @click="backfillAll">
            抽取全部笔记
          </el-button>
        </div>
        <div v-else-if="!shown.length" class="py-20 text-center text-sm text-tx3">
          {{ q ? '没有匹配的概念' : '这个主题下还没有概念' }}
        </div>
      </div>
    </section>

    <!-- 右：概念详情。窄屏隐藏，避免挤压中栏 -->
    <aside class="w-[280px] shrink-0 overflow-y-auto border-l border-line pl-5 hidden xl:block">
      <template v-if="current">
        <div class="text-[11px] tracking-wide text-tx3">概念</div>
        <h3 class="mt-1 text-lg font-medium leading-snug text-tx1">{{ current.name }}</h3>

        <p v-if="current.desc" class="mt-3 text-[13px] leading-[1.75] text-tx2">{{ current.desc }}</p>
        <p v-else class="mt-3 text-[13px] text-tx3">这篇笔记里只有名字，没有解释。</p>

        <div class="mt-5 text-[11px] tracking-wide text-tx3">来源</div>
        <button
          class="hover-accent mt-1 block w-full px-2 py-1.5 text-left"
          @click="goNote(current.noteId)"
        >
          <div class="truncate text-[13px] text-tx1">{{ current.noteTitle }}</div>
          <div class="mt-0.5 text-[11px] text-tx3">
            {{ current.folder || '未归类' }} · {{ current.date }}
          </div>
        </button>

        <div class="mt-5 text-[11px] tracking-wide text-tx3">主题</div>
        <div class="mt-1 flex flex-wrap gap-1.5">
          <span
            v-for="id in current.topics"
            :key="id"
            class="rounded-full bg-accent-light px-2 py-0.5 text-[11px] text-accent-strong"
          >{{ topicName(id) }}</span>
          <span v-if="!current.topics.length" class="text-[12px] text-tx3">未归属主题</span>
        </div>

        <template v-if="siblings.length">
          <div class="mt-5 text-[11px] tracking-wide text-tx3">同主题</div>
          <div class="mt-1">
            <button
              v-for="s in siblings"
              :key="s.name"
              class="block w-full truncate rounded px-2 py-1 text-left text-[12.5px] text-tx2 transition-colors hover:bg-hover hover:text-tx1"
              @click="open(s.name)"
            >{{ s.name }}</button>
          </div>
        </template>
      </template>

      <div v-else class="pt-16 text-center text-[12.5px] leading-relaxed text-tx3">
        选一个概念，看它在原文里怎么写的
      </div>
    </aside>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useNotes } from '../composables/useNotes'

/*
 * seed 原型数据由 tools/export_concepts.py 从本地笔记导出，含真实笔记内容，
 * 不进版本库（.gitignore 掉了 frontend/src/data/）。所以用 import.meta.glob
 * 而不是静态 import：本机有这个文件就打包作回退，新克隆环境没有它也能构建。
 */
const EMPTY_LAYER = {
  meta: { notes: 0, concepts: 0, explained: 0, topics: 0, uncategorized: 0 },
  topics: [],
  concepts: [],
  uncategorized: []
}
const seedModules = import.meta.glob('../data/conceptLayer.json', { eager: true, import: 'default' })
const seed = Object.values(seedModules)[0] || EMPTY_LAYER

const router = useRouter()
const { select } = useNotes()

const ALL = '__all__'
const LOOSE = '__loose__'

const data = ref(null)
const error = ref(null)
const activeKey = ref(ALL)
const selected = ref(null)
const q = ref('')
const backfilling = ref(false)

const topics = computed(() => data.value?.topics || [])
// 主题 id 统一按 String 比较：后端是字符串，seed 原型数据是数字，
// 严格相等混用会让主题筛选永远为空（点任何主题都显示"还没有概念"）
const topicIndex = computed(() => new Map(topics.value.map((t) => [String(t.id), t])))

const navItems = computed(() => [
  { key: ALL, label: '全部概念', count: data.value?.concepts.length || 0 },
  ...topics.value.map((t) => ({ key: String(t.id), label: t.name, count: t.concepts.length }))
])

const pool = computed(() => {
  const all = data.value?.concepts || []
  if (activeKey.value === ALL) {
    return all
  }
  if (activeKey.value === LOOSE) {
    return all.filter((c) => !c.topics.length)
  }
  return all.filter((c) => c.topics.some((id) => String(id) === activeKey.value))
})

const shown = computed(() => {
  const key = q.value.trim().toLowerCase()
  return key ? pool.value.filter((c) => c.name.toLowerCase().includes(key)) : pool.value
})

const heading = computed(() => {
  if (activeKey.value === ALL) return '全部概念'
  if (activeKey.value === LOOSE) return '未分类'
  return topicIndex.value.get(activeKey.value)?.name
})

const sub = computed(() => {
  const meta = data.value?.meta
  if (!meta) return ''
  if (activeKey.value === ALL) {
    return `${meta.concepts} 个 · ${meta.explained} 个有解释 · ${meta.uncategorized} 个未归属主题`
  }
  if (activeKey.value === LOOSE) {
    const n = pool.value.length
    return `${n} 个 · ${pool.value.filter((c) => c.desc).length} 个有解释 · 没有足够证据归入任何主题`
  }
  const t = topicIndex.value.get(activeKey.value)
  return t ? `${t.concepts.length} 个概念 · ${Math.round((t.depth || 0) * 100)}% 有解释` : ''
})

const current = computed(() => (data.value?.concepts || []).find((c) => c.name === selected.value) || null)

/* 同主题只取主归属下的兄弟，全部列出来会把别的主题的概念也拉进来 */
const siblings = computed(() => {
  const c = current.value
  if (!c || !c.topics.length) {
    return []
  }
  const id = c.primary || c.topics[0]
  return (data.value?.concepts || []).filter((x) => x.name !== c.name && x.topics.includes(id))
})

function topicName(id) {
  return topicIndex.value.get(String(id))?.name || '未分类'
}

function pick(key) {
  activeKey.value = key
  q.value = ''
}

function open(name) {
  selected.value = name
}

async function goNote(id) {
  if (!id) return
  try {
    await select(id)
    router.push('/notes')
  } catch {
    /* 笔记可能已删除，保持当前页即可 */
  }
}

/**
 * 取数据：优先走后端概念层接口。
 * 接口不存在（后端还没构建这版）时退回打包的原型数据——这样页面不会白屏，
 * 但控制台会留一条 warn，避免把原型数据当成真实结果。
 */
async function load() {
  try {
    data.value = await api.concepts()
    error.value = null
  } catch (e) {
    console.warn('[concept] 概念层接口不可用，退回打包的原型数据', e)
    data.value = seed
    error.value = null
  }
}

/** 新库第一次使用：把没抽过的笔记全排进队列，等队列清空再刷新 */
async function backfillAll() {
  backfilling.value = true
  try {
    await api.conceptBackfill(false)
    for (let i = 0; i < 60; i += 1) {
      await new Promise((r) => setTimeout(r, 2000))
      const st = await api.conceptStatus().catch(() => null)
      if (st && st.pending === 0 && st.queued === 0) break
    }
    await load()
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    backfilling.value = false
  }
}

onMounted(load)
</script>

<style>
/*
 * 本项目关闭了 Tailwind preflight（避免重置 Element Plus 的原生样式），
 * 代价是 <button> 会带着浏览器默认的 1px 边框和 buttonface 底色。
 * 只在本页范围内放回一条基础重置，且明确写进 base 层——
 * utilities 层优先级更高，hover:bg-hover 这类工具类仍然生效。
 */
@layer base {
  .concept-view button {
    border: 0;
    background: transparent;
  }
}
</style>
