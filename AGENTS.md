<API_TASKS>
Before responding to any non-trivial API engineering task — designing, implementing, mocking, testing, monitoring, documenting, or deploying an API or Postman Flow — read `postman/skills/api-engineer/SKILL.md` and follow it. It is the default entry point for API work in this repository. Pure questions and trivial one-line edits do not need it.
</API_TASKS>

# Lingxi (灵犀) — agent guide

AI-native note app: Spring Boot + LangChain4j RAG backend, Vue 3 SPA, optional Tauri desktop shell, Python reranker sidecar. Comments, docs, commit messages, and user-facing strings are Chinese; keep them Chinese.

## Layout
- `src/main/java/org/example/` — backend entrypoint `Application.java`; `web/` REST/SSE, `rag/` ingest/retrieval/graph, `config/` model wiring, `domain/` + `repository/` H2/JPA.
- `frontend/` — Vue 3 + Vite + Element Plus source. `npm run build` emits into `src/main/resources/static/` (gitignored; never hand-edit).
- `src-tauri/` — desktop shell. Serves `loading/`, spawns `target/lingxi.jar`, then navigates to `http://127.0.0.1:<port>` (8080, or a free 8081–8099 when 8080 is taken).
- `reranker-service/` — FastAPI sidecar on :8900, default reranker provider. Java falls back to in-process ONNX CPU if unreachable.
- `postman/` — the real integration test suite. `doc/` (gitignored locally) holds design/decision history.

## Commands (run from repo root)
- Full build: `npm ci; npm run build` in `frontend/`, then `mvn package` -> `target/lingxi.jar`.
- Backend dev: `mvn spring-boot:run` or `java -jar target/lingxi.jar` (port 8080). No Maven wrapper; system `mvn` required.
- Unit tests: `mvn test` (offline, seconds; no server/DB/models needed).
- Frontend dev: `npm run dev` in `frontend/` (port 5173 proxies `/api` and `/actuator` to 8080).
- Reranker: `.\reranker-service\setup.bat` once, then `.\reranker-service\start.bat`.
- Acceptance tests (backend must be running on 8080):
  `postman collection run "postman/collections/second-brain api" -e "postman/environments/Local.environment.yaml"`
- Desktop: build `target/lingxi.jar` first (`mvn package`), then build the shell **from inside `src-tauri/`** (tauri-cli finds `tauri.conf.json` downward from the CWD). The shell resolves its home as `LINGXI_HOME` > dev project root > install dir, and must never connect to a server it did not spawn (an installed build that finds 8080 taken switches to a free port instead of reusing someone else's backend). Full installer flow: `doc/09-安装包构建流程.md` (doc/ is local-only, not in the repo).

## Gotchas
- Unit tests live in `src/test/java` (JUnit 5 + AssertJ + Mockito, ~85 cases, `mvn test` in seconds). They are deliberately **complementary to Postman, not a duplicate**: unit tests own pure logic edges (chunking, folder-path normalization, attachment budgets), degradation paths (reranker sidecar down, LLM 401, ingest failure), and safety boundaries (loopback-only local files). Postman owns the HTTP contract and end-to-end flows. Don't re-assert HTTP-level behavior in unit tests. Tests must stay offline — no Spring context, no H2, no `models/`, no LLM key. Some Postman requests call a real LLM and need a valid key.
- Relative paths (`data/`, `models/`) resolve against the process CWD, so always launch from the repo root.
- `GET /api/notes` returns a `NoteSummary` projection (id/title/tags/folder/source/updatedAt) — **no `content`**. The body only comes from `GET /api/notes/{id}`. Keep list endpoints free of the 65k `content` column; list/picker only needs display fields.
- `NoteIngestService.ingest` writes the DB (delete + insert + `flush()`) **before** touching the vector store, then calls `EmbeddingStoreService.replaceAllByNoteId` (one persist per note). Don't split it back into `removeAllByNoteId` + `addAll` — that doubles whole-store serialization and leaves a half-written state on crash.
- `models/embedding/` and `models/reranker/` are gitignored and absent on fresh clones; the app fails at startup without them. See `models/README.md` for expected files. Since the installer ships no model files, the missing-embedding-model case must stay a **clear, actionable error** (see `AiModelConfig#embeddingModel`), while a missing **reranker** model only degrades to "no reranking" (`OnnxRerankerModelPresent` condition) — never a startup crash.
- Chat defaults to OpenAI-compatible. `application.yml` currently resolves the key from `${LLM_API_KEY_demo_glm_4_7_flash}` (the comment saying `LLM_API_KEY` is stale). UI model overrides persist to `data/runtime-config.json` and take precedence over yml; delete that file to revert.
- H2 file DB is `data/notes.mv.db` (`AUTO_SERVER=TRUE`, `ddl-auto=update`). Entity type changes can require deleting the DB to resync.
- Vectors are persisted separately in `data/embeddings.json`. After an embedding-model change, run `POST /api/admin/reembed` to rebuild them.
- GPU reranking only works via the Python sidecar: loading the CUDA provider inside the JVM fails on Windows. With the default `reranker-provider: http`, a missing sidecar is silently tolerated (WARN + CPU fallback). The JVM therefore depends on the **CPU** `onnxruntime` artifact (the GPU one is 460MB of dead weight: 683MB → 215MB jar).
- Chat models are built **only** via `AiModelConfig.chatModelOf` / `streamingModelOf` (startup beans, `AdminController.rebuildChat` hot swap, and the `/config/test` ping all share them). They enable `returnThinking(true)` on both providers, because langchain4j gates `onPartialThinking` on that flag — a missing flag silently drops cloud `reasoning_content` (symptom: thinking appears on Ollama, not in the cloud). Never re-inline `OpenAiChatModel.builder()` / `OllamaChatModel.builder()` elsewhere.
- Concept/theme extraction is built via `AiModelConfig.extractModelOf` and must stay **thinking-free**: Ollama uses native `think(false)`, OpenAI-compatible endpoints get `app.ai.extract.disable-thinking` (default `thinking.type=disabled`) through `customParameters`, because `max_tokens` is one shared generation budget and reasoning would eat the 512/2048 left for the answer. `returnThinking(false)` does NOT free the budget. These vendor params must reach only the extraction model — the chat model carries none of them. Two traps: `disable-thinking:` with an empty value breaks startup (null cannot bind to `Map` — write `{}`), and `enable_thinking: false` is silently ignored by some endpoints (verified on mimo).
- `src-tauri/gen/` and `src/main/resources/static/` are generated. Source files are UTF-8; PowerShell 5.1 `Get-Content` may display Chinese as mojibake — do not "fix" it by rewriting files.
