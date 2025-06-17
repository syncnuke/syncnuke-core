package io.github.syncnuke.client;

import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import io.github.syncnuke.client.internal.tcp.Codec;
import io.github.syncnuke.client.internal.tcp.TcpClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public abstract class SyncClient<T> extends TcpClient<T> implements VideoPlayer, VideoPlayerEventListener {

    protected static final int PLAY_STATUS = 1, PAUSE_STATUS = 0;

    @Delegate(types = VideoPlayer.class)
    private final VideoPlayer videoPlayer;

    // Drift tracking
    private volatile int prevStatus = 1; // 1 = playing, 0 = paused
    private volatile double prevProgress = 0;
    private volatile long prevProgTime = System.currentTimeMillis();
    private static final double DRIFT_THRESHOLD = 0.1; // error threshold for drift detection in %
    private static final double MIN_PROG_CHANGE = 0.5; // min progress change in seconds to trigger server notification

    // KeepAlive handling
    private final ScheduledExecutorService keepAliveScheduler;
    private final AtomicLong lastMessageSentTime = new AtomicLong(System.currentTimeMillis());

    protected SyncClient(String host, int port, Codec<T> codec, int keepAliveInterval, VideoPlayer videoPlayer) {
        super(host, port, codec);
        this.videoPlayer = videoPlayer;

        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "keepalive-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        startKeepAliveTask(keepAliveInterval);
    }

    private void startKeepAliveTask(int keepAliveInterval) {
        keepAliveScheduler.scheduleWithFixedDelay(() -> {
            try {
                long timeSinceLastMessage = System.currentTimeMillis() - lastMessageSentTime.get();
                if (timeSinceLastMessage > keepAliveInterval) {
                    log.debug("Sending keepAlive to maintain connection");
                    sendKeepAlive();
                }
            } catch (Exception e) {
                log.error("Error sending keepAlive message", e);
            }
        }, keepAliveInterval, keepAliveInterval, TimeUnit.MILLISECONDS);
    }

    public abstract void login(String username, String room);

    /**
     * Used to filter out video state updates that are too minor to trigger server notifications.
     * @param currentStatus     the status of the video player in number format
     * @param currentProgress   the current progress of the video player
     * @return  {@code true} if the change is significant enough to notify the server, {@code false} otherwise
     */
    protected boolean isSignificantChange(int currentStatus, double currentProgress) {
        if (isStatusChanged(currentStatus)) {
            return true;
        }

        if (Math.abs(currentProgress - prevProgress) <= MIN_PROG_CHANGE) {
            // Progress change is not significant enough
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - prevProgTime;

        boolean paused = currentStatus == PAUSE_STATUS;
        if (paused || timeDiff <= 0 || videoPlayer.getPlaybackSpeed() <= 0) {
            // Return exclusively based on progress change
            return true;
        }

        double expectedAdvance = timeDiff * videoPlayer.getPlaybackSpeed();
        if ((long) expectedAdvance == 0) {
            // Prevent division by zero
            return false;
        }
        double positionDiff = Math.abs(currentProgress - prevProgress) * 1000; // in milliseconds
        double relativeError = Math.abs(positionDiff - expectedAdvance) / expectedAdvance;

        // Progress change does not match expected change based on playback speed
        return relativeError > DRIFT_THRESHOLD;
    }

    private boolean isStatusChanged(int currentStatus) {
        return currentStatus != prevStatus;
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
