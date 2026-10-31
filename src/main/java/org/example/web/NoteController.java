package org.example.web;

import org.example.domain.Note;
import org.example.rag.NoteChunker;
import org.example.rag.NoteIngestService;
import org.example.rag.SemanticSearchService;
import org.example.service.NoteEnrichService;
import org.example.service.NoteService;
import org.example.web.dto.ChunkLayout;
import org.example.web.dto.NoteRequest;
import org.example.web.dto.NoteSummary;
import org.example.web.dto.RelatedNote;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记 CRUD。POST/PUT 同步触发分块+嵌入（M1 体量下延迟可接受，M2 异步化）。
 * 语义搜索在 RAG 问答中体现；本控制器 keyword 为普通 LIKE 检索。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService noteService;
    private final SemanticSearchService semanticSearchService;
    private final NoteEnrichService enrichService;
    private final NoteIngestService ingestService;

    public NoteController(NoteService noteService, SemanticSearchService semanticSearchService,
                          NoteEnrichService enrichService, NoteIngestService ingestService) {
        this.noteService = noteService;
        this.semanticSearchService = semanticSearchService;
        this.enrichService = enrichService;
        this.ingestService = ingestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Note create(@RequestBody NoteRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "content 不能为空");
        }
        return noteService.create(request);
    }

    /** 列表返回轻量投影（不含正文）；正文走 GET /{id}。 */
    @GetMapping
    public List<NoteSummary> list(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String tag) {
        return noteService.search(keyword, tag);
    }

    @GetMapping("/{id}")
    public Note get(@PathVariable String id) {
        return noteService.get(id);
    }

    /** 语义相关笔记推荐（M2）：基于向量相似度，排除自身，按笔记去重。 */
    @GetMapping("/{id}/related")
    public List<RelatedNote> related(@PathVariable String id) {
        Note note = noteService.get(id);
        return semanticSearchService.relatedNotes(note.getId(), note.getTitle(), note.getContent(), 5);
    }

    /** 分块布局（读时计算，不落库）：RAG 来源点击后前端据 chunk_index + 原文区间定位段落。 */
    @GetMapping("/{id}/chunks")
    public List<ChunkLayout> chunks(@PathVariable String id) {
        Note note = noteService.get(id);
        List<NoteChunker.Chunk> chunks = ingestService.layout(note);
        List<ChunkLayout> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            NoteChunker.Chunk c = chunks.get(i);
            result.add(new ChunkLayout(i, c.charStart(), c.charEnd(), c.headingPath()));
        }
        return result;
    }

    /** 显式 AI 富化（标签建议+摘要）：同步调用 LLM 并返回更新后的笔记；保存不再自动触发。 */
    @PostMapping("/{id}/enrich")
    public Note enrich(@PathVariable String id) {
        return enrichService.enrichNow(id);
    }

    @PutMapping("/{id}")
    public Note update(@PathVariable String id, @RequestBody NoteRequest request) {
        return noteService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        noteService.delete(id);
    }
}
