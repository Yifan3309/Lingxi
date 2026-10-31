/**
 * 斜杠命令定义：纯数据 + run(ctx, args)。
 * ctx 由 useCommands 注入（router/notes/chat/theme/ui/toast…），命令不直接 import 全局状态，
 * 这样在任何页面（不只对话抽屉）都能复用。
 *
 * 两条铁律（doc/07 §1）：每条命令都有等价的手动路径；令牌在发送前剥掉，模型看不到命令本身。
 * 形状约定：有参就是有 arg（必填），没有 arg 就是无参，不存在「可选参数」两可状态；
 * 唯一例外是 arg.default——文档明确给了默认值的命令（/review）用缺省直执行。
 */
import { isHttpUrl, oneOf, required } from './match'

export const GROUP_ORDER = ['跳转', '笔记', '会话', 'AI']

export const commands = [
  /* ---- 跳转（本地，零副作用） ---- */
  {
    name: 'notes',
    aliases: ['b', '笔记'],
    group: '跳转',
    title: '工作台',
    close: true,
    run: (ctx) => ctx.router.push('/notes')
  },
  {
    name: 'graph',
    aliases: ['g', '图谱'],
    group: '跳转',
    title: '知识图谱',
    close: true,
    run: (ctx) => ctx.router.push('/graph')
  },
  {
    name: 'review',
    aliases: ['r', '回顾'],
    group: '跳转',
    title: '回顾',
    common: true,
    close: true,
    hint: '回顾最近更新：统计 + AI 三段式叙述',
    arg: {
      placeholder: 'daily|weekly',
      hint: '参数：daily 或 weekly（默认 daily）',
      validate: oneOf(['daily', 'weekly'], '范围'),
      default: 'daily'
    },
    run: (ctx, range) => ctx.router.push({ path: '/review', query: { range: (range || 'daily').toLowerCase() } })
  },
  {
    name: 'theme',
    aliases: ['主题'],
    group: '跳转',
    title: '主题切换',
    close: true,
    hint: '与顶栏月亮按钮同一逻辑',
    arg: {
      placeholder: 'light|dark',
      hint: '参数：light 或 dark',
      validate: oneOf(['light', 'dark'], '主题')
    },
    run: (ctx, mode) => ctx.theme.set((mode || '').trim().toLowerCase())
  },
  {
    name: 'model',
    aliases: ['模型'],
    group: '跳转',
    title: '模型设置',
    hint: '打开模型配置对话框（热切换，无需重启）',
    run: (ctx) => ctx.ui.openModelConfig()
  },
  {
    name: 'help',
    aliases: ['?', '命令'],
    group: '跳转',
    title: '命令帮助',
    common: true,
    hint: '固定展开全部命令，可上下滚动',
    run: (ctx) => ctx.ui.openHelp()
  },

  /* ---- 笔记动作 ---- */
  {
    name: 'note',
    aliases: ['n', '新建'],
    group: '笔记',
    title: '新建笔记',
    common: true,
    close: true,
    hint: '新建空白笔记并聚焦标题（标题在编辑器里填）',
    run: async (ctx) => {
      ctx.notes.newNote()
      await ctx.router.push('/notes')
      await ctx.ui.focusEditor()
    }
  },
  {
    name: 'search',
    aliases: ['s', '搜'],
    group: '笔记',
    title: '搜索笔记',
    common: true,
    close: true,
    hint: '左栏切到关键词搜索并刷新（等价于搜索框回车）',
    arg: { placeholder: '<关键词>', hint: '参数：关键词（取整段）', validate: required },
    run: async (ctx, kw) => {
      ctx.notes.searchMode.value = 'keyword'
      ctx.notes.searchText.value = kw.trim()
      await ctx.router.push('/notes')
      await ctx.notes.refresh()
    }
  },
  {
    name: 'tag',
    aliases: ['t'],
    group: '笔记',
    title: '标签搜索',
    common: true,
    close: true,
    hint: '左栏切到标签搜索',
    arg: { placeholder: '<标签>', hint: '参数：标签名（取整段）', validate: required },
    run: async (ctx, tag) => {
      ctx.notes.searchMode.value = 'tag'
      ctx.notes.searchText.value = tag.trim()
      await ctx.router.push('/notes')
      await ctx.notes.refresh()
    }
  },
  {
    name: 'enrich',
    aliases: ['富化'],
    group: '笔记',
    title: 'AI 富化',
    close: true,
    hint: '对当前笔记重新生成摘要与建议标签（LLM，约数十秒）',
    run: async (ctx) => {
      if (!ctx.notes.currentId.value) {
        ctx.toast('当前没有打开的笔记', 'warning')
        return
      }
      const loading = ctx.ui.loading('正在生成摘要与建议标签…')
      try {
        ctx.notes.current.value = await ctx.api.enrichNote(ctx.notes.currentId.value)
        ctx.toast('已完成富化')
      } finally {
        loading.close()
      }
    }
  },
  {
    name: 'import',
    aliases: ['导入'],
    group: '笔记',
    title: '导入文件',
    close: true,
    hint: '唤起文件选择（等价于左栏「导入」）',
    run: async (ctx) => {
      await ctx.router.push('/notes')
      ctx.ui.requestImport()
    }
  },
  {
    name: 'clip',
    aliases: ['剪藏', 'c'],
    group: '笔记',
    title: '网页剪藏',
    close: true,
    hint: '抓取网页正文，AI 清洗后存为笔记',
    arg: {
      placeholder: '<url>',
      hint: '参数：网页地址（http/https）',
      validate: isHttpUrl,
      onMissing: '需要一个网页地址，例如 /clip https://…'
    },
    run: async (ctx, url) => {
      const loading = ctx.ui.loading('正在剪藏网页（约 5-15 秒）…')
      try {
        const note = await ctx.api.clipUrl(url.trim())
        ctx.toast(`已剪藏《${note.title}》`)
        await ctx.notes.refresh()
        await ctx.notes.select(note.id)
      } finally {
        loading.close()
      }
    }
  },
  {
    name: 'vault',
    aliases: ['迁移'],
    group: '笔记',
    title: '旧仓库迁移',
    close: true,
    hint: '打开旧仓库迁移对话框',
    run: async (ctx) => {
      await ctx.router.push('/notes')
      ctx.ui.requestVault()
    }
  },

  /* ---- 会话控制 ---- */
  {
    name: 'clear',
    aliases: ['new', '新会话'],
    group: '会话',
    title: '新会话',
    common: true,
    hint: '清空进程内记忆（与「新会话」按钮同义）',
    run: (ctx) => ctx.chat.newSession()
  },
  {
    name: 'attach',
    aliases: [],
    group: '会话',
    title: '添加附件',
    hint: '打开对应附件入口（等价于敲 @ 后再选类目）',
    arg: {
      placeholder: 'note|folder|file|upload',
      hint: '参数：note / folder / file / upload',
      validate: oneOf(['note', 'folder', 'file', 'upload'], '附件类型')
    },
    run: (ctx, kind) => ctx.ui.openAttachment((kind || '').trim().toLowerCase())
  },

  /* ---- AI 透传（唯一进 prompt 的命令；其余命令的令牌都在发送前剥掉） ---- */
  {
    name: 'ask',
    aliases: [],
    group: 'AI',
    title: '直接提问',
    hint: '剥掉 /ask 令牌，其余文本作为问题直接发送',
    arg: { placeholder: '<问题>', hint: '参数：要问的问题（取整段）', validate: required },
    run: (ctx, q) => ctx.chat.ask(q.trim())
  },
  {
    name: 'summarize',
    aliases: [],
    group: 'AI',
    title: '总结笔记',
    hint: '以当前笔记为附件，要求模型分段总结（走附件通道，30k 预算照旧）',
    run: (ctx) => {
      const cur = ctx.notes.current.value
      if (!cur || !ctx.notes.currentId.value) {
        ctx.toast('当前没有打开的笔记', 'warning')
        return
      }
      const question = `请分段总结当前笔记《${cur.title || '未命名'}》：先用 3-5 条要点概括，再分段展开关键内容。`
      return ctx.chat.ask(question, [{ type: 'note', id: ctx.notes.currentId.value }])
    }
  }
]
