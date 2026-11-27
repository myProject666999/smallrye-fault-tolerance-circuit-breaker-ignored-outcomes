package io.smallrye.faulttolerance.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.api.RateLimitException;

public class SetBasedExceptionDecisionTest {
    @Test
    public void consideredFailure() {
        ExceptionDecision alwaysFailure = new SetBasedExceptionDecision(SetOfThrowables.ALL, SetOfThrowables.EMPTY, false);
        assertThat(alwaysFailure.isConsideredExpected(new Exception())).isFalse();
    }

    @Test
    public void consideredExpected() {
        ExceptionDecision alwaysExpected = new SetBasedExceptionDecision(SetOfThrowables.EMPTY, SetOfThrowables.ALL, false);
        assertThat(alwaysExpected.isConsideredExpected(new Exception())).isTrue();
    }

    @Test
    public void unknown() {
        ExceptionDecision empty = new SetBasedExceptionDecision(SetOfThrowables.EMPTY, SetOfThrowables.EMPTY, false);
        assertThat(empty.isConsideredExpected(new Exception())).isTrue();
    }

    @Test
    public void causeConsideredFailure() {
        ExceptionDecision decision = new SetBasedExceptionDecision(SetOfThrowables.create(TestException.class),
                SetOfThrowables.EMPTY, true);

        assertThat(decision.isConsideredExpected(new Exception(new TestException()))).isFalse();
    }

    @Test
    public void causeConsideredExpected() {
        ExceptionDecision decision = new SetBasedExceptionDecision(SetOfThrowables.EMPTY,
                SetOfThrowables.create(TestException.class), true);

        assertThat(decision.isConsideredExpected(new Exception(new TestException()))).isTrue();
    }

    @Test
    public void causeUnknown() {
        ExceptionDecision decision = new SetBasedExceptionDecision(SetOfThrowables.EMPTY, SetOfThrowables.EMPTY, true);

        assertThat(decision.isConsideredExpected(new Exception(new TestException()))).isTrue();
    }

    @Test
    public void ignoredOnExceptionItselfBeforeCauseChainFailure() {
        // failOn(IllegalStateException) and ignore(RateLimitException), cause chain inspected
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.create(IllegalStateException.class), SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);

        // the exception's own type is a known failure: failure, even though its cause is ignored
        IllegalStateException exception = new IllegalStateException("x",
                new RateLimitException(100, "rl"));
        assertThat(decision.isConsideredExpected(exception)).isFalse();
        assertThat(decision.isIgnored(exception)).isFalse();
    }

    @Test
    public void ignoredOnCauseChainWhenExceptionItselfIsUnknown() {
        // failOn(IllegalStateException) and ignore(RateLimitException), cause chain inspected
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.create(IllegalStateException.class), SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), true);

        // the exception's own type matches nothing, the cause is ignored: ignored wins on the cause chain
        RuntimeException exception = new RuntimeException("y",
                new RateLimitException(100, "rl"));
        assertThat(decision.isIgnored(exception)).isTrue();
        assertThat(decision.isConsideredExpected(exception)).isTrue();
    }

    @Test
    public void explicitFailureOnExceptionItselfWinsOverIgnore() {
        // the same type is both failure and ignored; on the exception itself, failOn takes precedence
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.create(TestException.class), SetOfThrowables.EMPTY,
                SetOfThrowables.create(TestException.class), true);

        TestException exception = new TestException();
        assertThat(decision.isIgnored(exception)).isFalse();
        assertThat(decision.isConsideredExpected(exception)).isFalse();
    }

    @Test
    public void ignoreWinsWithinOneCauseOnTheCauseChain() {
        // the same type is both failure and ignored; on the cause chain, ignore takes precedence
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.create(TestException.class), SetOfThrowables.EMPTY,
                SetOfThrowables.create(TestException.class), true);

        Exception wrapped = new Exception(new TestException());
        assertThat(decision.isIgnored(wrapped)).isTrue();
        assertThat(decision.isConsideredExpected(wrapped)).isTrue();
    }

    @Test
    public void ignoredWithoutCauseChainInspection() {
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.ALL, SetOfThrowables.EMPTY,
                SetOfThrowables.create(RateLimitException.class), false);

        RateLimitException ignored = new RateLimitException(100, "rl");
        assertThat(decision.isIgnored(ignored)).isTrue();
        assertThat(decision.isConsideredExpected(ignored)).isTrue();

        // only the exception's own type is inspected without cause chain
        assertThat(decision.isIgnored(new RuntimeException(ignored))).isFalse();
    }

    @Test
    public void emptyIgnoreSetNeverIgnores() {
        SetBasedExceptionDecision decision = new SetBasedExceptionDecision(
                SetOfThrowables.ALL, SetOfThrowables.EMPTY, SetOfThrowables.EMPTY, true);

        assertThat(decision.isIgnored(new TestException())).isFalse();
        assertThat(decision.isConsideredExpected(new TestException())).isFalse();
    }
}
