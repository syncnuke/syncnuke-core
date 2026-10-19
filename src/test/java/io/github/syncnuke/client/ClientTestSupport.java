package io.github.syncnuke.client;

import io.github.syncnuke.service.SyncEventService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ClientTestSupport {
    private ClientTestSupport() {
    }

    public static ExecutorService executor(SyncClient<?> client, String name) {
        try {
            if ("eventQueue".equals(name)) {
                Field serviceField = SyncClient.class.getDeclaredField("eventService");
                serviceField.setAccessible(true);
                Field executorField = SyncEventService.class.getDeclaredField("executor");
                executorField.setAccessible(true);
                return (ExecutorService) executorField.get(serviceField.get(client));
            }
            Field field = SyncClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return (ExecutorService) field.get(client);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static void runEvent(SyncClient<?> client, Runnable event) {
        try {
            Method method = SyncClient.class.getDeclaredMethod("executeEvent", Runnable.class);
            method.setAccessible(true);
            method.invoke(client, event);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static void awaitIdle(SyncClient<?> client) {
        // I/O may enqueue callbacks; drain again after each worker barrier.
        for (int pass = 0; pass < 3; pass++) {
            for (String name : new String[]{"eventQueue", "networkExecutor", "playerExecutor"}) {
                ExecutorService executor = executor(client, name);
                try {
                    executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
                } catch (RejectedExecutionException e) {
                    try {
                        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                            throw new AssertionError(name + " did not terminate");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                } catch (TimeoutException e) {
                    // shutdownNow can discard the submitted barrier task.
                    if (!executor.isTerminated()) {
                        throw new AssertionError("Failed to drain " + name, e);
                    }
                } catch (Exception e) {
                    throw new AssertionError("Failed to drain " + name, e);
                }
            }
        }
    }
}
