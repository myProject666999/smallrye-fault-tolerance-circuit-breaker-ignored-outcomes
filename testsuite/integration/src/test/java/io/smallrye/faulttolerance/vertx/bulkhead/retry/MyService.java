package io.smallrye.faulttolerance.vertx.bulkhead.retry;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Retry;

import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import io.smallrye.faulttolerance.vertx.ContextDescription;
import io.smallrye.faulttolerance.vertx.VertxContext;

@ApplicationScoped
public class MyService {
    static final Queue<ContextDescription> currentContexts = new ConcurrentLinkedQueue<>();

    @AsynchronousNonBlocking
    @Bulkhead(value = 3, waitingTaskQueue = 3)
    @Retry(maxRetries = 1, delay = 1000, jitter = 0)
    public CompletionStage<String> hello() {
        currentContexts.add(VertxContext.current().describe());

        // Note that the Vert.x timer is rather inaccurate. If the retry delay (as defined above)
        // is close to the completion delay (as defined below), this test may fail spuriously.
        // The underlying reason in such case is that the completion is executed _later_ than
        // the retry, which violates the basic assumption of this test.

        CompletableFuture<String> result = new CompletableFuture<>();
        VertxContext.current().setTimer(200, () -> {
            currentContexts.add(VertxContext.current().describe());

            result.complete("Hello!");
        });
        return result;
    }
}
