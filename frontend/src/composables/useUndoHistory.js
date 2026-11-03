import { nextTick, watch } from 'vue'

/**
 * 编辑器的撤销 / 重做。
 *
 * <p>不依赖 textarea 的原生历史：原生历史在「切到预览再回来」时会被清空（textarea 被 v-if 重建），
 * 程序化改值（插图、AI 回填）也会冲掉它——而这两类恰恰是最该能撤销的操作。
 *
 * <p>快照带光标位置，撤销时一起回去；连续输入在 {@code mergeMs} 窗口内算同一步，
 * 免得一次撤销只退一个字。
 *
 * @param {object} opts
 * @param {() => string} opts.getContent 读当前正文
 * @param {(text: string) => void} opts.setContent 写回正文
 * @param {() => {start: number, end: number}} opts.getCaret 读光标 / 选区
 * @param {(start: number, end: number) => void} opts.setCaret 恢复光标
 * @param {() => any} [opts.scopeKey] 作用域键（如笔记 id）：变了就清空历史，
 *        否则撤销会把上一篇的内容写进这一篇
 * @param {number} [opts.mergeMs] 连续输入的合并窗口（毫秒）
 * @param {number} [opts.limit] 历史栈上限
 */
export function useUndoHistory(opts) {
  const {
    getContent,
    setContent,
    getCaret,
    setCaret,
    scopeKey = () => null,
    mergeMs = 600,
    limit = 200
  } = opts

  let past = []
  let future = []
  let lastEditAt = 0
  // 初始快照：没有它，第一次改动就没有"改之前"的状态，撤销会直接返回 false
  let lastState = snapshot()
  let lastScope = scopeKey()
  let applying = false

  function snapshot() {
    const caret = getCaret()
    return { content: getContent(), start: caret.start, end: caret.end }
  }

  /** 清空历史（换笔记时用） */
  function reset() {
    past = []
    future = []
    lastState = snapshot()
    lastEditAt = 0
  }

  watch(getContent, () => {
    // 自己撤销 / 重做造成的改动不再入栈，否则撤销一次会又产生一条历史
    if (applying) {
      return
    }
    // 换笔记：上一篇的内容不能进新一篇的撤销栈
    if (scopeKey() !== lastScope) {
      lastScope = scopeKey()
      reset()
      return
    }
    const now = Date.now()
    if (now - lastEditAt >= mergeMs && lastState) {
      past.push(lastState)
      if (past.length > limit) {
        past.shift()
      }
    }
    future = []
    lastState = snapshot()
    lastEditAt = now
  })

  /** 恢复到某个快照：内容与光标一起回去 */
  function apply(state) {
    applying = true
    setContent(state.content)
    nextTick(() => {
      const max = state.content.length
      setCaret(Math.min(state.start, max), Math.min(state.end, max))
      lastState = {
        content: state.content,
        start: Math.min(state.start, max),
        end: Math.min(state.end, max)
      }
      lastEditAt = 0
      applying = false
    })
  }

  function undo() {
    if (!past.length) {
      return false
    }
    future.push(snapshot())
    apply(past.pop())
    return true
  }

  function redo() {
    if (!future.length) {
      return false
    }
    past.push(snapshot())
    apply(future.pop())
    return true
  }

  return {
    undo,
    redo,
    reset,
    canUndo: () => past.length > 0,
    canRedo: () => future.length > 0
  }
}
