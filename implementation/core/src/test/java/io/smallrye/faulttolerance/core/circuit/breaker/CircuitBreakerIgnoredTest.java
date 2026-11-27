package io.smallrye.faulttolerance.core.circuit.breaker;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.sync;
import static io.smallrye.faulttolerance.core.Invocation.invocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.timer.TestTimer;
import io.smallrye.faulttolerance.core.util.ExceptionIgnore;
import io.smallrye.faulttolerance.core.util.PredicateBasedExceptionIgnore;
import io.smallrye.faulttolerance.core.util.SetBasedExceptionDecision;
import io.smallrye.faulttolerance.core.util.SetOfThrowables;
import io.smallrye.faulttolerance.core.util.TestException;

public class CircuitBreakerIgnoredTest {
    private TestStopwatch stopwatch;
    private TestTimer timer;

    // default failOn (Throwable), ignore RateLimitException, cause chain inspected
    private SetBasedExceptionDecision decisions;

    @BeforeEach
    public void setUp() {
        stopwatch = new TestStopwatch();
        timer = new TestTimer();
        decisions = new SetBasedExceptionDecision(SetOfThrowables.ALL, SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);
    }

    private CircuitBreaker<String> newCircuitBreaker(
            io.smallrye.faulttolerance.core.FaultToleranceStrategy<String> delegate) {
        return new CircuitBreaker<>(delegate, "test invocation",
                decisions, decisions, 1000, 4, 0.5, 2, stopwatch, timer);
    }

