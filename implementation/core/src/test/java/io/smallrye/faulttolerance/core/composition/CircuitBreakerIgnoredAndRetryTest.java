package io.smallrye.faulttolerance.core.composition;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.Future;
import io.smallrye.faulttolerance.core.circuit.breaker.CircuitBreaker;
import io.smallrye.faulttolerance.core.circuit.breaker.CircuitBreakerEvents;
import io.smallrye.faulttolerance.core.retry.AsyncDelay;
import io.smallrye.faulttolerance.core.retry.Retry;
import io.smallrye.faulttolerance.core.retry.SyncDelay;
import io.smallrye.faulttolerance.core.retry.TestInvocation;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.timer.TestTimer;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;
import io.smallrye.faulttolerance.core.util.SetBasedExceptionDecision;
import io.smallrye.faulttolerance.core.util.SetOfThrowables;

public class CircuitBreakerIgnoredAndRetryTest {
    @Test
    public void ignoredAttemptsAreNotRecordedEvenWhenRetried() {
        // every attempt fails with RateLimitException
        TestInvocation<String> action = TestInvocation.initiallyFailing(4,
                () -> new RateLimitException(100, "rl"), () -> "never reached");

        // the circuit breaker ignores RateLimitException
        SetBasedExceptionDecision decisions = new SetBasedExceptionDecision(
                SetOfThrowables.ALL, SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);
        CircuitBreaker<String> circuitBreaker = new CircuitBreaker<>(action, "circuit breaker",
                decisions, decisions, 1000, 4, 0.5, 2,
                new TestStopwatch(), new TestTimer());

        // retryOn contains RateLimitException, maxRetries = 3 -> 4 attempts total
        Retry<String> retry = new Retry<>(circuitBreaker, "retry",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                3, 0, SyncDelay.NONE, AsyncDelay.NONE,
                new TestStopwatch(), null);

        AtomicInteger successEvents = new AtomicInteger();
        AtomicInteger failureEvents = new AtomicInteger();
        AtomicInteger preventedEvents = new AtomicInteger();
        FaultToleranceContext<String> ctx = sync(null);
        ctx.registerEventHandler(CircuitBreakerEvents.Finished.class, event -> {
            switch (event.result) {
                case SUCCESS -> successEvents.incrementAndGet();
                case FAILURE -> failureEvents.incrementAndGet();
                case PREVENTED -> preventedEvents.incrementAndGet();
            }
        });

        assertThatThrownBy(() -> retry.apply(ctx).awaitBlocking())
                .isExactlyInstanceOf(RateLimitException.class);

        // 1 initial attempt + 3 retries
        assertThat(action.numberOfInvocations()).isEqualTo(4);

        // nothing was recorded in the circuit breaker
        assertThat(successEvents).hasValue(0);
        assertThat(failureEvents).hasValue(0);
        assertThat(preventedEvents).hasValue(0);
        assertThat(circuitBreaker.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
    }

    @Test
    public void nonIgnoredAttemptsAreStillRecordedUnderRetry() {
        // every attempt fails with a plain exception that is a failure for the circuit breaker
        TestInvocation<String> action = TestInvocation.initiallyFailing(4,
                RuntimeException::new, () -> "never reached");

        SetBasedExceptionDecision decisions = new SetBasedExceptionDecision(
                SetOfThrowables.ALL, SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);
        CircuitBreaker<String> circuitBreaker = new CircuitBreaker<>(action, "circuit breaker",
                decisions, decisions, 1000, 4, 0.5, 2,
                new TestStopwatch(), new TestTimer());

        Retry<String> retry = new Retry<>(circuitBreaker, "retry",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                3, 0, SyncDelay.NONE, AsyncDelay.NONE,
                new TestStopwatch(), null);

        CircuitBreakerRecorder<String> recorded = new CircuitBreakerRecorder<>(retry);

        assertThatThrownBy(() -> recorded.apply(sync(null)).awaitBlocking())
                .isExactlyInstanceOf(RuntimeException.class);

        assertThat(action.numberOfInvocations()).isEqualTo(4);
        assertThat(recorded.failureCount.get()).isEqualTo(4);
        assertThat(circuitBreaker.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);
    }

    private static class CircuitBreakerRecorder<V> implements FaultToleranceStrategy<V> {
        private final FaultToleranceStrategy<V> delegate;

        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicInteger preventedCount = new AtomicInteger();

        CircuitBreakerRecorder(FaultToleranceStrategy<V> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Future<V> apply(FaultToleranceContext<V> ctx) {
            ctx.registerEventHandler(CircuitBreakerEvents.Finished.class, event -> {
                switch (event.result) {
                    case SUCCESS -> successCount.incrementAndGet();
                    case FAILURE -> failureCount.incrementAndGet();
                    case PREVENTED -> preventedCount.incrementAndGet();
                }
            });
            return delegate.apply(ctx);
        }
    }
}
