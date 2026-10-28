package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 维护类异步任务的执行器。AI 富化已改显式同步触发（POST /api/notes/{id}/enrich），
 * 不再需要独立线程池；此处仅保留后台维护任务（全量重嵌入 / 仓库迁移）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 全量重嵌入等维护任务：单线程串行（避免两批重建并发争用嵌入模型与向量库写锁） */
    @Bean("maintenanceExecutor")
    public Executor maintenanceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("maintain-");
        executor.initialize();
        return executor;
    }

    /** 旧仓库迁移：单线程串行逐篇入库，进度经 volatile 计数暴露给轮询端点 */
    @Bean("vaultExecutor")
    public Executor vaultExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("vault-");
        executor.initialize();
        return executor;
    }
}
