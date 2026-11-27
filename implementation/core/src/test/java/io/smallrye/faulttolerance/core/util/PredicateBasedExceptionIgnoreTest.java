package io.smallrye.faulttolerance.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class PredicateBasedExceptionIgnoreTest {
    @Test
    public void neverIgnored() {
        ExceptionIgnore neverIgnored = new PredicateBasedExceptionIgnore(ignored -> false);
        assertThat(neverIgnored.isIgnored(new Exception())).isFalse();
    }

    @Test
    public void alwaysIgnored() {
        ExceptionIgnore alwaysIgnored = new PredicateBasedExceptionIgnore(ignored -> true);
        assertThat(alwaysIgnored.isIgnored(new Exception())).isTrue();
    }

    @Test
    public void ignoredByType() {
        ExceptionIgnore ignore = new PredicateBasedExceptionIgnore(e -> e instanceof TestException);

        assertThat(ignore.isIgnored(new TestException())).isTrue();
        assertThat(ignore.isIgnored(new RuntimeException())).isFalse();
    }

    @Test
    public void ignoredByCause() {
        ExceptionIgnore ignore = new PredicateBasedExceptionIgnore(
                e -> e.getCause() instanceof TestException);

        assertThat(ignore.isIgnored(new Exception(new TestException()))).isTrue();
        assertThat(ignore.isIgnored(new Exception())).isFalse();
    }

    @Test
    public void neverConstant() {
        assertThat(ExceptionIgnore.NEVER.isIgnored(new Exception())).isFalse();
        assertThat(ExceptionIgnore.NEVER.isIgnored(new TestException())).isFalse();
    }
}
