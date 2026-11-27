package io.smallrye.faulttolerance.core.util;

import static io.smallrye.faulttolerance.core.util.Preconditions.checkNotNull;

import java.util.function.Predicate;

public class PredicateBasedExceptionIgnore implements ExceptionIgnore {
    private final Predicate<Throwable> isIgnored;

    public PredicateBasedExceptionIgnore(Predicate<Throwable> isIgnored) {
        this.isIgnored = checkNotNull(isIgnored, "Exception predicate must be set");
    }

    @Override
    public boolean isIgnored(Throwable e) {
        return isIgnored.test(e);
    }
}
