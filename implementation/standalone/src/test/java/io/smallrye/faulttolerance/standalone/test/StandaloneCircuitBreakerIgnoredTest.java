package io.smallrye.faulttolerance.standalone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.Guard;
import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.api.TypedGuard;
import io.smallrye.faulttolerance.core.util.TestException;

public class StandaloneCircuitBreakerIgnoredTest {
    @BeforeEach
    public void setUp() {
        CircuitBreakerMaintenance.get().resetAll();
    }

    @Test
    public void ignoreOnKeepsCircuitBreakerClosedForRejections() throws Exception {
        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .failureRatio(0.5)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(this::rateLimitedAction);

        // four rejections would open the circuit breaker if they were counted as failures
        for (int i = 0; i < 4; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }

        // a genuine failure and a success still work, the circuit breaker never opened
        assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        Callable<String> successful = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(() -> "value");
        assertThat(successful.call()).isEqualTo("value");
    }

    @Test
    public void ignoreWhenCoexistsWithWhen() throws Exception {
        // when() decides success vs failure, ignoreWhen() is an independent third dimension;
        // the action throws a RateLimitException while a switch is on, and a TestException otherwise
        AtomicBoolean rateLimited = new AtomicBoolean(true);

        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .when(e -> e instanceof TestException)
                .ignoreWhen(e -> e instanceof RateLimitException)
                .done()
                .build()
                .adaptCallable(() -> {
                    if (rateLimited.get()) {
                        throw new RateLimitException(100, "rl");
                    }
                    throw new TestException();
                });

        // four ignored rejections leave the rolling window completely empty
        for (int i = 0; i < 4; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }

        // switch to genuine failures: the first three must not open the circuit breaker,
        // because the rolling window of size 4 is not full yet
        rateLimited.set(false);
        for (int i = 0; i < 3; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(TestException.class);
        }
        assertThatCode(guarded::call).isExactlyInstanceOf(TestException.class);
    }

    @Test
    public void ignoreOnCoexistsWithWhen() {
        // the set-based ignore dimension and the predicate-based when() dimension are independent,
        // so building this guard must not throw
        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .when(e -> e instanceof TestException)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(this::rateLimitedAction);

        for (int i = 0; i < 4; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }
    }

    @Test
    public void ignoreWhenAndIgnoreOnAreMutuallyExclusive() {
        assertThatThrownBy(() -> TypedGuard.create(String.class)
                .withCircuitBreaker()
                .ignoreOn(RateLimitException.class)
                .ignoreWhen(e -> e instanceof RateLimitException)
                .done()
                .build())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ignoreWhen");
    }

    @Test
    public void untypedAndTypedBuildersShareTheSameSemantics() {
        Guard guard = Guard.create()
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .build();

        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> guard.call(this::rateLimitedCall, String.class))
                    .isExactlyInstanceOf(RateLimitException.class);
        }
    }

    @Test
    public void ignoredInvocationsDoNotTriggerSuccessFailureOrPreventedCallbacks() throws Exception {
        AtomicInteger onSuccess = new AtomicInteger();
        AtomicInteger onFailure = new AtomicInteger();
        AtomicInteger onPrevented = new AtomicInteger();

        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .onSuccess(onSuccess::incrementAndGet)
                .onFailure(onFailure::incrementAndGet)
                .onPrevented(onPrevented::incrementAndGet)
                .done()
                .build()
                .adaptCallable(this::rateLimitedAction);

        for (int i = 0; i < 4; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }

        assertThat(onSuccess).hasValue(0);
        assertThat(onFailure).hasValue(0);
        assertThat(onPrevented).hasValue(0);
    }

    @Test
    public void ignoredExceptionIsPropagatedUnchanged() {
        RateLimitException exception = new RateLimitException(100, "rl");

        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .build()
                .adaptCallable(() -> {
                    throw exception;
                });

        assertThatThrownBy(guarded::call).isSameAs(exception);
    }

    @Test
    public void rateLimitRejectionDoesNotOpenCircuitBreakerAroundGuardedMethod() {
        // rate limit 1 per minute outside, circuit breaker inside: rejected calls never reach
        // the guarded method and must be invisible to the circuit breaker
        Callable<String> guarded = TypedGuard.create(String.class)
                .withCircuitBreaker()
                .requestVolumeThreshold(4)
                .ignoreOn(RateLimitException.class)
                .done()
                .withRateLimit().limit(1).window(1, java.time.temporal.ChronoUnit.MINUTES).done()
                .build()
                .adaptCallable(() -> "value");

        assertThatCode(guarded::call).doesNotThrowAnyException();
        for (int i = 0; i < 5; i++) {
            assertThatCode(guarded::call).isExactlyInstanceOf(RateLimitException.class);
        }

        // the circuit breaker is still closed; only the rate limit rejects, not the circuit breaker
        assertThatThrownBy(guarded::call)
                .isExactlyInstanceOf(RateLimitException.class)
                .isNotInstanceOf(CircuitBreakerOpenException.class);
    }

    private String rateLimitedCall() {
        throw new RateLimitException(100, "rl");
    }

    private String rateLimitedAction() {
        throw new RateLimitException(100, "rl");
    }
}
