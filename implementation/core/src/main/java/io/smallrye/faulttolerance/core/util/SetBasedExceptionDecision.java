package io.smallrye.faulttolerance.core.util;

import static io.smallrye.faulttolerance.core.util.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class SetBasedExceptionDecision implements ExceptionDecision, ExceptionIgnore {
    // @CircuitBreaker.failOn, @Fallback.applyOn, @Retry.retryOn
    private final SetOfThrowables consideredFailure;
    // @CircuitBreaker.skipOn, @Fallback.skipOn, @Retry.abortOn
    private final SetOfThrowables consideredExpected;
    // circuit breaker "ignore" outcome only; empty for retry and fallback
    private final SetOfThrowables consideredIgnored;

    private final boolean inspectCauseChain;

    private final boolean nonDefaultConsideredFailure;
    private final boolean nonDefaultConsideredExpected;

    public SetBasedExceptionDecision(SetOfThrowables consideredFailure, SetOfThrowables consideredExpected,
            boolean inspectCauseChain) {
        this(consideredFailure, consideredExpected, SetOfThrowables.EMPTY, inspectCauseChain);
    }

    public SetBasedExceptionDecision(SetOfThrowables consideredFailure, SetOfThrowables consideredExpected,
            SetOfThrowables consideredIgnored, boolean inspectCauseChain) {
        this.consideredFailure = checkNotNull(consideredFailure, "Set of considered-failure throwables must be set");
        this.consideredExpected = checkNotNull(consideredExpected, "Set of considered-expected throwables must be set");
        this.consideredIgnored = checkNotNull(consideredIgnored, "Set of considered-ignored throwables must be set");
        this.inspectCauseChain = inspectCauseChain;
        this.nonDefaultConsideredFailure = !consideredFailure.isAll();
        this.nonDefaultConsideredExpected = !consideredExpected.isEmpty();
    }

    public boolean isConsideredExpected(Throwable e) {
        return classify(e) != Outcome.FAILURE;
    }

    @Override
    public boolean isIgnored(Throwable e) {
        return classify(e) == Outcome.IGNORED;
    }

    private enum Outcome {
        SUCCESS,
        FAILURE,
        IGNORED,
    }

    // Two-phase classification:
    //
    // 1. only the exception's own type is inspected. First, an explicit success (skipOn) or failure
    //    (failOn) takes precedence -- the default failOn set (all throwables) is skipped, so that
    //    unknown exceptions may still be found on the cause chain. Only if the exception itself matches
    //    neither skipOn nor failOn, it may be ignored. This way, a failOn type that is a supertype
    //    of an ignored type (e.g. failOn(RuntimeException) together with ignore(RateLimitException),
    //    where RateLimitException is a RuntimeException) still classifies the thrown exception itself
    //    as a failure and is not shadowed by the ignore set.
    // 2. only if phase 1 didn't match, the cause chain is traversed (when enabled) and each cause
    //    is inspected in order: ignored, skipOn, failOn. Ignored wins over expected and failure
    //    within one cause, because the exception itself already matched none of the success/failure sets.
    //
    // The default failOn set matches every throwable, so it is only consulted as a last-resort fallback
    // after the cause chain. This preserves the previous behavior where the default failOn set always
    // indicated a failure, while not shadowing skipOn / ignore matches on the cause chain.
    private Outcome classify(Throwable e) {
        // phase 1: exception's own type; explicit skipOn / failOn take precedence over ignore
        if (nonDefaultConsideredExpected && consideredExpected.includes(e.getClass())) {
            return Outcome.SUCCESS;
        }
        if (nonDefaultConsideredFailure && consideredFailure.includes(e.getClass())) {
            return Outcome.FAILURE;
        }
        if (consideredIgnored.includes(e.getClass())) {
            return Outcome.IGNORED;
        }

        // phase 2: cause chain; ignored takes precedence within each cause
        if (inspectCauseChain) {
            Set<Throwable> alreadySeen = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable cause = e.getCause();
            while (cause != null && !alreadySeen.contains(cause)) {
                alreadySeen.add(cause);

                if (consideredIgnored.includes(cause.getClass())) {
                    return Outcome.IGNORED;
                }
                if (consideredExpected.includes(cause.getClass())) {
                    return Outcome.SUCCESS;
                }
                if (consideredFailure.includes(cause.getClass())) {
                    return Outcome.FAILURE;
                }

                cause = cause.getCause();
            }
        }

        // last resort: the default failOn set (all throwables) means failure,
        // otherwise an unknown exception is considered expected
        return consideredFailure.isAll() ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}
