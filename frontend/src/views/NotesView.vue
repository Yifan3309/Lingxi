<template>
  <div class="h-full flex flex-col gap-4 lg:gap-6">
    <div class="flex items-center gap-3">
      <h2 class="text-lg font-semibold text-tx1">笔记</h2>
      <span class="text-xs text-tx3">捕捉 → 组织 → 连接 → 回顾 的工作台</span>
    </div>
    <el-container class="flex-1 min-h-0">
      <el-aside width="300px" class="!bg-sidebar border-r border-line overflow-hidden">
        <NoteList />
      </el-aside>
      <el-main class="!p-4 lg:!p-6 flex flex-col gap-4 overflow-y-auto">
        <NoteEditor />
        <AiPanel />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import NoteList from '../components/NoteList.vue'
import NoteEditor from '../components/NoteEditor.vue'
import AiPanel from '../components/AiPanel.vue'
</script>
