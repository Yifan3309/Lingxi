package org.example.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.example.rag.EmbeddingStoreService;
import org.example.rag.HttpScoringModel;
import org.example.rag.NoteAssistant;
import org.example.rag.VectorSearchRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配。
 * 无重排：向量召回 top-k 直出。
 * 有重排（app.rag.reranker-enabled=true）：向量召回 top-k×recallMultiplier 候选，
 * ReRankingContentAggregator 用 cross-encoder 重排后截断回 top-k，
 * 重排分数写入 Content 的 RERANKED_SCORE 元数据（done 帧来源可见）。
 * reranker-provider：http = Python sidecar（本机 JVM 加载 CUDA provider 必 1114，GPU 只能出进程，见决策 15；
 * 不可达/自检失败自动回退本地 onnx CPU）；onnx = 进程内本地。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.rag", name = "reranker-enabled", havingValue = "true")
    public ScoringModel rerankScoringModel(RagProperties props) {
        if ("http".equalsIgnoreCase(props.rerankerProvider())) {
            try {
                ScoringModel model = new HttpScoringModel(props.rerankerUrl());
                verifySanity(model, props); // 真实调用一次 /score：兼作连通性与方向自检
                log.info("重排模型走 HTTP 服务（{}，GPU sidecar）", props.rerankerUrl());
                return model;
            } catch (Throwable e) {
                log.warn("HTTP reranker 不可用（{}），回退本地 onnx CPU。原因：{}", props.rerankerUrl(),
                        truncate(String.valueOf(e.getMessage()), 200));
            }
        }
        // EP：auto = 先试 CUDA，失败（无 NVIDIA 驱动/CUDA/cuDNN）自动回退 CPU——GPU 包内置 CPU EP，能力不丢
        String ep = props.rerankEp() == null ? "auto" : props.rerankEp().toLowerCase();
        if ("cuda".equals(ep) || "auto".equals(ep)) {
            try {
                // CUDA 会话必须在专用大栈线程上创建：Spring 启动期主线程调用栈极深（反射/CGLIB 嵌套），
                // 674MB 的 provider DLL 在 DllMain 初始化时耗栈大，深层栈上会溢出（表现为"初始化例程失败"→
                // "Failed to find CUDA shared provider"），且该 DLL 在进程内永久失败不可重试
                final ScoringModel[] holder = new ScoringModel[1];
                final Throwable[] failure = new Throwable[1];
                Thread init = new Thread(null, () -> {
                    try {
                        holder[0] = buildScoringModel(props, true);
                    } catch (Throwable t) {
                        failure[0] = t;
                    }
                }, "rerank-cuda-init", 32L * 1024 * 1024);
                init.start();
                init.join();
                if (failure[0] != null) {
                    throw failure[0];
                }
                log.info("重排模型启用 CUDA EP（GPU 加速）");
                verifySanity(holder[0], props);
                return holder[0];
            } catch (Throwable e) {
                if ("cuda".equals(ep)) {
                    throw new IllegalStateException("rerank-ep=cuda 但 CUDA 会话创建失败：检查 NVIDIA 驱动/CUDA 12.x/cuDNN 9.x。"
                            + "或改 rerank-ep=auto/cpu。原因：" + e.getMessage(), e);
                }
                log.warn("CUDA EP 不可用（{}），回退 CPU EP。若需 GPU 加速请安装 CUDA 12.x + cuDNN 9.x",
                        truncate(String.valueOf(e.getMessage()), 200));
            }
        }
        ScoringModel cpuModel = buildScoringModel(props, false);
        log.info("重排模型使用 CPU EP");
        verifySanity(cpuModel, props);
        return cpuModel;
    }

    private ScoringModel buildScoringModel(RagProperties props, boolean cuda) {
        try {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            int threads = props.rerankThreads() > 0
                    ? props.rerankThreads()
                    : Runtime.getRuntime().availableProcessors();
            options.setIntraOpNumThreads(threads);
            if (cuda) {
                // ORT 1.20 Windows 版加载 CUDA provider 的搜索不含"已加载 DLL 所在目录"（temp 解包目录）：
                // 显式预载（java.library.path 派生自 PATH，可命中 provider 所在目录）
                try {
                    System.loadLibrary("onnxruntime_providers_shared");
                    System.loadLibrary("onnxruntime_providers_cuda");
                } catch (UnsatisfiedLinkError pre) {
                    log.warn("provider DLL 预载失败（将由 ONNX 自行查找）：{}", String.valueOf(pre.getMessage()));
                }
                options.addCUDA(0);
            }
            return new OnnxScoringModel(
                    props.rerankerModelPath(), options, props.rerankerTokenizerPath(),
                    props.rerankMaxLength(), true);
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX 会话创建失败（cuda=" + cuda + "）：" + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 启动自检：坏导出（如输出 hidden states 而非相关性 logit）不会报错，
     * 只会静默用噪声重排检索结果。用一对相关/无关文本试打分，
     * 方向不对直接让应用启动失败，把问题暴露在最早时刻。
     */
    private static void verifySanity(ScoringModel model, RagProperties props) {
        String query = "数据库迁移计划";
        TextSegment relevant = TextSegment.from("我们团队决定采用 PostgreSQL 作为主数据库，预计下季度完成迁移。");
        TextSegment irrelevant = TextSegment.from("今天天气晴朗，适合出门散步。");
        double rel = model.score(relevant, query).content();
        double irr = model.score(irrelevant, query).content();
        if (rel <= irr) {
            throw new IllegalStateException(String.format(
                    "reranker 启动自检失败：相关文本得分 %s 应高于无关文本 %s。模型文件可能损坏或导出格式错误：%s",
                    rel, irr, props.rerankerModelPath()));
        }
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStoreService storeService,
                                             EmbeddingModel embeddingModel,
                                             RagProperties props) {
        int recall = props.rerankerEnabled() ? props.topK() * props.recallMultiplier() : props.topK();
        return new VectorSearchRetriever(embeddingModel, storeService.store(), recall, props.queryPrefix());
    }

    @Bean
    public NoteAssistant noteAssistant(StreamingChatModel streamingChatModel,
                                       ContentRetriever contentRetriever,
                                       ObjectProvider<ScoringModel> reranker,
                                       RagProperties props) {
        AiServices<NoteAssistant> builder = AiServices.builder(NoteAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20));
        ScoringModel scoringModel = reranker.getIfAvailable();
        if (scoringModel != null) {
            RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                    .contentRetriever(contentRetriever)
                    .contentAggregator(ReRankingContentAggregator.builder()
                            .scoringModel(scoringModel)
                            .minScore(props.rerankMinScore())
                            .maxResults(props.topK())
                            .build())
                    .build();
            builder.retrievalAugmentor(augmentor);
        } else {
            builder.contentRetriever(contentRetriever);
        }
        return builder.build();
    }
}
