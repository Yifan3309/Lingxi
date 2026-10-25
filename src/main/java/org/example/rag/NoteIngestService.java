package org.example.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.example.config.AiProperties;
import org.example.config.RagProperties;
import org.example.domain.Note;
import org.example.domain.NoteChunk;
import org.example.repository.NoteChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 入库管线：笔记 → 分块 → 嵌入 → 向量库 + note_chunk 元数据。
 * 更新笔记时先清旧块再写新块（数据库与向量库同步重建）。
 */
@Service
public class NoteIngestService {

    private static final Logger log = LoggerFactory.getLogger(NoteIngestService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStoreService storeService;
    private final NoteChunkRepository chunkRepo;
    private final AiProperties aiProps;
    private final RagProperties ragProps;

    public NoteIngestService(EmbeddingModel embeddingModel,
                             EmbeddingStoreService storeService,
                             NoteChunkRepository chunkRepo,
                             AiProperties aiProps,
                             RagProperties ragProps) {
        this.embeddingModel = embeddingModel;
        this.storeService = storeService;
        this.chunkRepo = chunkRepo;
        this.aiProps = aiProps;
        this.ragProps = ragProps;
    }

    @Transactional
    public void deleteVectors(String noteId) {
        chunkRepo.deleteByNoteId(noteId);
        storeService.removeAllByNoteId(noteId);
    }

    @Transactional
    public void ingest(Note note) {
        chunkRepo.deleteByNoteId(note.getId());
        storeService.removeAllByNoteId(note.getId());

        List<String> chunks = NoteChunker.chunk(note.getTitle(), note.getContent(), ragProps.chunkTargetChars());
        if (chunks.isEmpty()) {
            log.info("笔记 {} 无可分块内容，跳过嵌入", note.getId());
            return;
        }

        List<String> ids = new ArrayList<>(chunks.size());
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        List<NoteChunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = UUID.randomUUID().toString();
            Metadata metadata = new Metadata()
                    .put("note_id", note.getId())
                    .put("chunk_id", chunkId)
                    .put("title", note.getTitle())
                    .put("chunk_index", i);
            segments.add(TextSegment.from(chunks.get(i), metadata));
            ids.add(chunkId);

            NoteChunk row = new NoteChunk();
            row.setNoteId(note.getId());
            row.setChunkIndex(i);
            row.setText(chunks.get(i));
            row.setEmbeddingModel(currentEmbeddingModelName());
            rows.add(row);
        }

        // E5 等模型要求文档侧加前缀；嵌入用带前缀的副本，向量库存未加前缀的原文
        String passagePrefix = ragProps.passagePrefix();
        List<TextSegment> toEmbed = passagePrefix.isEmpty()
                ? segments
                : segments.stream()
                        .map(s -> TextSegment.from(passagePrefix + s.text(), s.metadata()))
                        .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(toEmbed).content();
        storeService.addAll(ids, embeddings, segments);
        chunkRepo.saveAll(rows);
        log.info("笔记 {} 入库完成：{} 个分块", note.getId(), chunks.size());
    }

    private String currentEmbeddingModelName() {
        AiProperties.Embedding cfg = aiProps.embedding();
        return switch (cfg.provider()) {
            case "ollama" -> cfg.ollama().embeddingModel();
            case "onnx" -> "onnx:" + Path.of(cfg.onnx().modelPath()).getFileName();
            default -> cfg.openaiCompatible().embeddingModel();
        };
    }
}
