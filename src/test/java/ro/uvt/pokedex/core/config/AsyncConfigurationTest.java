package ro.uvt.pokedex.core.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncConfigurationTest {

    @Test
    void registersGaugesAndTracksRejections() throws InterruptedException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AsyncConfiguration configuration = new AsyncConfiguration(meterRegistry);
        ReflectionTestUtils.setField(configuration, "coreSize", 1);
        ReflectionTestUtils.setField(configuration, "maxSize", 1);
        ReflectionTestUtils.setField(configuration, "queueCapacity", 0);
        ReflectionTestUtils.setField(configuration, "threadNamePrefix", "TestAsync-");

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.taskExecutor();

        CountDownLatch blocker = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                blocker.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
        }));

        blocker.countDown();
        executor.shutdown();

        assertThat(meterRegistry.find("core.async.active.threads").gauge()).isNotNull();
        assertThat(meterRegistry.find("core.async.queue.size").gauge()).isNotNull();
        assertThat(meterRegistry.find("core.async.rejections").counter()).isNotNull();
        assertThat(meterRegistry.find("core.async.rejections").counter().count()).isGreaterThanOrEqualTo(1.0);
    }
}
