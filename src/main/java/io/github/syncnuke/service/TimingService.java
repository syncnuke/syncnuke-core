package io.github.syncnuke.service;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public interface TimingService {

    /**
     * Get the current system time in milliseconds.
     * 
     * @return current time in milliseconds
     */
    long getCurrentTime();

    /**
     * Schedule a task to run repeatedly with a fixed delay between executions.
     * 
     * @param task the task to execute
     * @param initialDelay the time to delay first execution
     * @param delay the delay between successive executions
     * @param unit the time unit of the initialDelay and delay parameters
     * @return a ScheduledFuture representing pending completion of the task
     */
    ScheduledFuture<?> schedule(Runnable task, long initialDelay, long delay, TimeUnit unit);

    /**
     * Shutdown all scheduled tasks and release resources.
     */
    void shutdown();

}
