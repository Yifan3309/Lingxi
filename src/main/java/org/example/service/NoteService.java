package org.example.service;

import org.example.domain.Note;
import org.example.rag.NoteIngestService;
import org.example.repository.NoteRepository;
import org.example.web.dto.NoteRequest;
import org.example.web.dto.NoteSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 笔记 CRUD。嵌入入库失败不阻断保存（笔记仍可按关键词检索，只是暂不可语义召回）。
 * AI 富化（标签建议/摘要）已改显式触发（POST /api/notes/{id}/enrich），保存路径不再调用 LLM。
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

    private final NoteRepository noteRepo;
    private final NoteIngestService ingestService;
    private final ConceptQueue conceptQueue;

    public NoteService(NoteRepository noteRepo, NoteIngestService ingestService,
                       ConceptQueue conceptQueue) {
        this.noteRepo = noteRepo;
        this.ingestService = ingestService;
        this.conceptQueue = conceptQueue;
    }

    public Note create(NoteRequest request) {
        return create(request, "manual");
    }

    /** 指定来源的创建（manual/import/vision，多模态导入用）。 */
    public Note create(NoteRequest request, String source) {
        Note note = new Note();
        note.setTitle(resolveTitle(request));
        note.setContent(request.content());
        note.setTags(request.tags());
        note.setFolder(normalizeFolder(request.folder()));
        note.setSource(source);
        note = noteRepo.save(note);
        ingestQuietly(note);
        conceptQueue.submitOnSave(note.getId());
        return note;
    }

    public Note update(String id, NoteRequest request) {
        Note note = findOr404(id);
        if (request.title() != null) {
            note.setTitle(request.title());
        }
        if (request.content() != null) {
            note.setContent(request.content());
        }
        if (request.tags() != null) {
            note.setTags(request.tags());
        }
        if (request.folder() != null) {
            note.setFolder(normalizeFolder(request.folder()));
        }
        note = noteRepo.save(note);
        ingestQuietly(note);
        conceptQueue.submitOnSave(note.getId());
        return note;
    }

    public void delete(String id) {
        Note note = findOr404(id);
        ingestService.deleteVectors(note.getId());
        noteRepo.delete(note);
    }

    public Note get(String id) {
        return findOr404(id);
    }

    /**
     * 列表与关键词/标签搜索：返回投影 {@link NoteSummary}（不含 content），
     * 正文只在详情接口返回——列表每刷新一次就搬全库正文是没必要的大开销。
     * keyword 命中由数据库 LIKE 决定；keyword + tag 同时给出时，标签在内存里二次过滤（与原行为一致）。
     */
    public List<NoteSummary> search(String keyword, String tag) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasTag = tag != null && !tag.isBlank();
        List<NoteSummary> notes;
        if (hasKeyword) {
            notes = noteRepo.findSummariesByKeyword(keyword, SORT);
        } else if (hasTag) {
            notes = noteRepo.findSummariesByTag(tag, SORT);
        } else {
            notes = noteRepo.findAllSummaries(SORT);
        }
        if (hasTag && hasKeyword) {
            notes = notes.stream()
                    .filter(n -> n.tags() != null && n.tags().toLowerCase().contains(tag.toLowerCase()))
                    .toList();
        }
        return notes;
    }

    /**
     * 文件夹路径规范化（多层文件夹：/ 分隔）：反斜杠统一为 /，逐段去空白、丢空段
     * （容忍首尾与连续斜杠），全空视为未归类（null）；「.」「..」段与超长路径拒绝。
     */
    private static String normalizeFolder(String folder) {
        if (folder == null) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (String raw : folder.replace('\\', '/').split("/")) {
            String seg = raw.trim();
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹路径段不能是 . 或 ..：" + seg);
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(seg);
        }
        if (path.length() == 0) {
            return null;
        }
        if (path.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹路径过长（上限 128 字符）：" + path);
        }
        return path.toString();
    }

    /**
     * 概念抽取排在保存之后，走异步队列：保存是用户明确的动作，能不能存下来
     * 和能不能抽出概念是两件事，模型挂了不该让"保存失败"弹出来。
     * 是否真的抽由内容的 SHA-256 决定（见 ConceptService#extract），重复保存不会重复调用。
     */
    private void ingestQuietly(Note note) {
        try {
            ingestService.ingest(note);
        } catch (Exception e) {
            log.error("笔记 {} 嵌入入库失败，已保存但暂不可语义检索", note.getId(), e);
        }
    }

    private Note findOr404(String id) {
        return noteRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
    }

    private String resolveTitle(NoteRequest request) {
        if (request.title() != null && !request.title().isBlank()) {
            return request.title().trim();
        }
        String content = request.content() == null ? "" : request.content().strip();
        int lineEnd = content.indexOf('\n');
        String firstLine = lineEnd > 0 ? content.substring(0, lineEnd) : content;
        String title = firstLine.strip();
        return title.length() > 30 ? title.substring(0, 30) : title;
    }
}
