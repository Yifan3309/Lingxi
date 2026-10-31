/**
 * 命令匹配纯函数：解析（index 0 锚定）、候选打分、拼音辅助、参数校验。
 * 不依赖 Vue 与任何全局状态，可直接在浏览器控制台或未来的 Vitest 里验。
 */

/** 整段输入从 0 开始匹配；name 之后的第一个空格是唯一切分点，其余全是参数原文 */
const CMD_RE = /^\/([a-zA-Z]+)(?:[ \t]+([\s\S]*))?$/

/**
 * 解析命令令牌。只认「第 0 个字符是 / 且首词是纯字母」的输入；
 * 返回 { name, args }（args 为空格后的整段原文，可能为 undefined），
 * 不匹配返回 null——调用方应把它当普通消息发送（词表闸门见 resolve）。
 */
export function parseCommand(text) {
  const m = CMD_RE.exec(text || '')
  if (!m) {
    return null
  }
  return { name: m[1].toLowerCase(), args: m[2] }
}

/** 是否处于「/ 前缀、首词内尚未出现空格」的面板阶段（含单独一个 /） */
export function isSlashBrowsing(text) {
  return /^\/[a-zA-Z]*$/.test(text || '')
}

/* ---- 拼音辅助：只需覆盖命令标题/别名出现的汉字，查不到的字不参与匹配 ---- */
const PINYIN = {
  工: 'gong', 作: 'zuo', 台: 'tai', 知: 'zhi', 识: 'shi', 图: 'tu', 谱: 'pu',
  回: 'hui', 顾: 'gu', 主: 'zhu', 题: 'ti', 模: 'mo', 型: 'xing', 设: 'she',
  置: 'zhi', 命: 'ming', 令: 'ling', 帮: 'bang', 助: 'zhu', 新: 'xin',
  建: 'jian', 笔: 'bi', 记: 'ji', 搜: 'sou', 索: 'suo', 标: 'biao', 签: 'qian',
  富: 'fu', 化: 'hua', 导: 'dao', 入: 'ru', 文: 'wen', 件: 'jian', 网: 'wang',
  页: 'ye', 剪: 'jian', 藏: 'cang', 旧: 'jiu', 仓: 'cang', 库: 'ku', 迁: 'qian',
  移: 'yi', 会: 'hui', 话: 'hua', 添: 'tian', 加: 'jia', 直: 'zhi', 接: 'jie',
  提: 'ti', 问: 'wen', 总: 'zong', 结: 'jie', 切: 'qie', 换: 'huan'
}

function syllablesOf(s) {
  return [...(s || '')].map((ch) => PINYIN[ch]).filter(Boolean)
}

/** 全拼与首字母两种形态，如「网页剪藏」→ ['wangyejiancang', 'wjc'] */
export function pinyinVariants(s) {
  const sy = syllablesOf(s)
  return [sy.join(''), sy.map((x) => x[0]).join('')]
}

/**
 * 候选打分：精确 100 > 前缀 80 > 包含 60 > 全拼前缀 45 > 首字母 40 > 全拼包含 35。
 * q 需已 lowercase；覆盖 name / 别名 / 标题及其拼音形态（验收要求 jiancang 也能命中 /clip）。
 */
export function scoreOf(cmd, q) {
  if (!q) {
    return 0
  }
  const names = [cmd.name, ...(cmd.aliases || []), cmd.title].map((s) => (s || '').toLowerCase())
  if (names.some((n) => n === q)) return 100
  if (names.some((n) => n.startsWith(q))) return 80
  if (names.some((n) => n.includes(q))) return 60
  let best = 0
  for (const s of [cmd.title, ...(cmd.aliases || [])]) {
    const [full, initials] = pinyinVariants(s)
    if (full.startsWith(q)) best = Math.max(best, 45)
    if (initials && initials.startsWith(q)) best = Math.max(best, 40)
    if (full.includes(q)) best = Math.max(best, 35)
  }
  return best
}

/**
 * 按打分过滤候选，分高在前、同分保持注册顺序。
 * q 为空：默认只给常用 6 条；expandAll=true 给全量（/help 的固定展开）。
 */
export function filterCommands(commands, q, { expandAll = false } = {}) {
  const query = (q || '').trim().toLowerCase()
  if (!query) {
    return expandAll ? [...commands] : commands.filter((c) => c.common)
  }
  return commands
    .map((c) => ({ cmd: c, score: scoreOf(c, query) }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.cmd)
}

/* ---- 参数校验：返回 true 表示通过，返回字符串作为错误提示 ---- */

export function required(v) {
  return (v || '').trim() ? true : '参数不能为空'
}

/** URL 只允许 http/https（沿用剪藏的现有边界，本地路径/其它协议一律拒绝） */
export function isHttpUrl(v) {
  try {
    const u = new URL((v || '').trim())
    if (u.protocol === 'http:' || u.protocol === 'https:') {
      return true
    }
  } catch {
    // 非法 URL
  }
  return '参数需为 http/https 网页地址，例如 /clip https://…'
}

/** 枚举参数：light|dark、daily|weekly 这类；比较前 trim + lowercase */
export function oneOf(options, label) {
  return (v) => {
    const s = (v || '').trim().toLowerCase()
    return options.includes(s) ? true : `${label || '参数'}需为：${options.join(' / ')}`
  }
}
