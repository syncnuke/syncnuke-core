package io.github.syncnuke.client;

import io.github.syncnuke.service.TimingService;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TestableTimingService implements TimingService {

    private final AtomicLong currentTime = new AtomicLong();

    public TestableTimingService() {
        this.currentTime.set(System.currentTimeMillis());
    }

    /**
     * Advances the mock time by the specified number of milliseconds.
     */
    public void advanceTimeBy(long milliseconds) {
        currentTime.addAndGet(milliseconds);
    }

    @Override
    public long getCurrentTime() {
        return currentTime.get();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        return null;
    }

    @Override
    public void shutdown() {

    }

}
