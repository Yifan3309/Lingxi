import { nextTick, ref } from 'vue'
import { useUndoHistory } from '../src/composables/useUndoHistory.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let failed = 0

function check(label, actual, expected) {
  const ok = actual === expected
  if (!ok) failed++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}  期望=${JSON.stringify(expected)} 实际=${JSON.stringify(actual)}`)
}

/** 起一套环境：content 是正文，caret 是光标，scope 是笔记 id */
function setup(initial = '') {
  const content = ref(initial)
  const noteId = ref('n1')
  const caret = ref({ start: 0, end: 0 })
  const h = useUndoHistory({
    getContent: () => content.value,
    setContent: (t) => { content.value = t },
    getCaret: () => caret.value,
    setCaret: (start, end) => { caret.value = { start, end } },
    scopeKey: () => noteId.value
  })
  return { content, noteId, caret, h }
}

async function type(state, text, { caretAt = null } = {}) {
  state.content.value = text
  if (caretAt !== null) state.caret.value = { start: caretAt, end: caretAt }
  await nextTick()
}

/* ① 连续输入合并成一步：撤销一次应回到这段输入之前 */
{
  const s = setup('')
  await type(s, 'a')
  await type(s, 'ab')
  await type(s, 'abc')
  check('① 合并后撤销', s.h.undo() && (await nextTick(), s.content.value), '')
  check('① 重做回来', s.h.redo() && (await nextTick(), s.content.value), 'abc')
}

/* ② 间隔超过合并窗口：两次输入是两步 */
{
  const s = setup('')
  await type(s, 'a')
  await sleep(700)
  await type(s, 'ab')
  s.h.undo()
  await nextTick()
  check('② 撤销一步', s.content.value, 'a')
  s.h.undo()
  await nextTick()
  check('② 再撤销一步', s.content.value, '')
}

/* ③ 程序化改值（插图）也能撤销 */
{
  const s = setup('正文')
  await nextTick()
  await type(s, '正文\n![](img.png)\n')
  s.h.undo()
  await nextTick()
  check('③ 撤销插图', s.content.value, '正文')
}

/* ④ 换笔记：历史清空，撤销不会把上一篇正文写进来 */
{
  const s = setup('第一篇')
  await nextTick()
  await type(s, '第一篇改过')
  s.noteId.value = 'n2'
  await type(s, '第二篇')
  const undone = s.h.undo()
  await nextTick()
  check('④ 换笔记后无可撤销', undone, false)
  check('④ 当前笔记内容未被动', s.content.value, '第二篇')
}

/* ⑤ 撤销后新输入会清掉重做栈 */
{
  const s = setup('')
  await type(s, 'a')
  await sleep(700)
  await type(s, 'ab')
  s.h.undo()
  await nextTick()
  await sleep(700)
  await type(s, 'ax')
  check('⑤ 新输入后不能重做', s.h.redo(), false)
}

console.log(failed ? `\n失败 ${failed} 项` : '\n全部通过')
process.exit(failed ? 1 : 0)
