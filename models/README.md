# 本地模型文件目录（ONNX）

M1 过渡方案：嵌入/重排在本地 ONNX 加载，不依赖外部服务。重排默认由 `reranker-service/` HTTP sidecar（@8900，GPU）提供，本目录 ONNX 为 sidecar 与 JVM CPU 回退共用；嵌入最终态可切 Ollama。
模型二进制不进 git（.gitignore 已忽略 models/ 下的模型文件）。

## 目录约定

```
models/
├── embedding/   # 嵌入模型 ONNX 文件（当前 Multilingual-E5-small）
└── reranker/    # 重排模型 ONNX 文件（当前 onnx-community/bge-reranker-v2-m3-ONNX 的 int8 导出）
```

## ⚠️ 重排模型导出的坑（2026-10-25 实录）

reranker 的 ONNX 必须输出**相关性 logit `[N, 1]`**。曾下载的
`er6ybge-reranker-v2-m3_dynamic_int8_onnx` 输出的是 hidden states
`[N, seq, 1024]`（分类头没导出）——**加载不报错，但等于用噪声排序**。
识别方法：用 ONNX Runtime 查输出 shape；应用侧已有启动自检兜底
（相关/无关文本试打分，方向不对启动失败）。
可靠来源：`onnx-community/bge-reranker-v2-m3-ONNX`（HF 官方 onnx-community
组织，取 `model_int8.onnx` + `tokenizer.json`）。

## ⚠️ 先读：Qwen3 系列 ONNX 的兼容性问题

Qwen3-Embedding / Qwen3-Reranker（0.6B 等）是 **decoder LLM 架构**（Qwen2 底座，
last-token pooling），而 LangChain4j 的 ONNX 模块
（`langchain4j-embeddings-*` / `onnx-scoring`）只支持 **BERT 编码器架构**
（BGE 系列即此）。把 Qwen3 的 ONNX 文件放进来直接加载会失败。

因此二选一：

- **方案 A（推荐，代码即插即用）**：本地 ONNX 用 BGE 系，最终切 Ollama 时再上 Qwen3。
  - 嵌入：`langchain4j-embeddings-bge-small-zh-v15` 依赖内置 BGE-small-zh 模型，
    **无需手动下载文件**，加依赖即可（中文效果好，量化后约 24MB）。
  - 重排：BAAI/bge-reranker-v2-m3 的 ONNX 导出（BERT 架构 cross-encoder），
    配合 `onnx-scoring` 模块。
- **方案 B（坚持 Qwen3）**：跳过本地 ONNX，直接用 Ollama 起服务
  （`ollama pull qwen3-embedding` / `ollama pull qwen3-reranker`），
  本目录留空；或自定义 ONNX 推理代码（工作量大，不建议 M1 做）。

## 若采用方案 A 的重排：放这些文件

下载 BAAI/bge-reranker-v2-m3 的 ONNX 导出（HuggingFace 搜
`bge-reranker-v2-m3 onnx`，取 optimum 导出版），放进 `reranker/`：

- `model.onnx`（或量化的 `model_optimized.onnx`）
- `tokenizer.json`
- `config.json`

## 若仍想手动放嵌入模型（方案 A 变体）

- `embedding/model.onnx`
- `embedding/tokenizer.json`

来源：BAAI/bge-small-zh-v1.5 的 ONNX 导出。

## 相关配置（代码接入时生效）

- `app.rag.store-path` 向量库文件位置
- `app.ai.embedding.provider` 切 `onnx`（本地）或 `ollama`（最终态）
