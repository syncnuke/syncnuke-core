package io.github.syncnuke.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Manages all sync events and state reads, for concurrency management.
 */
@Slf4j
public final class SyncEventService implements AutoCloseable {

    private final ThreadLocal<Boolean> onEventQueue = ThreadLocal.withInitial(() -> false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sync-events");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;

    public void execute(Runnable event) {
        try {
            executor.execute(() -> handleEvent(event));
        } catch (RejectedExecutionException ignored) {
            // Ignore events submitted after shutdown
        }
    }

    private void handleEvent(Runnable event) {
        onEventQueue.set(true);
        if (closed) {
            return;
        }
        try {
            event.run();
        } catch (Exception e) {
            log.error("Error processing sync event", e);
        }
    }

    public <R> R read(Supplier<R> read) {
        if (onEventQueue.get()) {
            return read.get();
        }
        try {
            return executor.submit(() -> {
                onEventQueue.set(true);
                return read.get();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading sync state", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read sync state", e.getCause());
        }
    }

    @Override
    public void close() {
        if (!onEventQueue.get()) {
            execute(this::close);
            return;
        }
        closed = true;
        executor.shutdown();
    }

}
