package io.github.syncnuke.client;

import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import io.github.syncnuke.tcp.Codec;
import io.github.syncnuke.tcp.TcpClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public abstract class SyncClient<T> extends TcpClient<T> implements VideoPlayer, VideoPlayerEventListener {

    @Delegate(types = VideoPlayer.class)
    private final VideoPlayer videoPlayer;

    // Drift tracking
    private volatile int prevStatus = 1; // 1 = playing, 0 = paused
    private volatile double prevProgress = 0;
    private volatile long prevProgTime = System.currentTimeMillis();
    
    // KeepAlive handling
    private final ScheduledExecutorService keepAliveScheduler;
    private final AtomicLong lastMessageSentTime = new AtomicLong(System.currentTimeMillis());
    private static final long KEEP_ALIVE_INTERVAL = 10000; // in milliseconds

    protected SyncClient(String host, int port, Codec<T> codec, VideoPlayer videoPlayer) {
        super(host, port, codec);
        this.videoPlayer = videoPlayer;

        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "keepalive-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        startKeepAliveTask();
    }

    private void startKeepAliveTask() {
        keepAliveScheduler.scheduleWithFixedDelay(() -> {
            try {
                // Only send a keepAlive if there hasn't been a message sent recently
                long timeSinceLastMessage = System.currentTimeMillis() - lastMessageSentTime.get();
                if (timeSinceLastMessage > KEEP_ALIVE_INTERVAL / 2) {
                    log.debug("Sending keepAlive to maintain connection");
                    sendKeepAlive();
                }
            } catch (Exception e) {
                log.error("Error sending keepAlive message", e);
            }
        }, KEEP_ALIVE_INTERVAL / 2, KEEP_ALIVE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public abstract void login(String username, String room);

    /**
     * Used to filter out video state updates that are too minor to trigger server notifications.
     * @param currentStatus     the status of the video player in number format
     * @param currentProgress   the current progress of the video player
     * @return  {@code true} if the change is significant enough to notify the server, {@code false} otherwise
     */
    protected boolean isSignificantChange(int currentStatus, double currentProgress) {
        long currentTime = System.currentTimeMillis();
        double positionDiff = Math.abs(currentProgress - prevProgress);
        double timeDiff = (currentTime - prevProgTime) / 1000.0;
        return currentStatus != prevStatus || positionDiff > 1 || timeDiff > 1;
    }

    protected void updateTracking(int currentStatus, double currentProgress) {
        this.prevStatus = currentStatus;
        updateTracking(currentProgress);
    }

    protected void updateTracking(double currentProgress) {
        this.prevProgress = currentProgress;
        this.prevProgTime = System.currentTimeMillis();
    }

    protected abstract void sendKeepAlive();

    /**
     * Updates the timestamp of the last message sent.
     * This should be called after any message is sent to prevent unnecessary keepAlive messages.
     */
    protected void updateLastMessageSentTime() {
        lastMessageSentTime.set(System.currentTimeMillis());
    }

    @Override
    public void close() {
        if (keepAliveScheduler != null) {
            keepAliveScheduler.shutdownNow();
        }
        super.close();
        try {
            videoPlayer.close();
        } catch (Exception e) {
            log.warn("Failed to close VideoPlayer: {}", e.getMessage());
        }
    }

}
