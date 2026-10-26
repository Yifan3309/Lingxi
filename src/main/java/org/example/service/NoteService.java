package org.example.service;

import org.example.config.EnrichProperties;
import org.example.domain.Note;
import org.example.rag.NoteIngestService;
import org.example.repository.NoteRepository;
import org.example.web.dto.NoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 笔记 CRUD。嵌入入库失败不阻断保存（笔记仍可按关键词检索，只是暂不可语义召回）。
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

    private final NoteRepository noteRepo;
    private final NoteIngestService ingestService;
    private final NoteEnrichService enrichService;
    private final EnrichProperties enrichProps;

    public NoteService(NoteRepository noteRepo,
                       NoteIngestService ingestService,
                       NoteEnrichService enrichService,
                       EnrichProperties enrichProps) {
        this.noteRepo = noteRepo;
        this.ingestService = ingestService;
        this.enrichService = enrichService;
        this.enrichProps = enrichProps;
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
        enrichQuietly(note);
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
        enrichQuietly(note);
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

    public List<Note> search(String keyword, String tag) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasTag = tag != null && !tag.isBlank();
        List<Note> notes;
        if (hasKeyword) {
            notes = noteRepo.findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(keyword, keyword, SORT);
        } else if (hasTag) {
            notes = noteRepo.findByTagsContainingIgnoreCase(tag, SORT);
        } else {
            notes = noteRepo.findAll(SORT);
        }
        if (hasTag && hasKeyword) {
            notes = notes.stream()
                    .filter(n -> n.getTags() != null && n.getTags().toLowerCase().contains(tag.toLowerCase()))
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

    private void ingestQuietly(Note note) {        try {
            ingestService.ingest(note);
        } catch (Exception e) {
            log.error("笔记 {} 嵌入入库失败，已保存但暂不可语义检索", note.getId(), e);
        }
    }

    private void enrichQuietly(Note note) {
        if (!enrichProps.enabled()) {
            return;
        }
        try {
            enrichService.enrich(note.getId());
        } catch (Exception e) {
            log.warn("笔记 {} 富化任务提交失败：{}", note.getId(), String.valueOf(e.getMessage()));
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
