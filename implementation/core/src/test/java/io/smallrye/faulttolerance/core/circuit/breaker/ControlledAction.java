package io.smallrye.faulttolerance.core.circuit.breaker;

import java.util.ArrayList;
import java.util.List;

import io.smallrye.faulttolerance.core.Completer;
import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.Future;

/**
 * A guarded action whose returned futures are completed explicitly by the test, without any thread pool
 * or sleeping. Each invocation captures a fresh {@link Completer}; all outstanding completers are
 * retained in invocation order, so the test can complete any probe out of order.
 */
final class ControlledAction<V> implements FaultToleranceStrategy<V> {
    private final List<Completer<V>> probes = new ArrayList<>();

    private int invocationCount;

    @Override
    public Future<V> apply(FaultToleranceContext<V> ctx) {
        invocationCount++;
        Completer<V> completer = Completer.create();
        probes.add(completer);
        return completer.future();
    }

    int invocationCount() {
        return invocationCount;
    }

    /**
     * Number of probes that were started but not yet completed.
     */
    int pending() {
        int result = 0;
        for (Completer<V> probe : probes) {
            if (!probe.future().isComplete()) {
                result++;
            }
        }
        return result;
    }

    /**
     * Returns the completer of the most recently started probe, without completing it.
     */
    Completer<V> latestProbe() {
        return probes.get(probes.size() - 1);
    }

    /**
     * Completes the oldest not-yet-completed probe successfully.
     */
    void succeed(V value) {
        firstPending().complete(value);
    }

    /**
     * Completes the oldest not-yet-completed probe with an error.
     */
    void fail(Throwable error) {
        firstPending().completeWithError(error);
    }

    /**
     * Completes a specific probe with an error, regardless of its invocation order.
     */
    void fail(Completer<V> probe, Throwable error) {
        probe.completeWithError(error);
    }

    private Completer<V> firstPending() {
        for (Completer<V> probe : probes) {
            if (!probe.future().isComplete()) {
                return probe;
            }
        }
        throw new IllegalStateException("No pending probe");
    }
}
