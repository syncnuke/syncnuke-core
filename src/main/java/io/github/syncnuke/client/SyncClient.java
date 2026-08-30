package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class implementation for video synchronization clients. Responsibilities include:
 * <ul>
 *   <li>Playback change detection to prevent unnecessary updates</li>
 *   <li>Centralized access to the internally managed video player</li>
 *   <li>Setting up a networking client for synchronization</li>
 *   <li>Preventing time-outs through a keep-alive mechanism</li>
 * </ul>
 */
@Slf4j
public abstract class SyncClient<T> implements AutoCloseable {

    @Getter
    private final PlayerManager player;

    private final TimingService timingService;

    private volatile PlayerState serverState;
    private static final double DRIFT_THRESHOLD = 0.1; // error threshold for drift detection in %
    private static final double MIN_PROG_CHANGE = 0.5; // min progress change in seconds to trigger server notification

    // KeepAlive handling
    private ScheduledFuture<?> keepAliveTask;
    private final AtomicLong lastMessageSentTime = new AtomicLong();

    protected SyncClient(int keepAliveInterval, PlayerManager videoPlayer) {
        this(keepAliveInterval, videoPlayer, new TimingServiceImpl());
    }

    protected SyncClient(int keepAliveInterval, PlayerManager videoPlayer, TimingService timingService) {
        this.player = videoPlayer;
        this.timingService = timingService;
        startKeepAliveTask(keepAliveInterval);
    }

    /**
     * Used for instantiating the underlying NetClient.
     */
    protected abstract NetClient<T> getNetClient();

    /**
     * Establishes the connection to be used for synchronization.
     * @param host  the sync server host
     * @param port  the sync server port
     * @param codec the codec to use for encoding/decoding messages
     */
    public void connect(String host, int port, Codec<T> codec) {
        getNetClient().connect(host, port, codec);
        getNetClient().addListener(this::handleResponse);
    }

    private void startKeepAliveTask(int keepAliveInterval) {
        if (keepAliveInterval <= 0) {
            log.debug("KeepAlive interval is set to 0 or negative, turning off keepAlive");
            return;
        }

        keepAliveTask = timingService.schedule(() -> {
            try {
                long timeSinceLastMessage = getCurrentTime() - lastMessageSentTime.get();
                if (timeSinceLastMessage >= keepAliveInterval) {
                    log.debug("Sending keepAlive to maintain connection");
                    sendKeepAlive();
                }
            } catch (Exception e) {
                log.error("Error sending keepAlive message", e);
            }
        }, keepAliveInterval, keepAliveInterval, TimeUnit.MILLISECONDS);
    }

    public abstract void login(String username, String room);

    public abstract void onStatusChange(PlayerState status);

    /**
     * Handles the response received from the server.
     */
    protected abstract void handleResponse(T data);

    protected void send(T data) {
        if (data == null) {
            log.debug("Attempted to send null data, skipping");
            return;
        }
        getNetClient().send(data);
        updateLastMessageSentTime();
    }

    protected abstract void sendKeepAlive();

    /**
     * Used to filter out video state updates that are too minor to trigger server notifications.
     *
     * TODO: Decide whether this policy belongs to the SyncNuke standard or to each protocol implementation.
     *
     * @param localStatus the status reported by the local video player
     * @return  {@code true} if the change is significant enough to notify the server, {@code false} otherwise
     */
    protected boolean isSignificantChange(PlayerState localStatus) {
        Objects.requireNonNull(localStatus, "localStatus");
        if (serverState == null) {
            return true;
        }

        if (localStatus.getPlaybackState() != serverState.getPlaybackState()) {
            // Status changed
            return true;
        }

        if (Math.abs(localStatus.getPosition() - serverState.getPosition()) <= MIN_PROG_CHANGE) {
            // Progress change is not significant enough
            return false;
        }

        long currentTime = getCurrentTime();
        long timeDiff = currentTime - serverState.getLastUpdateTime();

        boolean paused = localStatus.getPlaybackState() == PlaybackState.PAUSED;
        double playbackSpeed = localStatus.getPlaybackSpeed();
        if (paused || timeDiff <= 0 || playbackSpeed <= 0) {
            // Return exclusively based on progress change
            return true;
        }

        double expectedAdvance = timeDiff * playbackSpeed;
        if ((long) expectedAdvance == 0) {
            // Prevent division by zero
            return false;
        }
        double positionDiff = Math.abs(localStatus.getPosition() - serverState.getPosition()) * 1000; // in milliseconds
        double relativeError = Math.abs(positionDiff - expectedAdvance) / expectedAdvance;

        // Progress change does not match expected change based on playback speed
        return relativeError > DRIFT_THRESHOLD;
    }

    /**
     * Records playback state decoded from an inbound server command.
     */
    protected final void updateServerState(PlayerState serverStatus) {
        PlayerState expectation = new PlayerState(Objects.requireNonNull(serverStatus, "serverStatus"));
        expectation.setLastUpdateTime(getCurrentTime());
        serverState = expectation;
    }

    /**
     * Updates the timestamp of the last message sent.
     * This should be called after any message is sent to prevent unnecessary keepAlive messages.
     */
    private void updateLastMessageSentTime() {
        lastMessageSentTime.set(getCurrentTime());
    }

    /**
     * @return The current system time in milliseconds.
     */
    protected long getCurrentTime() {
        return timingService.getCurrentTime();
    }

    @Override
    public void close() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }
        try {
            getNetClient().close();
        } catch (Exception e) {
            log.warn("Failed to close network client: {}", e.getMessage());
        }
    }

}
