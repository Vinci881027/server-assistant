package com.linux.ai.serverassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SystemStatusConfig {

    @Bean(name = "systemStatusExecutor", destroyMethod = "shutdown")
    public ExecutorService systemStatusExecutor(
            @Value("${app.system.status.async.max-parallel:8}") int maxParallel,
            @Value("${app.system.status.async.queue-capacity:64}") int queueCapacity,
            @Value("${app.system.status.async.keep-alive-seconds:30}") long keepAliveSeconds) {
        int workers = Math.max(1, maxParallel);
        int queueSize = Math.max(1, queueCapacity);
        AtomicInteger threadCounter = new AtomicInteger(1);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                Math.max(1L, keepAliveSeconds),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                runnable -> {
                    Thread thread = new Thread(runnable, "system-status-" + threadCounter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
