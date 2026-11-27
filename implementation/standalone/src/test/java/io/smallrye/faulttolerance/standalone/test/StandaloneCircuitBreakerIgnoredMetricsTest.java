package io.smallrye.faulttolerance.standalone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.api.TypedGuard;
import io.smallrye.faulttolerance.core.metrics.MetricsConstants;
import io.smallrye.faulttolerance.standalone.Configuration;
import io.smallrye.faulttolerance.standalone.MetricsAdapter;
import io.smallrye.faulttolerance.standalone.MicrometerAdapter;
import io.smallrye.faulttolerance.standalone.StandaloneFaultTolerance;

public class StandaloneCircuitBreakerIgnoredMetricsTest {
    private static final String NAME = StandaloneCircuitBreakerIgnoredMetricsTest.class.getName();

    static ExecutorService executor;
    static MeterRegistry metrics;

    @BeforeAll
    public static void setUp() {
        executor = Executors.newCachedThreadPool();
        metrics = new SimpleMeterRegistry();

        StandaloneFaultTolerance.configure(new Configuration() {
            @Override
            public ExecutorService executor() {
                return executor;
            }

            @Override
            public MetricsAdapter metricsAdapter() {
                return new MicrometerAdapter(metrics);
            }

            @Override
            public void onShutdown() throws InterruptedException {
                metrics.close();

                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        });
    }

    @AfterAll
    public static void tearDown() throws InterruptedException {
        StandaloneFaultTolerance.shutdown();
    }

    private double circuitBreakerCalls(String description, String result) {
        Counter counter = metrics.find(MetricsConstants.CIRCUIT_BREAKER_CALLS_TOTAL)
                .tags(List.of(
                        Tag.of("method", description),
                        Tag.of("circuitBreakerResult", result)))
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    public void ignoredInvocationsAreNotCountedInCircuitBreakerCallsTotal() throws Exception {
        String description = NAME + " ignored only";

        Callable<String> guarded = TypedGuard.create(String.class)
                .withDescription(description)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(() -> {
                    throw new RateLimitException(100, "rl");
                });

        for (int i = 0; i < 4; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }

        // ignored invocations produce no success/failure/circuitBreakerOpen counters for this method
        assertThat(circuitBreakerCalls(description, "success")).isZero();
        assertThat(circuitBreakerCalls(description, "failure")).isZero();
        assertThat(circuitBreakerCalls(description, "circuitBreakerOpen")).isZero();
    }

    @Test
    public void onlySuccessesAndFailuresAreCountedInCircuitBreakerCallsTotal() throws Exception {
        String description = NAME + " mixed";

        Callable<String> guarded = TypedGuard.create(String.class)
                .withDescription(description)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(new Callable<>() {
                    private int iteration;

                    @Override
                    public String call() {
                        iteration++;
                        if (iteration == 1 || iteration == 3) {
                            throw new RateLimitException(100, "rl");
                        }
                        if (iteration == 2) {
                            throw new RuntimeException("failure");
                        }
                        return "value";
                    }
                });

        assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class); // ignored
        assertThatCode(guarded::call).isExactlyInstanceOf(RuntimeException.class); // failure
        assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class); // ignored
        assertThat(guarded.call()).isEqualTo("value"); // success

        // only the failure and the success are counted; the two ignored invocations are not,
        // so the calls.total sum (2) is smaller than the actual number of invocations (4)
        assertThat(circuitBreakerCalls(description, "failure")).isEqualTo(1.0);
        assertThat(circuitBreakerCalls(description, "success")).isEqualTo(1.0);
        assertThat(circuitBreakerCalls(description, "circuitBreakerOpen")).isZero();
    }
}
