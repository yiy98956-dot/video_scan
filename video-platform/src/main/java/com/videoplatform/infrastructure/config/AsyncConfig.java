package com.videoplatform.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("videoExecutor")
    public Executor videoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU 核心数
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        // 最大线程数
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        // 队列容量
        executor.setQueueCapacity(500);
        // 线程前缀
        executor.setThreadNamePrefix("Video-Async-");
        // 拒绝策略：由调用者线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
