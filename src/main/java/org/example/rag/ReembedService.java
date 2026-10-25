package org.example.rag;

import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 全量重嵌入维护（M3，风险 R1 对策收尾）：更换嵌入模型后向量空间失效，
 * 逐篇 deleteVectors + ingest 重建（单篇失败计数不中断）。
 * 单线程维护执行器串行跑，volatile 计数供 /api/admin/reembed/status 轮询。
 */
@Service
public class ReembedService {

    private static final Logger log = LoggerFactory.getLogger(ReembedService.class);

    private final NoteRepository noteRepo;
    private final NoteIngestService ingestService;

    private volatile boolean running = false;
    private volatile int total = 0;
    private volatile int done = 0;
    private volatile int failed = 0;

    public ReembedService(NoteRepository noteRepo, NoteIngestService ingestService) {
        this.noteRepo = noteRepo;
        this.ingestService = ingestService;
    }

    public record ReembedStatus(boolean running, int total, int done, int failed) {}

    public ReembedStatus status() {
        return new ReembedStatus(running, total, done, failed);
    }

    /** 触发前置检查：已在跑则拒绝，避免并发重建。 */
    public synchronized boolean tryStart() {
        if (running) {
            return false;
        }
        running = true;
        done = 0;
        failed = 0;
        total = 0;
        return true;
    }

    @Async("maintenanceExecutor")
    public void reembedAll() {
        List<Note> notes = noteRepo.findAll();
        total = notes.size();
        log.info("全量重嵌入开始：{} 篇", total);
        for (Note n : notes) {
            try {
                ingestService.ingest(n);
                done++;
            } catch (Exception e) {
                failed++;
                log.warn("重嵌入失败 note={}（{}）：{}", n.getId(), n.getTitle(), String.valueOf(e.getMessage()));
            }
        }
        running = false;
        log.info("全量重嵌入完成：成功 {} / 失败 {} / 共 {}", done, failed, total);
    }
}
