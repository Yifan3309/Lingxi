package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.example.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量库门面：持有进程内 InMemoryEmbeddingStore 实例，
 * 每次变更后整库序列化到文件（M1 数据量下开销可忽略）。
 * 换 PgVector/Milvus 时仅需替换本类实现，检索方无感。
 */
@Service
public class EmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreService.class);

    private final Path storePath;
    private final InMemoryEmbeddingStore<TextSegment> store;
    private final Object persistLock = new Object();

    public EmbeddingStoreService(RagProperties props) {
        this.storePath = Path.of(props.storePath());
        if (Files.exists(storePath)) {
            this.store = InMemoryEmbeddingStore.fromFile(storePath);
            log.info("已从 {} 加载向量库", storePath);
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            log.info("向量库为空，将在 {} 新建", storePath);
        }
    }

    public InMemoryEmbeddingStore<TextSegment> store() {
        return store;
    }

    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        store.addAll(ids, embeddings, segments);
        persist();
    }

    public void removeAllByNoteId(String noteId) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        persist();
    }

    private void persist() {
        synchronized (persistLock) {
            try {
                if (storePath.getParent() != null) {
                    Files.createDirectories(storePath.getParent());
                }
                store.serializeToFile(storePath);
            } catch (IOException e) {
                // 持久化失败不阻断检索链路，但要让问题可见
                throw new UncheckedIOException("向量库写入失败: " + storePath, e);
            }
        }
    }
}
