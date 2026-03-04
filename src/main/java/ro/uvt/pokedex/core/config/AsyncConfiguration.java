package ro.uvt.pokedex.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableAsync
@RequiredArgsConstructor
@Slf4j
public class AsyncConfiguration {

    private final MeterRegistry meterRegistry;

    @Value("${core.async.pool.core-size:2}")
    private int coreSize;

    @Value("${core.async.pool.max-size:2}")
    private int maxSize;

    @Value("${core.async.pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${core.async.pool.thread-name-prefix:CarThread-}")
    private String threadNamePrefix;

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            meterRegistry.counter("core.async.rejections", "component", "taskExecutor").increment();
            log.warn("Async task rejected: component=taskExecutor, active={}, queueSize={}",
                    poolExecutor.getActiveCount(), poolExecutor.getQueue().size());
            throw new RejectedExecutionException("Async task rejected by taskExecutor");
        });
        executor.initialize();
        bindExecutorMetrics(executor);
        return executor;
    }

    private void bindExecutorMetrics(ThreadPoolTaskExecutor executor) {
        meterRegistry.gauge("core.async.active.threads", executor, ThreadPoolTaskExecutor::getActiveCount);
        meterRegistry.gauge("core.async.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize);
        meterRegistry.gauge("core.async.queue.size", executor, e -> {
            if (e.getThreadPoolExecutor() == null) {
                return 0;
            }
            return e.getThreadPoolExecutor().getQueue().size();
        });
        meterRegistry.gauge("core.async.queue.remaining", executor, e -> {
            if (e.getThreadPoolExecutor() == null) {
                return 0;
            }
            return e.getThreadPoolExecutor().getQueue().remainingCapacity();
        });
        meterRegistry.timer("core.async.executor.bind.duration", "component", "taskExecutor")
                .record(1, TimeUnit.MILLISECONDS);
    }
}
