import { nextTick, ref } from 'vue'

/**
 * 跨组件 UI 状态域（模块级单例）。
 * 命令要打开的对话框/动作分散在不同组件里（App 的模型设置、NoteList 的导入/迁移、
 * NoteEditor 的标题输入），命令层不该直接摸这些组件——用「请求计数」通知属主响应。
 * focusEditor 先等一帧再发 tick：/note 刚置好 current，编辑器 v-if 挂载后才能接到请求。
 */
const modelConfigOpen = ref(false)
const importTick = ref(0)
const vaultTick = ref(0)
const editorFocusTick = ref(0)

export function useUi() {
  return {
    modelConfigOpen,
    importTick,
    vaultTick,
    editorFocusTick,
    openModelConfig: () => {
      modelConfigOpen.value = true
    },
    requestImport: () => {
      importTick.value++
    },
    requestVault: () => {
      vaultTick.value++
    },
    focusEditor: async () => {
      await nextTick()
      editorFocusTick.value++
    }
  }
}
