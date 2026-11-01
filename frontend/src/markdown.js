import MarkdownIt from 'markdown-it'
import texmath from 'markdown-it-texmath'
import taskLists from 'markdown-it-task-lists'
import katex from 'katex'
import DOMPurify from 'dompurify'
import 'katex/dist/katex.min.css'

// markdown-it（VSCode 同款解析器）：插件以 md.use() 组合，整套引擎的替换成本收敛在本文件。
// html:true 沿用旧 marked 行为（允许内联 HTML），安全由 DOMPurify 统一消毒兜底；
// linkify:true 对齐 marked 的 GFM 自动链接。
const md = new MarkdownIt({ html: true, linkify: true })
  // KaTeX：$...$ 行内与 $$...$$ 块级（throwOnError=false 公式错误时显示原文不炸渲染）。
  // texmath 的行内规则不依赖空白边界，修复了 marked-katex-extension 的
  // 「$ 紧贴汉字/全角标点时不渲染」缺陷（如 `和$\sigma$`）。
  .use(texmath, { engine: katex, delimiters: 'dollars', katexOptions: { throwOnError: false } })
  // GFM 任务列表（- [ ] / - [x]）：marked 默认支持而 markdown-it 需要插件
  .use(taskLists)

/** Markdown → 消毒后的 HTML。笔记预览与聊天流共用；流式期间每个 chunk 重渲染累积全文（KB 级亚毫秒）。 */
export function renderMarkdown(text) {
  const html = md.render(text || '')
  return DOMPurify.sanitize(html)
}

/** 标题转锚点：保留中日韩与字母数字，其余折叠成连字符。 */
function slugify(text) {
  return (
    String(text || '')
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section'
  )
}

/**
 * 预览容器增强（在 v-html 之后的真实 DOM 上做，与具体 markdown 引擎解耦）：
 * ① 给 h1–h3 补稳定 id，并收集成目录条目；② 把每个 <pre> 包成带「语言条 + 复制按钮」的代码块。
 * 返回目录条目 [{ id, text, level }]，供右侧目录使用。
 */
export function decoratePreview(root) {
  const toc = []
  if (!root) {
    return toc
  }

  const used = new Map()
  root.querySelectorAll('h1, h2, h3').forEach((heading) => {
    const text = (heading.textContent || '').trim()
    if (!text) {
      return
    }
    let id = heading.id || slugify(text)
    const seen = used.get(id) || 0
    used.set(id, seen + 1)
    if (seen) {
      id = `${id}-${seen + 1}`
    }
    heading.id = id
    toc.push({ id, text, level: Number(heading.tagName.slice(1)) })
  })

  root.querySelectorAll('pre').forEach((pre) => {
    // 幂等：已包装过就跳过（v-html 重渲染时不会重复套壳）
    if (pre.closest('.code-block')) {
      return
    }
    const code = pre.querySelector('code')
    const match = (code && code.className || '').match(/language-([\w+#-]+)/)
    const lang = match ? match[1] : 'code'

    const wrap = document.createElement('div')
    wrap.className = 'code-block'
    const bar = document.createElement('div')
    bar.className = 'code-block__bar'
    const langEl = document.createElement('span')
    langEl.className = 'code-block__lang'
    langEl.textContent = lang
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'code-block__copy'
    copy.dataset.copy = ''
    copy.textContent = '复制'
    bar.append(langEl, copy)

    pre.replaceWith(wrap)
    wrap.append(bar, pre)
  })

  return toc
}
