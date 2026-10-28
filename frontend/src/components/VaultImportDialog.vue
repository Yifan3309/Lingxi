<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useNotes } from '../composables/useNotes'

const open = defineModel({ type: Boolean, default: false })
const { refresh, select } = useNotes()

const pathInput = ref('')
const scanning = ref(false)
const scanReport = ref(null)
const importing = ref(false)
const progress = ref(null)
let pollTimer = null
let polling = false

/** 路径改动后旧预览立即失效，避免“预览 A、实际导入 B” */
watch(pathInput, () => {
  if (!importing.value) {
    scanReport.value = null
  }
})

/** 打开时接续进行中的迁移（上次会话遗留），关闭时停轮询 */
watch(open, async (v) => {
  clearInterval(pollTimer)
  pollTimer = null
  if (!v) {
    return
  }
  const st = await api.vaultImportStatus().catch(() => null)
  if (st?.running) {
    progress.value = st
    importing.value = true
    startPoll()
  }
})

onBeforeUnmount(() => clearInterval(pollTimer))

async function doScan() {
  if (!pathInput.value.trim() || scanning.value || importing.value) {
    return
  }
  scanning.value = true
  scanReport.value = null
  try {
    scanReport.value = await api.vaultScan(pathInput.value)
  } catch (e) {
    ElMessage.error(`扫描失败：${e?.message || e}`)
  } finally {
    scanning.value = false
  }
}

async function startImport() {
  if (!scanReport.value || importing.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将导入 ${scanReport.value.mdCount} 篇 md：相对目录映射为多层文件夹，图片自动落盘并重写链接。开始迁移？`,
      '迁移仓库',
      { confirmButtonText: '开始迁移', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  try {
    progress.value = await api.vaultImportStart(scanReport.value.root)
    importing.value = true
    startPoll()
  } catch (e) {
    ElMessage.error(`启动失败：${e?.message || e}`)
  }
}

function startPoll() {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    if (polling) {
      return
    }
    polling = true
    try {
      const st = await api.vaultImportStatus().catch(() => null)
      if (!st) {
        return
      }
      progress.value = st
      if (!st.running) {
        clearInterval(pollTimer)
        pollTimer = null
        importing.value = false
        const summary = `迁移完成：导入 ${st.imported} · 更新 ${st.updated} · 跳过 ${st.skipped} · 失败 ${st.failed}`
        if (st.failed || st.errors?.length) {
          ElMessage.warning(summary)
        } else {
          ElMessage.success(summary)
        }
        await refresh()
        if (st.importedNoteIds?.length) {
          await select(st.importedNoteIds[0])
        }
      }
    } finally {
      polling = false
    }
  }, 1500)
}

function pct() {
  const p = progress.value
  if (!p || !p.total) {
    return 0
  }
  return Math.min(100, Math.round((p.done / p.total) * 100))
}
</script>

<template>
  <el-dialog v-model="open" title="旧仓库一键迁移" width="560px" append-to-body>
    <div class="flex flex-col gap-3">
      <div class="flex gap-2">
        <el-input
          v-model="pathInput"
          placeholder="本机笔记文件夹路径，如 D:\我的笔记（全部 .md）"
          clearable
          :disabled="importing"
          @keyup.enter="doScan"
        />
        <el-button :loading="scanning" :disabled="importing" @click="doScan">扫描预览</el-button>
      </div>

      <!-- 扫描预览 -->
      <template v-if="scanReport">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            {{ scanReport.mdCount }} 篇 md · {{ scanReport.folderCount }} 个文件夹 · {{ scanReport.imageFileCount }} 个图片文件
            <template v-if="scanReport.excludedCount"> · 排除 {{ scanReport.excludedCount }} 个隐藏/排除目录</template>
          </template>
          <div class="text-xs leading-relaxed mt-1">
            根目录 md 归入「未归类」，子目录映射为多层文件夹；frontmatter 的 title/tags/folder 优先；
            相对图片与 ![[嵌入]] 自动落盘重写链接。迁移不跑 AI 摘要/标签（之后可在笔记里手动生成）。
          </div>
        </el-alert>
        <el-alert v-if="scanReport.truncated" type="warning" :closable="false" show-icon
                  title="md 篇数超过单次上限（5000），仅迁移前 5000 篇" />
        <div v-if="scanReport.topLevelFolders.length" class="flex flex-wrap gap-1.5">
          <el-tag v-for="f in scanReport.topLevelFolders.slice(0, 8)" :key="f" size="small" effect="plain">{{ f }}</el-tag>
          <el-tag v-if="scanReport.topLevelFolders.length > 8" size="small" type="info" effect="plain">
            +{{ scanReport.topLevelFolders.length - 8 }}
          </el-tag>
        </div>
      </template>

      <!-- 进行中 -->
      <template v-if="importing && progress">
        <el-progress :percentage="pct()" :stroke-width="10" />
        <div class="text-xs text-tx3 truncate">正在迁移：{{ progress.currentFile || '…' }}</div>
      </template>

      <!-- 完成报告 -->
      <template v-if="!importing && progress && progress.total > 0">
        <el-alert :type="progress.failed || progress.errors.length ? 'warning' : 'success'" :closable="false" show-icon>
          <template #title>
            导入 {{ progress.imported }} · 更新 {{ progress.updated }} · 跳过 {{ progress.skipped }} · 失败 {{ progress.failed }}
          </template>
          <div class="text-xs leading-relaxed mt-1">
            图片落盘 {{ progress.imagesCopied }} 张<template v-if="progress.imagesBroken">（链接缺失 {{ progress.imagesBroken }}）</template>
            <template v-if="progress.gbkConverted"> · GBK 转码 {{ progress.gbkConverted }} 篇</template>
            <template v-if="progress.truncated"> · 超长截断 {{ progress.truncated }} 篇</template>
          </div>
        </el-alert>
      </template>
      <el-alert v-else-if="!importing && progress && progress.total === 0" type="warning" :closable="false" show-icon
                title="没有找到可迁移的 md 文件" />
      <div v-if="!importing && progress?.errors?.length" class="text-xs text-tx3 max-h-32 overflow-y-auto leading-relaxed">
        <div v-for="(e, i) in progress.errors" :key="i" class="truncate" :title="e">· {{ e }}</div>
      </div>
    </div>

    <template #footer>
      <el-button @click="open = false">{{ importing ? '后台运行，关闭' : '关闭' }}</el-button>
      <el-button
        type="primary"
        :loading="importing"
        :disabled="!scanReport || scanReport.mdCount === 0"
        @click="startImport"
      >
        {{ importing ? `迁移中 ${progress?.done ?? 0}/${progress?.total ?? '?'}` : '开始迁移' }}
      </el-button>
    </template>
  </el-dialog>
</template>
