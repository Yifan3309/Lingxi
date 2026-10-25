<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved'])
const config = ref(null)
const saving = ref(false)

/* 表单：空 = 用该 provider 的配置文件默认值 */
const chatProvider = ref('openai-compatible')
const chatModel = ref('')
const chatBaseUrl = ref('')
const chatApiKey = ref('')
const visionModel = ref('')

/* 常用模型速选（按 provider 分组） */
const chatPresets = {
  'openai-compatible': ['glm-4-flash', 'glm-4-flashx', 'glm-4-air', 'glm-4.6', 'deepseek-chat'],
  ollama: ['qwen3:8b', 'qwen2.5:7b', 'llama3.1:8b', 'deepseek-r1:8b', 'mistral:7b']
}
const visionPresets = ['glm-4v-flash', 'glm-4v-plus', 'qwen-vl-plus']

const isOllama = computed(() => chatProvider.value === 'ollama')
const modelPlaceholder = computed(() =>
  isOllama.value ? '模型名，留空用配置默认（如 qwen3:8b）' : '模型名，留空用配置默认（如 glm-4-flash）')
const baseUrlPlaceholder = computed(() =>
  isOllama.value ? 'http://localhost:11434' : '留空用配置文件（换服务商时填，如 DeepSeek）')

/* 切 provider：清空具体字段，让新 provider 走自己的默认值（后端也会清掉旧覆盖防串扰） */
function onProviderChange() {
  chatModel.value = ''
  chatBaseUrl.value = ''
  chatApiKey.value = ''
}

watch(open, async (v) => {
  if (!v) {
    clearInterval(pollTimer)
    pollTimer = null
    return
  }
  config.value = await api.config().catch(() => null)
  if (config.value) {
    chatProvider.value = config.value.chat?.provider || 'openai-compatible'
    chatModel.value = config.value.chat?.model || ''
    chatBaseUrl.value = config.value.chat?.baseUrl || ''
    visionModel.value = config.value.vision?.model || ''
    chatApiKey.value = '' // 密钥脱敏不回填，留空=不修改
  }
  // 重嵌入任务进行中（上个会话遗留）则接上轮询
  const st = await api.reembedStatus().catch(() => null)
  if (st?.running) {
    reembeding.value = true
    startPoll()
  }
})

/* ---- 全量重嵌入（R1：更换嵌入模型后向量空间失效，需重建全部向量） ---- */
const reembeding = ref(false)
const reembedProgress = ref(null)
let pollTimer = null

async function startReembed() {
  try {
    await ElMessageBox.confirm(
      '将用当前嵌入模型重建全部笔记的向量（期间检索可能暂不完整，正常使用不受影响）。确定开始？',
      '重建向量',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    reembedProgress.value = await api.reembedStart()
    reembeding.value = true
    startPoll()
  } catch (e) {
    ElMessage.error(`启动失败：${e?.message || e}`)
  }
}

function startPoll() {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    const st = await api.reembedStatus().catch(() => null)
    if (!st) return
    reembedProgress.value = st
    if (!st.running) {
      clearInterval(pollTimer)
      pollTimer = null
      reembeding.value = false
      ElMessage.success(`重建完成：成功 ${st.done - st.failed} / 失败 ${st.failed} / 共 ${st.total}`)
    }
  }, 1500)
}

async function save() {
  saving.value = true
  try {
    const body = {
      chat: { provider: chatProvider.value },
      vision: { model: visionModel.value.trim() || undefined }
    }
    if (chatModel.value.trim()) {
      body.chat.model = chatModel.value.trim()
    }
    if (chatBaseUrl.value.trim() && chatBaseUrl.value.trim() !== config.value?.chat?.baseUrl) {
      body.chat.baseUrl = chatBaseUrl.value.trim()
    }
    if (!isOllama.value && chatApiKey.value.trim()) {
      body.chat.apiKey = chatApiKey.value.trim()
    }
    config.value = await api.updateConfig(body)
    ElMessage.success('已热切换，新对话即刻生效（无需重启）')
    emit('saved', config.value)
    open.value = false
  } catch (e) {
    ElMessage.error(`保存失败：${e?.message || e}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="模型配置（热切换）" width="560px">
    <div class="flex flex-col gap-4">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          修改后立即生效，无需重启
        </template>
      </el-alert>

      <div v-if="config">
        <div class="text-xs font-semibold text-tx2 mb-2">对话模型（chat 与流式共用）</div>
        <div class="flex flex-col gap-2.5">
          <el-radio-group v-model="chatProvider" @change="onProviderChange">
            <el-radio-button value="openai-compatible">云端 OpenAI 兼容</el-radio-button>
            <el-radio-button value="ollama">本地 Ollama</el-radio-button>
          </el-radio-group>
          <el-autocomplete
            v-model="chatModel"
            :fetch-suggestions="(q, cb) => cb((chatPresets[chatProvider] || []).filter(p => !q || p.includes(q)).map(p => ({ value: p })))"
            :placeholder="modelPlaceholder"
            clearable
          >
            <template #prepend>模型</template>
          </el-autocomplete>
          <el-input v-model="chatBaseUrl" :placeholder="baseUrlPlaceholder" clearable>
            <template #prepend>地址</template>
          </el-input>
          <el-input
            v-if="!isOllama"
            v-model="chatApiKey"
            type="password"
            show-password
            :placeholder="config.chat?.apiKey ? `当前 ${config.chat.apiKey}，留空不修改` : '留空用配置文件'"
            clearable
          >
            <template #prepend>密钥</template>
          </el-input>
          <div v-if="isOllama" class="text-xs text-tx3">本地 Ollama 无需密钥；需先装好 Ollama 并拉取模型（11434 端口）</div>
        </div>

        <div class="text-xs font-semibold text-tx2 mt-4 mb-2">视觉模型（图片/扫描 PDF 文字提取，始终走云端）</div>
        <el-autocomplete
          v-model="visionModel"
          :fetch-suggestions="(q, cb) => cb(visionPresets.filter(p => !q || p.includes(q)).map(p => ({ value: p })))"
          placeholder="如 glm-4v-flash（免费）"
        >
          <template #prepend>模型</template>
        </el-autocomplete>
        <div class="text-xs text-tx3 mt-2">
          当前 provider: {{ config.chat?.provider }}
          <span v-if="config.chat?.overridden || config.vision?.overridden" class="text-amber-500">（有运行时覆盖）</span>
        </div>

        <div class="text-xs font-semibold text-tx2 mt-4 mb-2">嵌入向量维护</div>
        <div class="flex items-center gap-3 rounded-lg bg-app px-3 py-2.5">
          <div class="text-xs text-tx2 flex-1">
            更换嵌入模型后向量空间失效，需重建全部向量（当前 embed: {{ config.embedding?.provider }})
          </div>
          <el-button size="small" type="warning" plain :loading="reembeding" @click="startReembed">
            {{ reembeding ? `重建中 ${reembedProgress?.done ?? 0}/${reembedProgress?.total ?? '?'}` : '重建向量' }}
          </el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" class="!active:scale-95 transition-transform" @click="save">
        保存并切换
      </el-button>
    </template>
  </el-dialog>
</template>
