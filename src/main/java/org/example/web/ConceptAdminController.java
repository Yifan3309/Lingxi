package org.example.web;

import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.example.service.ConceptQueue;
import org.example.service.ConceptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 概念层维护端点：回填与汇总。
 *
 * <p>回填不是"批量任务"，它就是"把没抽过的笔记挨个排进队列"——
 * 因为抽取按篇自包含，不需要进度轮询那套编排。队列自己就是进度。
 */
@RestController
@RequestMapping("/api/admin/concepts")
public class ConceptAdminController {

    private static final Logger log = LoggerFactory.getLogger(ConceptAdminController.class);

    private final NoteRepository noteRepo;
    private final ConceptQueue queue;
    private final ConceptService conceptService;

    public ConceptAdminController(NoteRepository noteRepo, ConceptQueue queue, ConceptService conceptService) {
        this.noteRepo = noteRepo;
        this.queue = queue;
        this.conceptService = conceptService;
    }

    /**
     * 回填：把所有"没抽过"的笔记排进队列。
     *
     * @param all true 时连已抽过的也重抽（先清指纹，否则会被"内容未变"直接跳过）
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam(defaultValue = "false") boolean all) {
        List<String> queued = new ArrayList<>();
        for (Note note : noteRepo.findAll()) {
            if (note.getContent() == null || note.getContent().isBlank()) {
                continue;
            }
            if (!all && note.getConceptHash() != null) {
                continue;
            }
            if (all) {
                note.setConceptHash(null);
                noteRepo.save(note);
            }
            queue.submit(note.getId());
            queued.add(note.getId());
        }
        log.info("概念回填：{} 篇排入队列（all={}）", queued.size(), all);
        return Map.of("queued", queued.size(), "all", all);
    }

    /**
     * 强制重算主题层。
     *
     * <p>模型模式下这是一次全量分组，按概念总数付模型调用的钱（当前 107 个概念约 17 秒）；
     * 文件夹模式是本地计算，毫秒级。两者都可以用这个端点触发。
     */
    @PostMapping("/rebuild-themes")
    public Map<String, Object> rebuildThemes() {
        return conceptService.rebuildTopics();
    }

    /** 重算主题层。全量重建，本地计算，不花模型调用 */
    @PostMapping("/rebuild-topics")
    public Map<String, Object> rebuildTopics() {
        return conceptService.rebuildTopics();
    }

    /** 进度：已抽 / 未抽 / 队列中。前端只在需要时拉一次，不轮询 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        long total = 0;
        long extracted = 0;
        for (Note note : noteRepo.findAll()) {
            if (note.getContent() == null || note.getContent().isBlank()) {
                continue;
            }
            total++;
            if (note.getConceptHash() != null) {
                extracted++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notes", total);
        out.put("extracted", extracted);
        out.put("pending", total - extracted);
        out.put("queued", queue.pendingCount());
        return out;
    }
}
