package io.smallrye.faulttolerance.core.circuit.breaker;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.RateLimitException;
import io.smallrye.faulttolerance.core.Completer;
import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.Future;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.timer.TestTimer;
import io.smallrye.faulttolerance.core.util.SetBasedExceptionDecision;
import io.smallrye.faulttolerance.core.util.SetOfThrowables;
import io.smallrye.faulttolerance.core.util.TestException;

/**
 * Half-open probe slot handling with ignored outcomes. The guarded action returns futures completed
 * explicitly by the test (see {@link ControlledAction}), so no thread pool and no sleeping is involved.
 */
public class CircuitBreakerHalfOpenIgnoredTest {
    private TestStopwatch stopwatch;
    private TestTimer timer;
    private ControlledAction<String> action;
    private CircuitBreaker<String> cb;

    private final AtomicInteger preventedEvents = new AtomicInteger();

    @BeforeEach
    public void setUp() {
        stopwatch = new TestStopwatch();
        timer = new TestTimer();
        action = new ControlledAction<>();

        // volume threshold 4, failure ratio 0.5 -> failure threshold 2; success threshold 2;
        // default failOn (Throwable), ignore RateLimitException, cause chain inspected
        SetBasedExceptionDecision decisions = new SetBasedExceptionDecision(
                SetOfThrowables.ALL, SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);
        cb = new CircuitBreaker<>(action, "test invocation",
                decisions, decisions, 1000, 4, 0.5, 2, stopwatch, timer);
        preventedEvents.set(0);
    }

    private Future<String> apply() {
        FaultToleranceContext<String> ctx = sync(() -> null);
        ctx.registerEventHandler(CircuitBreakerEvents.Finished.class, event -> {
            if (event == CircuitBreakerEvents.Finished.PREVENTED) {
                preventedEvents.incrementAndGet();
            }
        });
        return cb.apply(ctx);
    }

    private void openCircuitBreaker() {
        for (int i = 0; i < 4; i++) {
            Future<String> future = apply();
            action.fail(new TestException());
            assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(TestException.class);
        }
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);
    }

    /**
     * Pushes the stopwatch past the delay and makes one call. That call transitions the circuit
     * breaker to half-open and becomes the first probe. The probe's future and its completer
     * (captured before any further invocation) are returned.
     */
    private Probe enterHalfOpen() {
        stopwatch.setCurrentValue(1500);
        Future<String> future = apply();
        Completer<String> completer = action.latestProbe();
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);
        assertThat(future.isComplete()).isFalse();
        return new Probe(future, completer);
    }

    private Probe newProbe() {
        Future<String> future = apply();
        return new Probe(future, action.latestProbe());
    }

    private record Probe(Future<String> future, Completer<String> completer) {
    }

    @Test
    public void ignoredProbeReturnsItsSlotAndTwoSuccessesCloseTheBreaker() throws Throwable {
        openCircuitBreaker();

        int invocationsBeforeHalfOpen = action.invocationCount();
        Probe p1 = enterHalfOpen();
        // the call that triggered the half-open transition is the first in-flight probe (P1)
        assertThat(action.pending()).isEqualTo(1);

        // P2 is the second probe and is allowed
        Probe p2 = newProbe();
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);
        assertThat(action.pending()).isEqualTo(2);

        // P3 exceeds the success threshold and is prevented; its provisional slot is returned
        Future<String> p3 = apply();
        assertThatThrownBy(p3::awaitBlocking).isExactlyInstanceOf(CircuitBreakerOpenException.class);
        assertThat(action.pending()).isEqualTo(2);
        // only P1 and P2 ran the action so far; P3 was prevented
        assertThat(action.invocationCount()).isEqualTo(invocationsBeforeHalfOpen + 2);

        // P1 finishes with an ignored exception: its slot is returned, no consecutive success recorded
        action.fail(p1.completer(), new RateLimitException(100, "rl"));
        assertThatThrownBy(p1.future()::awaitBlocking).isExactlyInstanceOf(RateLimitException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);
        assertThat(action.pending()).isEqualTo(1);

        // P4 reuses the returned slot; in-flight probes peak at 2 (P2 and P4)
        Probe p4 = newProbe();
        assertThat(action.pending()).isEqualTo(2);

        // P2 and P4 succeed: two consecutive successes close the circuit breaker
        p2.completer().complete("p2");
        assertThat(p2.future().awaitBlocking()).isEqualTo("p2");
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);

        p4.completer().complete("p4");
        assertThat(p4.future().awaitBlocking()).isEqualTo("p4");
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);

        // the guarded method ran 3 times in this half-open phase; exactly 1 invocation was prevented
        assertThat(action.invocationCount() - invocationsBeforeHalfOpen).isEqualTo(3);
        assertThat(preventedEvents).hasValue(1);
    }

    @Test
    public void ignoredProbeFromAnOldHalfOpenPhaseDoesNotConsumeSlotsOfTheNewPhase() throws Throwable {
        openCircuitBreaker();

        int invocationsBeforeHalfOpen = action.invocationCount();
        Probe p1 = enterHalfOpen();

        // P2 is the second probe and is allowed
        Probe p2 = newProbe();
        assertThat(action.pending()).isEqualTo(2);

        // P2 fails: the circuit breaker reopens immediately, while P1 is still in flight
        action.fail(p2.completer(), new TestException());
        assertThatThrownBy(p2.future()::awaitBlocking).isExactlyInstanceOf(TestException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_OPEN);

        // push the stopwatch past the delay and enter a new half-open phase: Q1 is allowed
        stopwatch.setCurrentValue(3000);
        Probe q1 = newProbe();
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);

        // only now does the old P1 finish with an ignored exception; its slot was captured in the old
        // state object, so the decrement cannot affect the new half-open phase
        action.fail(p1.completer(), new RateLimitException(100, "rl"));
        assertThatThrownBy(p1.future()::awaitBlocking).isExactlyInstanceOf(RateLimitException.class);
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_HALF_OPEN);

        // Q2 is the second probe of the new phase and is allowed
        Probe q2 = newProbe();
        assertThat(action.pending()).isEqualTo(2);

        // Q3 must be rejected: only 2 probes may be in flight in the new half-open phase
        Future<String> q3 = apply();
        assertThatThrownBy(q3::awaitBlocking).isExactlyInstanceOf(CircuitBreakerOpenException.class);

        // P1, P2 in the first phase and Q1, Q2 in the second: 4 guarded invocations since entry;
        // Q3 did not run the action, and exactly 1 invocation was prevented in the new phase
        assertThat(action.invocationCount() - invocationsBeforeHalfOpen).isEqualTo(4);
        assertThat(preventedEvents).hasValue(1);

        // completing the pending probes does not change the state
        q1.completer().complete("q1");
        q2.completer().complete("q2");
        assertThat(q1.future().awaitBlocking()).isEqualTo("q1");
        assertThat(q2.future().awaitBlocking()).isEqualTo("q2");
        assertThat(cb.currentState()).isEqualTo(CircuitBreaker.STATE_CLOSED);
    }
}
