package org.example.service;

import org.example.config.ConceptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 概念抽取的异步队列：保存之后排队，不阻塞保存。
 *
 * <p>为什么要排队而不是同步：保存是用户明确的动作，能不能存下来和能不能抽出概念
 * 是两件事。模型没起、超时、返回垃圾，都不该让"保存失败"弹出来。
 *
 * <p><b>为什么自己持有任务队列，而不是把任务直接丢给线程池：</b>
 * 池子（maintenanceExecutor）的 queueCapacity 只有 1，它的设计前提是"一次只跑一个维护任务"。
 * 回填时一次提交 34 篇，第 3 篇起就会被拒绝，而且是静默失败——回填看起来完成了，
 * 实际只抽了两篇。所以这里只向线程池提交<b>一个</b>消费者，任务本身放在自己的队列里：
 * 池子的容量从此与批量大小无关，批量回填和逐篇保存走同一条路。
 *
 * <p>单线程是刻意的：本地只有一个模型，并发只会互相拖慢；顺序也恰好是用户操作的顺序。
 */
@Component
public class ConceptQueue {

    private static final Logger log = LoggerFactory.getLogger(ConceptQueue.class);

    private final Executor executor;
    private final ConceptService conceptService;
    private final ConceptProperties props;

    /** 待抽取的笔记 id，先进先出 */
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    /** 去重集合：一次编辑会话里同一篇可能被排好几次，抽完才允许再次入队 */
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    /** 消费者是否在跑，保证线程池里同时最多一个消费任务 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ConceptQueue(@Qualifier("maintenanceExecutor") Executor executor,
                        ConceptService conceptService,
                        ConceptProperties props) {
        this.executor = executor;
        this.conceptService = conceptService;
        this.props = props;
    }

    /** 保存路径专用：受 extractOnSave 开关控制（回填不受它影响） */
    public void submitOnSave(String noteId) {
        if (!props.enabled() || !props.extractOnSave()) {
            return;
        }
        submit(noteId);
    }

    /** 排队抽取一篇。已在队列或正在抽的直接返回，不重复排队 */
    public void submit(String noteId) {
        if (noteId == null || !known.add(noteId)) {
            return;
        }
        queue.offer(noteId);
        pump();
    }

    /** 确保有一个消费者在跑；已经有一个就什么都不做 */
    private void pump() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (Exception e) {
            // 线程池此刻满（比如正在全量重嵌入）：任务留在自己的队列里，下次入队时再拉
            running.set(false);
            log.warn("概念抽取消费者未能提交到线程池，任务保留在队列中：{}", String.valueOf(e.getMessage()));
        }
    }

    /** 消费者：一直取到队列空为止 */
    private void drain() {
        try {
            String noteId;
            while ((noteId = queue.poll()) != null) {
                try {
                    conceptService.extract(noteId);
                } catch (Exception e) {
                    // 抽取失败只是这篇笔记暂时没有概念，不影响任何已保存的内容
                    log.warn("笔记 {} 概念抽取失败：{}", noteId, String.valueOf(e.getMessage()));
                } finally {
                    known.remove(noteId);
                }
            }
        } finally {
            running.set(false);
            // 一批抽取结束后同步主题层。
            // 少了这一步会出现"看起来正常、其实没归类"的状态：
            // 主题表里已经有几个主题（不是 0），但后抽出来的概念一条主题边都没有，
            // 页面上它们全落进未分类——实测 103 个概念里 89 个是这样。
            // 模型模式下这一步只做增量归类（一次调用），文件夹模式是本地全量重算。
            try {
                conceptService.syncTopics();
            } catch (Exception e) {
                log.warn("同步主题层失败：{}", String.valueOf(e.getMessage()));
            }
            // 收尾这一瞬间可能又有新任务进来，补一次
            if (!queue.isEmpty()) {
                pump();
            }
        }
    }

    /** 队列里还有多少篇没抽（含正在抽的那篇） */
    public int pendingCount() {
        return known.size();
    }
}
