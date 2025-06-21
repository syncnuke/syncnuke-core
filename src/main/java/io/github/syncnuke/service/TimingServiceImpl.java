package io.github.syncnuke.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TimingServiceImpl implements TimingService {

    private final ScheduledExecutorService scheduler;

    public TimingServiceImpl() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "timing-service-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        scheduler.shutdownNow();
    }

}
