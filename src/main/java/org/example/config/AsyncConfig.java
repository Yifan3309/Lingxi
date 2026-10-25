package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 富化等异步任务的执行器。有界队列 + CallerRuns 兜底：
 * LLM 慢/挂时不堆积任务，也不丢（保存线程自己跑，最坏退化为同步）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("enrichExecutor")
    public Executor enrichExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("enrich-");
        executor.initialize();
        return executor;
    }

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
}
