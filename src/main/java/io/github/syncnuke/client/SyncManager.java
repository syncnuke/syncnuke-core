package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.protocol.master.MasterClient;
import io.github.syncnuke.client.internal.protocol.master.data.ConnectData;
import io.github.syncnuke.player.MediaPlayer;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.player.internal.MediaPlayerEventListener;
import io.github.syncnuke.player.data.PlayerState;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Wrapper class for SyncClient instances, handling instantiation and thread safety.
 */
@Slf4j
public class SyncManager implements MediaPlayerEventListener, AutoCloseable {

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile SyncManager instance;

    // Thread management variables
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sync-startup");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private Future<?> startupTask;
    private int startCount;
    private boolean closed;

    // Sync protocol variables
    private SyncClient<?> syncClient;
    private final MediaPlayer mediaPlayer;
    private final PlayerManager playerManager;

    private SyncManager(MediaPlayer mediaPlayer, PlayerManager playerManager) {
        this.mediaPlayer = mediaPlayer;
        this.playerManager = playerManager;
    }

    public static SyncManager getInstance(MediaPlayer mediaPlayer) {
        Objects.requireNonNull(mediaPlayer, "mediaPlayer");
        synchronized (INSTANCE_LOCK) {
            if (instance != null && instance.mediaPlayer.equals(mediaPlayer)) {
                return instance;
            }

            PlayerManager playerManager = PlayerManager.create(mediaPlayer);
            instance = new SyncManager(mediaPlayer, playerManager);
            playerManager.setListener(instance);

            return instance;
        }
    }

    public static SyncManager getInstance(MediaPlayer mediaPlayer, long pollIntervalMillis) {
        Objects.requireNonNull(mediaPlayer, "mediaPlayer");
        synchronized (INSTANCE_LOCK) {
            if (instance != null && instance.mediaPlayer.equals(mediaPlayer)) {
                return instance;
            }
            PlayerManager playerManager = PlayerManager.create(mediaPlayer, pollIntervalMillis);
            instance = new SyncManager(mediaPlayer, playerManager);
            playerManager.setListener(instance);
            return instance;
        }
    }

    public void start(String protocol, String server, int port, String username, String room, String password) {
        synchronized (lock) {
            if (closed || syncClient != null || startupTask != null) {
                return;
            }
            int startId = ++startCount;
            startupTask = syncExecutor.submit(() -> {
                SyncClient<?> client = null;
                try {
                    ConnectData endpoint;
                    try (MasterClient masterClient = new MasterClient()) {
                        endpoint = masterClient.join(
                                server,
                                port,
                                protocol,
                                // Version of the protocol supported by the current revision of this library
                                SyncClientFactory.protocolVersion(protocol),
                                room,
                                password
                        );
                    }
                    client = SyncClientFactory.createClient(
                            endpoint.getProtocol(),
                            endpoint.getVersion(),
                            endpoint.getHost(),
                            endpoint.getPort(),
                            playerManager
                    );
                    synchronized (lock) {
                        if (closed || startId != startCount) {
                            return;
                        }
                        client.login(username, room, password);
                        syncClient = client;
                        client = null;
                    }
                } catch (Exception e) {
                    log.error("Failed to start SyncClient", e);
                } finally {
                    if (client != null) {
                        client.close();
                    }
                    synchronized (lock) {
                        if (startId == startCount) {
                            startupTask = null;
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onStatusChange(PlayerState status) {
        synchronized (lock) {
            if (syncClient != null) {
                syncClient.onStatusChange(status);
            }
        }
    }

    public void stop() {
        SyncClient<?> toClose;
        synchronized (lock) {
            ++startCount; // Invalidate any startup still in progress
            if (startupTask != null) {
                startupTask.cancel(true);
                startupTask = null;
            }
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
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        stop();
        syncExecutor.shutdownNow();
        try {
            playerManager.close();
        } catch (Exception e) {
            log.error("Error while closing media player: {}", e.getMessage());
        }
        synchronized (INSTANCE_LOCK) {
            if (instance == this) {
                instance = null;
            }
        }
    }

}
