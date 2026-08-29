package io.github.syncnuke.client;

import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.internal.VideoPlayerEventListener;
import io.github.syncnuke.player.data.PlayerState;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wrapper class for SyncClient instances, handling instantiation and thread safety.
 */
@Slf4j
public class SyncManager implements VideoPlayerEventListener, AutoCloseable {

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile SyncManager instance;

    // Thread management variables
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile boolean starting = false;

    // Sync protocol variables
    private SyncClient<?> syncClient;
    private final VideoPlayer videoPlayer;
    private final PlayerManager playerManager;

    private SyncManager(VideoPlayer videoPlayer, PlayerManager playerManager) {
        this.videoPlayer = videoPlayer;
        this.playerManager = playerManager;
    }

    public static SyncManager getInstance(VideoPlayer videoPlayer) {
        Objects.requireNonNull(videoPlayer, "videoPlayer");
        if (instance != null && instance.videoPlayer.equals(videoPlayer)) {
            return instance;
        }

        PlayerManager playerManager = PlayerManager.create(videoPlayer);
        instance = new SyncManager(videoPlayer, playerManager);
        playerManager.setListener(instance);

        return instance;
    }

    public static SyncManager getInstance(VideoPlayer videoPlayer, long pollIntervalMillis) {
        Objects.requireNonNull(videoPlayer, "videoPlayer");
        if (instance != null && instance.videoPlayer.equals(videoPlayer)) {
            return instance;
        }

        PlayerManager playerManager = PlayerManager.create(videoPlayer, pollIntervalMillis);
        instance = new SyncManager(videoPlayer, playerManager);
        playerManager.setListener(instance);

        return instance;
    }

    public void start(String protocol, String server, int port, String username, String room) {
        synchronized (lock) {
            if (syncClient != null || starting) {
                return;
            }
            starting = true;
        }

        syncExecutor.submit(() -> {
            try {
                SyncClient<?> tmp = SyncClientFactory.createClient(
                        protocol,
                        server,
                        port,
                        playerManager
                );
                tmp.login(username, room);

                synchronized (lock) {
                    syncClient = tmp;
                }
            } catch (Exception e) {
                log.error("Failed to start SyncClient", e);
            } finally {
                starting = false;
            }
        });
    }

    @Override
    public void onStatusChange(PlayerState status) {
        syncExecutor.submit(() -> {
            synchronized (lock) {
                if (syncClient != null) {
                    syncClient.onStatusChange(status);
                }
            }
        });
    }

    public void stop() {
        SyncClient<?> toClose;
        synchronized (lock) {
            toClose = syncClient;
            syncClient = null;
        }

        if (toClose != null) {
            try {
                toClose.close();
            } catch (Exception e) {
                log.error("Error while disconnecting SyncClient: {}", e.getMessage());
            }
        }
    }

    /**
     * Stops any active sync session and shuts down the internal executor.
     * <p>
     * Without this, {@link #syncExecutor} (a non-daemon thread pool) would
     * keep the JVM alive indefinitely after the rest of the application has
     * shut down, since nothing else ever terminates it.
     */
    @Override
    public void close() {
        stop();
        syncExecutor.shutdownNow();
        try {
            playerManager.close();
        } catch (Exception e) {
            log.error("Error while closing video player: {}", e.getMessage());
        }
        synchronized (INSTANCE_LOCK) {
            if (instance == this) {
                instance = null;
            }
        }
    }

}
