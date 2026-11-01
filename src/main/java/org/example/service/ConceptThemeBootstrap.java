package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 启动后补一次主题同步。
 *
 * <p>场景：应用重启时概念已经在库里、但主题还没生成（首次上线、或上次生成失败）。
 * 这时没有抽取任务在跑，队列里的"排空后同步"不会触发，主题就会一直空着。
 *
 * <p>走异步线程，不阻塞启动，也不阻塞第一个请求。
 */
@Component
public class ConceptThemeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ConceptThemeBootstrap.class);

    private final Executor executor;
    private final ConceptService conceptService;

    public ConceptThemeBootstrap(@Qualifier("maintenanceExecutor") Executor executor,
                                 ConceptService conceptService) {
        this.executor = executor;
        this.conceptService = conceptService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            executor.execute(() -> {
                try {
                    if (conceptService.needsTopicRebuild()) {
                        log.info("启动后同步主题层");
                        conceptService.syncTopics();
                    }
                } catch (Exception e) {
                    log.warn("启动后同步主题层失败：{}", String.valueOf(e.getMessage()));
                }
            });
        } catch (Exception e) {
            log.warn("主题同步任务未能提交：{}", String.valueOf(e.getMessage()));
        }
    }
}
