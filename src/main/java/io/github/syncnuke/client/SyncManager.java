package io.github.syncnuke.client;

import io.github.syncnuke.player.PlayerManager;
import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.VideoPlayerEventListener;
import io.github.syncnuke.player.data.PlayerState;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wrapper class for SyncClient instances, handling instantiation and thread safety.
 */
@Slf4j
public class SyncManager implements VideoPlayerEventListener {

    private static volatile SyncManager instance;

    // Thread management variables
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile boolean starting = false;

    // Sync protocol variables
    private SyncClient<?> syncClient;
    @Setter
    private PlayerManager videoPlayer;

    private SyncManager(PlayerManager videoPlayer) {
        this.videoPlayer = videoPlayer;
    }

    public static SyncManager getInstance(VideoPlayer videoPlayer) {
        if (instance != null && instance.videoPlayer.equals(videoPlayer)) {
            return instance;
        }
        PlayerManager playerManager;

        if (videoPlayer instanceof PlayerManager) {
            playerManager = (PlayerManager) videoPlayer;
        } else {
            throw new IllegalArgumentException("SyncManager requires a controlled PlayerManager.");
        }

        instance = new SyncManager(playerManager);
        playerManager.setListener(instance);

        return instance;
    }

    public void start(String protocol, String server, int port, String username, String room) {
        synchronized (lock) {
            if (videoPlayer == null || syncClient != null || starting) {
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
                        videoPlayer
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

}
