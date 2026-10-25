import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import DOMPurify from 'dompurify'
import 'katex/dist/katex.min.css'

// KaTeX 扩展：支持 $...$ 行内与 $$...$$ 块级公式（throwOnError=false 公式错误时显示原文不炸渲染）
marked.use(markedKatex({ throwOnError: false }))

/** Markdown → 消毒后的 HTML（预览用）。 */
export function renderMarkdown(text) {
  const html = marked.parse(text || '')
  return DOMPurify.sanitize(html)
}
