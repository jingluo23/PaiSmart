package com.jingluo.paismart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 15:50
 * @Desc: 异步任务线程池配置
 */
@Configuration
public class AsyncExecutorConfig {

    /**
     * 聊天监控专用线程池：核心4线程，峰值16线程，队列容量200，关闭时等待任务完成
     *
     * @return 已初始化的聊天监控线程池执行器
     */
    @Bean(name = "chatMonitorExecutor")
    public ThreadPoolTaskExecutor chatMonitorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-monitor-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();

        return executor;
    }
}
