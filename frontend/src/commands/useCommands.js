import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChat } from '../composables/useChat'
import { useNotes } from '../composables/useNotes'
import { useTheme } from '../composables/useTheme'
import { useUi } from '../composables/useUi'
import { api } from '../api'
import { parseCommand } from './match'
import { commands } from './registry'

/** 埋点：每次执行记 { name, args, ok, ms }，先打 console，接得上再加 /api/admin/metrics */
function track(entry) {
  console.info('[命令]', entry)
}

/**
 * 命令执行器：把 ctx（router/notes/chat/theme/ui）注入命令并执行。
 * extra 由宿主组件补充对话抽屉内的交互入口（openHelp/openAttachment/showParamHint），
 * 命令层不关心宿主是谁。
 */
export function useCommands(extra = {}) {
  const router = useRouter()
  const ui = useUi()
  const ctx = {
    router,
    notes: useNotes(),
    chat: useChat(),
    theme: useTheme(),
    ui: { ...ui, ...extra },
    api,
    toast: (message, type = 'success') => ElMessage({ message, type, duration: 2000 }),
    confirm: (message, title) => ElMessageBox.confirm(message, title, { type: 'warning' }),
    /** 昂贵操作（/clip、/enrich）的显式 loading：duration 0 常驻，由命令自己 close */
    loading: (message) => ElMessage({ message, type: 'info', duration: 0 }),
    now: () => Date.now()
  }

  function byName(name) {
    return commands.find((c) => c.name === name)
  }

  /**
   * 对整段输入做命令裁决（不发请求、不弹窗，纯决策）：
   * - pass：不是命令或首词不在词表（词表闸门：未知命令不拦截，按普通消息发送）
   * - noarg-with-args：无参命令带了参数——宁可报错也不猜
   * - missing-arg / invalid-arg：缺参或校验不过——只提示、不发送
   * - ok：可以执行
   */
  function resolve(text) {
    const parsed = parseCommand(text)
    if (!parsed) {
      return { kind: 'pass' }
    }
    const cmd = byName(parsed.name)
    if (!cmd) {
      return { kind: 'pass' }
    }
    if (!cmd.arg) {
      if (parsed.args && parsed.args.trim()) {
        return { kind: 'noarg-with-args', cmd }
      }
      return { kind: 'ok', cmd, args: '' }
    }
    const args = (parsed.args || '').trim()
    if (!args) {
      if (cmd.arg.default) {
        return { kind: 'ok', cmd, args: cmd.arg.default }
      }
      return { kind: 'missing-arg', cmd, message: cmd.arg.onMissing || cmd.arg.hint || '缺少参数' }
    }
    const verdict = cmd.arg.validate ? cmd.arg.validate(args) : true
    if (verdict !== true) {
      return { kind: 'invalid-arg', cmd, message: verdict }
    }
    return { kind: 'ok', cmd, args }
  }

  /** 执行 + 计时 + 失败兜底 toast（失败带原因）。 */
  async function execute(cmd, args) {
    const t0 = ctx.now()
    let ok = true
    try {
      await cmd.run(ctx, args)
    } catch (err) {
      ok = false
      ctx.toast(`执行失败：${err?.message || err}`, 'error')
    } finally {
      track({ name: cmd.name, args: args || null, ok, ms: ctx.now() - t0 })
    }
  }

  return { ctx, commands, byName, resolve, execute }
}
