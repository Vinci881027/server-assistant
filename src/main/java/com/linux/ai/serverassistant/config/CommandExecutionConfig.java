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
public class CommandExecutionConfig {

    @Bean(name = "commandOutputCaptureExecutor", destroyMethod = "shutdown")
    public ExecutorService commandOutputCaptureExecutor(
            @Value("${app.command.capture.max-parallel:32}") int maxParallel,
            @Value("${app.command.capture.queue-capacity:256}") int queueCapacity,
            @Value("${app.command.capture.keep-alive-seconds:60}") long keepAliveSeconds) {
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
                    Thread thread = new Thread(runnable, "cmd-output-capture-" + threadCounter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
