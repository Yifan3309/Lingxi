package org.example.web;

import org.example.domain.Note;
import org.example.service.NoteService;
import org.example.web.dto.NoteRequest;
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

import java.util.List;

/**
 * 笔记 CRUD。POST/PUT 同步触发分块+嵌入（M1 体量下延迟可接受，M2 异步化）。
 * 语义搜索在 RAG 问答中体现；本控制器 keyword 为普通 LIKE 检索。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
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

    @GetMapping
    public List<Note> list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String tag) {
        return noteService.search(keyword, tag);
    }

    @GetMapping("/{id}")
    public Note get(@PathVariable String id) {
        return noteService.get(id);
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
