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

    public abstract void login(String username, String room, String password);

    public abstract RoomInfo getRoomInfo();

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
