package com.ragapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async executor configuration for document indexing jobs and background tasks.
 * Uses a bounded thread pool to prevent resource exhaustion under load.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AsyncConfig implements AsyncConfigurer {

    private final AppProperties props;

    @Bean(name = "ragTaskExecutor")
    public ThreadPoolTaskExecutor ragTaskExecutor() {
        AppProperties.Async asyncProps = props.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncProps.getCorePoolSize());
        executor.setMaxPoolSize(asyncProps.getMaxPoolSize());
        executor.setQueueCapacity(asyncProps.getQueueCapacity());
        executor.setThreadNamePrefix(asyncProps.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("Task rejected — executor queue full. Consider increasing queue-capacity."));
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return ragTaskExecutor();
    }
}