    @Test
    public void ignoredInvocationsAreNotRecordedInRollingWindow() throws Throwable {
        CircuitBreaker<String> cb = newCircuitBreaker(invocation());

        // 1. TestException -> failure
        assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                .isExactlyInstanceOf(TestException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // 2. RateLimitException -> ignored, not recorded
        RateLimitException firstRateLimit = new RateLimitException(100, "rl");
        assertThatThrownBy(cb.apply(sync(() -> {
            throw firstRateLimit;
        }))::awaitBlocking).isSameAs(firstRateLimit);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // 3. TestException -> failure (window: 2 records)
        assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                .isExactlyInstanceOf(TestException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // 4. RateLimitException -> ignored, not recorded
        assertThatThrownBy(cb.apply(sync(() -> {
            throw new RateLimitException(100, "rl");
        }))::awaitBlocking).isExactlyInstanceOf(RateLimitException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // 5. normal return -> success (window now has 3 records: failure, failure, success)
        assertThat(cb.apply(sync(() -> "value")).awaitBlocking()).isEqualTo("value");
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // 6. TestException -> failure; window fills with 4 records: failure, failure, success, failure
        //    3 failures out of 4 >= threshold 2, so the circuit breaker opens
        assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                .isExactlyInstanceOf(TestException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);
    }

    @Test
    public void ignoredInvocationFiresNoFinishedEventAndNoSuccessOrFailureCallback() throws Throwable {
        CircuitBreaker<String> cb = newCircuitBreaker(invocation());

        AtomicInteger successEvents = new AtomicInteger();
        AtomicInteger failureEvents = new AtomicInteger();
        AtomicInteger preventedEvents = new AtomicInteger();

        FaultToleranceContext<String> ctx = sync(() -> {
            throw new RateLimitException(100, "rl");
        });
        ctx.registerEventHandler(CircuitBreakerEvents.Finished.class, event -> {
            switch (event.result) {
                case SUCCESS -> successEvents.incrementAndGet();
                case FAILURE -> failureEvents.incrementAndGet();
                case PREVENTED -> preventedEvents.incrementAndGet();
            }
        });

        assertThatThrownBy(cb.apply(ctx)::awaitBlocking)
                .isExactlyInstanceOf(RateLimitException.class);

        assertThat(successEvents).hasValue(0);
        assertThat(failureEvents).hasValue(0);
        assertThat(preventedEvents).hasValue(0);
    }

    @Test
    public void originalIgnoredExceptionIsPropagatedWithoutWrapping() throws Throwable {
        CircuitBreaker<String> cb = newCircuitBreaker(invocation());

        RateLimitException exception = new RateLimitException(100, "rl");
        assertThatThrownBy(cb.apply(sync(() -> {
            throw exception;
        }))::awaitBlocking).isSameAs(exception);
    }

    @Test
    public void throwingIgnorePredicateFallsBackToSuccessFailureDecision() throws Throwable {
        // the ignore predicate throws for a TestException; the exception must then be treated
        // as a failure (default failOn), recorded exactly once, and propagated unchanged
        ExceptionIgnore brokenIgnore = new PredicateBasedExceptionIgnore(e -> {
            if (e instanceof TestException) {
                throw new IllegalStateException("ignore decision broken");
            }
            return e instanceof RateLimitException;
        });

        CircuitBreaker<String> cb = new CircuitBreaker<>(invocation(), "test invocation",
                decisions, brokenIgnore, 1000, 4, 0.5, 2, stopwatch, timer);

        TestException testException = new TestException();
        assertThatThrownBy(cb.apply(sync(() -> {
            throw testException;
        }))::awaitBlocking).isSameAs(testException);

        // one failure recorded, so one more failure than a fully ignored window would contain
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // fill the window: the first failure above plus three more failures open the circuit breaker
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                    .isExactlyInstanceOf(TestException.class);
        }
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);

        // a predicate that doesn't throw still ignores normally
        RateLimitException rateLimitException = new RateLimitException(100, "rl");
        stopwatch.setCurrentValue(0);
        assertThatThrownBy(cb.apply(sync(() -> {
            throw rateLimitException;
        }))::awaitBlocking)
                .isExactlyInstanceOf(CircuitBreakerOpenException.class); // still open, ignored never runs
    }

    @Test
    public void ignoredExceptionOnCauseChainIsIgnored() throws Throwable {
        CircuitBreaker<String> cb = newCircuitBreaker(invocation());

        // the exception itself matches nothing non-default; its cause is the ignored RateLimitException
        RuntimeException wrapped = new RuntimeException("wrapper", new RateLimitException(100, "rl"));
        assertThatThrownBy(cb.apply(sync(() -> {
            throw wrapped;
        }))::awaitBlocking).isSameAs(wrapped);

        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // only ignored exceptions so far: a single failure must not open a 4-slot window
        assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                .isExactlyInstanceOf(TestException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
    }

    @Test
    public void knownFailureOnExceptionItselfWinsOverIgnoredCause() throws Throwable {
        // explicit failOn(IllegalStateException) and ignore(RateLimitException), as in the acceptance inputs
        SetBasedExceptionDecision explicitDecisions = new SetBasedExceptionDecision(
                SetOfThrowables.create(IllegalStateException.class), SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);
        CircuitBreaker<String> cb = new CircuitBreaker<>(invocation(), "test invocation",
                explicitDecisions, explicitDecisions, 1000, 4, 0.5, 2, stopwatch, timer);

        IllegalStateException failure = new IllegalStateException("x",
                new RateLimitException(100, "rl"));
        assertThatThrownBy(cb.apply(sync(() -> {
            throw failure;
        }))::awaitBlocking).isSameAs(failure);

        // 4 such failures must open the circuit breaker, proving they are recorded as failures
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(cb.apply(sync(() -> {
                throw new IllegalStateException("x", new RateLimitException(100, "rl"));
            }))::awaitBlocking).isExactlyInstanceOf(IllegalStateException.class);
        }
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);

        // a wrapped RateLimitException whose own type matches no failOn type is ignored instead
        cb.reset();
        RuntimeException ignored = new RuntimeException("y", new RateLimitException(100, "rl"));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(cb.apply(sync(() -> {
                throw ignored;
            }))::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);
        }
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
    }

    @Test
    public void throwingIgnoreDecisionOnSynchronousPathRecordsExactlyOneFailure() throws Throwable {
        // the ignore decision throws for a TestException; following the "not ignored" fallback,
        // the synchronous invocation must still produce exactly one failure record and the
        // original TestException instance (no double recording through the outer catch)
        ExceptionIgnore brokenIgnore = e -> {
            throw new IllegalStateException("ignore decision broken");
        };

        AtomicInteger failureEvents = new AtomicInteger();
        AtomicInteger successEvents = new AtomicInteger();

        CircuitBreaker<String> cb = new CircuitBreaker<>(invocation(), "test invocation",
                decisions, brokenIgnore, 1000, 4, 0.5, 2, stopwatch, timer);

        TestException testException = new TestException();
        FaultToleranceContext<String> ctx = sync(() -> {
            throw testException;
        });
        ctx.registerEventHandler(CircuitBreakerEvents.Finished.class, event -> {
            if (event == CircuitBreakerEvents.Finished.FAILURE) {
                failureEvents.incrementAndGet();
            } else if (event == CircuitBreakerEvents.Finished.SUCCESS) {
                successEvents.incrementAndGet();
            }
        });

        assertThatThrownBy(cb.apply(ctx)::awaitBlocking).isSameAs(testException);

        assertThat(failureEvents).hasValue(1);
        assertThat(successEvents).hasValue(0);

        // the single failure didn't fill the window of size 4, so the breaker stays closed
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
    }

    @Test
    public void withoutIgnoreDecisionBehaviorIsUnchanged() throws Throwable {
        // same configuration as the existing circuit breaker tests, using the 9-arg constructor
        SetOfThrowables testException = SetOfThrowables.create(TestException.class);
        CircuitBreaker<String> cb = new CircuitBreaker<>(invocation(), "test invocation",
                new SetBasedExceptionDecision(testException, SetOfThrowables.EMPTY, false),
                1000, 4, 0.5, 2, stopwatch, timer);

        assertThatThrownBy(cb.apply(sync(() -> {
            throw new RateLimitException(100, "rl");
        }))::awaitBlocking).isExactlyInstanceOf(RateLimitException.class); // treated as success

        // three TestException failures fill the remaining window together with the success above
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(cb.apply(sync(TestException::doThrow))::awaitBlocking)
                    .isExactlyInstanceOf(TestException.class);
        }
        // 3 failures of 4 records >= 2 opens the breaker
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);
    }
}
