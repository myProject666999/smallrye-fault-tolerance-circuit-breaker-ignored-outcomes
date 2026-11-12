package io.smallrye.faulttolerance.mutiny.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.Guard;
import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.core.util.TestException;
import io.smallrye.faulttolerance.core.util.party.Party;
import io.smallrye.mutiny.Uni;

public class MutinyUntypedGuardTest {
    private ExecutorService executor;

    @BeforeEach
    public void setUp() {
        executor = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    public void bulkhead() throws Exception {
        Guard guard = Guard.create()
                .withBulkhead().limit(6).queueSize(2).done()
                .withThreadOffload(true)
                .build();

        Party party = Party.create(6);

        for (int i = 0; i < 4; i++) {
            guard.call(() -> {
                party.participant().attend();
                return Uni.createFrom().item("ignored");
            }, Types.UNI_STRING).subscribeAsCompletionStage();
            guard.call(() -> {
                party.participant().attend();
                return Uni.createFrom().item(42);
            }, Types.UNI_INTEGER).subscribeAsCompletionStage();
        }

        party.organizer().waitForAll();

        assertThat(guard.call(() -> Uni.createFrom().item("value"), Types.UNI_STRING).subscribeAsCompletionStage())
                .failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class) // caused by AssertJ calling future.get()
                .withCauseExactlyInstanceOf(BulkheadException.class);

        party.organizer().disband();
    }

    @Test
    public void circuitBreaker() throws Exception {
        Guard guard = Guard.create()
                .withCircuitBreaker().requestVolumeThreshold(6).done()
                .build();

        for (int i = 0; i < 3; i++) {
            assertThat(guard.call(this::stringAction, Types.UNI_STRING).subscribeAsCompletionStage())
                    .failsWithin(10, TimeUnit.SECONDS)
                    .withThrowableOfType(ExecutionException.class) // caused by AssertJ calling future.get()
                    .withCauseExactlyInstanceOf(TestException.class);

            assertThat(guard.call(this::integerAction, Types.UNI_INTEGER).subscribeAsCompletionStage())
                    .failsWithin(10, TimeUnit.SECONDS)
                    .withThrowableOfType(ExecutionException.class) // caused by AssertJ calling future.get()
                    .withCauseExactlyInstanceOf(TestException.class);
        }

        assertThat(guard.call(this::stringAction, Types.UNI_STRING).subscribeAsCompletionStage())
                .failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class) // caused by AssertJ calling future.get()
                .withCauseExactlyInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    public void rateLimit() throws Exception {
        Guard guard = Guard.create()
                .withRateLimit().limit(6).window(1, ChronoUnit.MINUTES).done()
                .withThreadOffload(true)
                .build();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> {
                return guard.call(() -> Uni.createFrom().item("ignored"), Types.UNI_STRING).await().indefinitely();
            }));
            futures.add(executor.submit(() -> {
                return guard.call(() -> Uni.createFrom().item(42), Types.UNI_INTEGER).await().indefinitely();
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        assertThat(guard.call(() -> Uni.createFrom().item("value"), Types.UNI_STRING).subscribeAsCompletionStage())
                .failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class) // caused by AssertJ calling future.get()
                .withCauseExactlyInstanceOf(RateLimitException.class);
    }

    public Uni<String> stringAction() {
        return Uni.createFrom().failure(new TestException());
    }

    public Uni<Integer> integerAction() {
        return Uni.createFrom().failure(new TestException());
    }
}
