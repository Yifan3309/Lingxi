# 本地模型文件目录（ONNX）

M1 过渡方案：嵌入/重排在本地 ONNX 加载，不依赖外部服务；最终态切 Ollama。
模型二进制不进 git（.gitignore 已忽略 models/ 下的模型文件）。

## 目录约定

```
models/
├── embedding/   # 嵌入模型 ONNX 文件
└── reranker/    # 重排模型 ONNX 文件
```

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
