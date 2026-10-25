"""灵犀 reranker sidecar：bge-reranker-v2-m3 (ONNX int8) + CUDA EP + 动态批处理。

Java 端（HttpScoringModel）经 POST /score 消费打分；GET /health 观察激活的 EP 与攒批统计。
为什么是独立服务：本机上 JVM 进程加载 onnxruntime CUDA provider 必失败（DllMain 1114，
与依赖/加载方式/provider 构建均无关），而 Python wheel 构建实测 CUDA 正常——见 doc/06 决策 15。
"""
import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
import onnxruntime as ort
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from tokenizers import Tokenizer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("reranker")

# ---------- 配置（环境变量可覆盖） ----------
PROJECT = Path(__file__).resolve().parent.parent
MODEL = os.getenv("RERANKER_MODEL", str(PROJECT / "models/reranker/onnx-communitybge-reranker-v2-m3-ONNX/model_int8.onnx"))
TOKENIZER = os.getenv("RERANKER_TOKENIZER", str(PROJECT / "models/reranker/onnx-communitybge-reranker-v2-m3-ONNX/tokenizer.json"))
MAX_LEN = int(os.getenv("RERANKER_MAX_LEN", "384"))
MAX_BATCH = int(os.getenv("RERANKER_MAX_BATCH", "64"))          # 每批最多 (query,text) 对数
MAX_DELAY = float(os.getenv("RERANKER_MAX_DELAY_MS", "25")) / 1000.0  # 攒批窗口
MAX_QUEUE_PAIRS = int(os.getenv("RERANKER_MAX_QUEUE_PAIRS", "512"))    # 背压上限
PORT = int(os.getenv("PORT", "8900"))
# CUDA/cuDNN 运行库目录（Windows 下 onnxruntime 不搜这些位置，需显式挂载；目录不存在则跳过，
# CUDA EP 不可用时自动回退 CPU——与 Java 端 rerank-ep:auto 的回退语义一致）
CUDA_DIRS = [d for d in os.getenv(
    "RERANKER_CUDA_DIRS",
    r"C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.6\bin;"
    r"C:\Program Files\NVIDIA\CUDNN\v9.27\bin\12.9\x64",
).split(";") if d.strip()]

for d in CUDA_DIRS:
    if Path(d).is_dir():
        os.add_dll_directory(d)
        os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")
        log.info("已挂载 CUDA 依赖目录: %s", d)
    else:
        log.warning("CUDA 依赖目录不存在，跳过: %s", d)

# ---------- 会话与分词器 ----------
_opts = ort.SessionOptions()
_threads = int(os.getenv("RERANKER_THREADS", "0"))
if _threads > 0:
    _opts.intra_op_num_threads = _threads  # 仅影响 CPU 回退路径

session = ort.InferenceSession(MODEL, _opts, providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
ACTIVE_EP = session.get_providers()[0]
HAS_TOKEN_TYPE = "token_type_ids" in {i.name for i in session.get_inputs()}
tok = Tokenizer.from_file(TOKENIZER)
tok.enable_truncation(max_length=MAX_LEN)
log.info("模型已加载: %s | 生效 EP: %s", Path(MODEL).name, ACTIVE_EP)


def _infer(pairs: list[tuple[str, str]]) -> list[float]:
    """一批 (query, text) 对 → sigmoid 分数。pad 到批内最长，单次 session.run。"""
    encs = [tok.encode(q, t) for q, t in pairs]
    n, length = len(encs), max(len(e.ids) for e in encs)
    ids = np.zeros((n, length), dtype=np.int64)
    mask = np.zeros((n, length), dtype=np.int64)
    types = np.zeros((n, length), dtype=np.int64)
    for i, e in enumerate(encs):
        m = len(e.ids)
        ids[i, :m] = e.ids
        mask[i, :m] = 1
        if HAS_TOKEN_TYPE and e.type_ids:
            types[i, :m] = e.type_ids
    feed = {"input_ids": ids, "attention_mask": mask}
    if HAS_TOKEN_TYPE:
        feed["token_type_ids"] = types
    logits = np.asarray(session.run(None, feed)[0], dtype=np.float64).reshape(n)
    return (1.0 / (1.0 + np.exp(-logits))).tolist()  # 与 OnnxScoringModel(normalize=true) 同尺度


# ---------- 动态批处理：请求入队 → 攒批 → 一次推理 → 按来源拆分 ----------
class _Req:
    __slots__ = ("query", "texts", "future")

    def __init__(self, query: str, texts: list[str], future: asyncio.Future):
        self.query, self.texts, self.future = query, texts, future


queue: asyncio.Queue = asyncio.Queue()
pending_pairs = 0
stats = {"batches": 0, "pairs": 0, "last_batch_pairs": 0, "last_infer_ms": 0.0}


async def _batcher() -> None:
    """唯一批处理循环：等首请求 → 攒批（条数/窗口先到即冲）→ 线程池推理（期间新请求继续排队）。"""
    global pending_pairs
    while True:
        batch = [await queue.get()]
        pairs = len(batch[0].texts)
        deadline = time.monotonic() + MAX_DELAY
        while pairs < MAX_BATCH:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                r = await asyncio.wait_for(queue.get(), remaining)
            except asyncio.TimeoutError:
                break
            batch.append(r)
            pairs += len(r.texts)
        pending_pairs -= pairs
        started = time.monotonic()
        try:
            flat = [(r.query, t) for r in batch for t in r.texts]
            scores = await asyncio.to_thread(_infer, flat)
            i = 0
            for r in batch:
                n = len(r.texts)
                r.future.set_result(scores[i:i + n])
                i += n
        except Exception as e:  # 推理失败：整批各自收到异常
            for r in batch:
                if not r.future.done():
                    r.future.set_exception(e)
        stats["batches"] += 1
        stats["pairs"] += pairs
        stats["last_batch_pairs"] = pairs
        stats["last_infer_ms"] = (time.monotonic() - started) * 1000
        log.info("批完成: %d 请求 %d 对, 推理 %.0fms, 队列余 %d", len(batch), pairs,
                 stats["last_infer_ms"], pending_pairs)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    task = asyncio.create_task(_batcher())
    await asyncio.to_thread(_infer, [("数据库迁移", "我们决定用 PostgreSQL 并在下季度完成迁移")])  # 预热
    log.info("预热完成，服务就绪 (port %d)", PORT)
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="lingxi-reranker", docs_url=None, redoc_url=None, lifespan=lifespan)


class ScoreIn(BaseModel):
    query: str
    texts: list[str] = Field(min_length=1, max_length=256)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "provider": ACTIVE_EP,
        "model": Path(MODEL).name,
        "max_length": MAX_LEN,
        "batching": {
            "max_batch": MAX_BATCH,
            "max_delay_ms": MAX_DELAY * 1000,
            "batches": stats["batches"],
            "avg_batch_pairs": round(stats["pairs"] / stats["batches"], 1) if stats["batches"] else 0,
            "last_infer_ms": round(stats["last_infer_ms"], 1),
        },
    }


@app.post("/score")
async def score(inp: ScoreIn):
    global pending_pairs
    if pending_pairs + len(inp.texts) > MAX_QUEUE_PAIRS:
        raise HTTPException(503, "reranker 队列已满，请稍后重试")
    fut = asyncio.get_running_loop().create_future()
    pending_pairs += len(inp.texts)
    queue.put_nowait(_Req(inp.query, inp.texts, fut))
    try:
        return {"scores": await fut}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, f"推理失败: {e}")


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
