package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import io.github.syncnuke.service.SyncEventService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Base class implementation for media synchronization clients. Responsibilities include:
 * <ul>
 *   <li>Centralized access to the internally managed media player</li>
 *   <li>Setting up a networking client for synchronization</li>
 *   <li>Preventing time-outs through a keep-alive mechanism</li>
 * </ul>
 */
@Slf4j
public abstract class SyncClient<T> implements AutoCloseable {

    @Getter
    private final PlayerManager player;
    private final TimingService timingService;
    private final int keepAliveInterval;
    private final SyncEventService eventService = new SyncEventService();
    private final ExecutorService networkExecutor = createExecutor("sync-network");
    private final ExecutorService playerExecutor = createExecutor("sync-player");

    private ScheduledFuture<?> keepAliveTask;
    private long lastMessageSentTime;

    private final List<PlayerState> pendingPlayerUpdates = new ArrayList<>();
    private boolean listenerRegistered;

    protected SyncClient(int keepAliveInterval, PlayerManager mediaPlayer) {
        this(keepAliveInterval, mediaPlayer, new TimingServiceImpl());
    }

    protected SyncClient(int keepAliveInterval, PlayerManager mediaPlayer, TimingService timingService) {
        this.player = Objects.requireNonNull(mediaPlayer, "mediaPlayer");
        this.timingService = Objects.requireNonNull(timingService, "timingService");
        this.keepAliveInterval = keepAliveInterval;
    }

    private static ExecutorService createExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    protected final void executeEvent(Runnable event) {
        eventService.execute(event);
    }

    protected final <R> R readState(Supplier<R> read) {
        return eventService.read(read);
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
    public final void connect(String host, int port, Codec<T> codec) {
        executeEvent(() -> {
            if (!listenerRegistered) {
                listenerRegistered = true;
                executeNetwork(() -> getNetClient().addListener(this::onResponse));
                startKeepAliveTask();
            }
            reconnect(host, port, codec);
        });
    }

    private void onResponse(T data) {
        executeEvent(() -> handleResponse(data));
    }

    protected final void reconnect(String host, int port, Codec<T> codec) {
        executeNetwork(() -> getNetClient().connect(host, port, codec));
    }

    private void startKeepAliveTask() {
        if (keepAliveInterval <= 0) {
            return;
        }
        keepAliveTask = timingService.schedule(() -> executeEvent(() -> {
            if (getCurrentTime() - lastMessageSentTime >= keepAliveInterval) {
                sendKeepAlive();
            }
        }), keepAliveInterval, keepAliveInterval, TimeUnit.MILLISECONDS);
    }

    public abstract void login(String username, String room, String password);

    public abstract RoomInfo getRoomInfo();

    public abstract void onStatusChange(PlayerState status);

    /**
     * Handles the response received from the server.
     */
    protected abstract void handleResponse(T data);

    protected final void send(T data) {
        if (data == null) {
            return;
        }
        executeNetwork(() -> getNetClient().send(data));
        lastMessageSentTime = getCurrentTime();
    }

    private void executeNetwork(Runnable operation) {
        networkExecutor.execute(() -> {
            try {
                operation.run();
            } catch (Exception e) {
                log.error("Sync network operation failed", e);
                // A failed send invalidates the session's server expectation.
                // Close on the queue rather than rolling back newer events.
                close();
            }
        });
    }

    protected final PlayerState getPlayerStatus() {
        Future<PlayerState> read = playerExecutor.submit(player::getStatus);
        try {
            return new PlayerState(read.get(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading player status", e);
        } catch (ExecutionException | TimeoutException e) {
            read.cancel(true);
            throw new IllegalStateException("Failed to read live player status", e);
        }
    }

    protected final boolean hasPendingPlayerUpdate(Predicate<PlayerState> matches) {
        return pendingPlayerUpdates.stream().anyMatch(matches);
    }

    protected final void updatePlayerStatus(PlayerState status) {
        updatePlayerStatus(status, null);
    }

    protected final void updatePlayerStatus(PlayerState status, Runnable onFailure) {
        PlayerState requestedState = new PlayerState(status);
        PlayerState pendingUpdate = new PlayerState(requestedState);
        pendingUpdate.setLastUpdateTime(getCurrentTime());
        pendingPlayerUpdates.add(pendingUpdate);
        playerExecutor.execute(() -> applyPlayerStatus(requestedState, pendingUpdate, onFailure));
    }

    private void applyPlayerStatus(PlayerState requestedState, PlayerState pendingUpdate, Runnable onFailure) {
        try {
            player.updateStatus(requestedState);
        } catch (Exception e) {
            log.error("Failed to update player status", e);
            if (onFailure != null) {
                executeEvent(onFailure);
            }
        } finally {
            executeEvent(() -> pendingPlayerUpdates.remove(pendingUpdate));
        }
    }

    protected abstract void sendKeepAlive();

    /**
     * @return The current system time in milliseconds.
     */
    protected long getCurrentTime() {
        return timingService.getCurrentTime();
    }

    protected abstract void onClose();

    @Override
    public final void close() {
        executeEvent(this::handleClose);
    }

    private void handleClose() {
        try {
            onClose();
        } finally {
            eventService.close();
            if (keepAliveTask != null) {
                keepAliveTask.cancel(false);
            }
            timingService.shutdown();
            playerExecutor.shutdownNow();
            networkExecutor.execute(this::closeNetwork);
            networkExecutor.shutdown();

            Thread shutdown = new Thread(this::awaitNetworkShutdown, "sync-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
    }

    private void awaitNetworkShutdown() {
        try {
            if (networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        networkExecutor.shutdownNow();
        closeNetwork();
    }

    private void closeNetwork() {
        try {
            getNetClient().close();
        } catch (Exception e) {
            log.warn("Failed to close network client", e);
        }
    }

}
