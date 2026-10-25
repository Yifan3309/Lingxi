package org.example.service;

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

    public NoteService(NoteRepository noteRepo, NoteIngestService ingestService) {
        this.noteRepo = noteRepo;
        this.ingestService = ingestService;
    }

    public Note create(NoteRequest request) {
        Note note = new Note();
        note.setTitle(resolveTitle(request));
        note.setContent(request.content());
        note.setTags(request.tags());
        note = noteRepo.save(note);
        try {
            ingestService.ingest(note);
        } catch (Exception e) {
            log.error("笔记 {} 嵌入入库失败，已保存但暂不可语义检索", note.getId(), e);
        }
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
        note = noteRepo.save(note);
        try {
            ingestService.ingest(note);
        } catch (Exception e) {
            log.error("笔记 {} 重新嵌入失败，向量保持旧版本", id, e);
        }
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
