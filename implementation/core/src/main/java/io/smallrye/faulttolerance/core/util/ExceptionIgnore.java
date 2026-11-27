package io.smallrye.faulttolerance.core.util;

/**
 * Determines whether an exception should be <em>ignored</em> by a circuit breaker.
 * An ignored exception is neither a success nor a failure: it is not recorded in the rolling window
 * of a closed circuit breaker and it does not advance the number of consecutive successful probe
 * invocations in a half-open circuit breaker. The original exception is still propagated to the caller.
 * <p>
 * This is intentionally a separate abstraction from {@link ExceptionDecision}, which only distinguishes
 * between expected (success) and unexpected (failure) outcomes and is also used by retry and fallback.
 * Ignoring is an independent third outcome used only by the circuit breaker.
 */
public interface ExceptionIgnore {
    boolean isIgnored(Throwable e);

    ExceptionIgnore NEVER = ignored -> false;
}
