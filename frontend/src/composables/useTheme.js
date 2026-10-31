import { ref } from 'vue'

/** 主题状态域（模块级单例）：顶栏按钮与 /theme 命令共用同一套逻辑。 */
const theme = ref(localStorage.getItem('lx_theme') || 'light')

export function useTheme() {
  /** 显式设置 light/dark：写 localStorage + 根元素 dark class（index.html 内联脚本已做渲染前防闪） */
  function set(mode) {
    if (mode !== 'light' && mode !== 'dark') {
      return
    }
    theme.value = mode
    document.documentElement.classList.toggle('dark', mode === 'dark')
    localStorage.setItem('lx_theme', mode)
  }

  function toggle() {
    set(theme.value === 'light' ? 'dark' : 'light')
  }

  return { theme, set, toggle }
}
