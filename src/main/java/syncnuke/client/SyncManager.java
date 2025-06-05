package syncnuke.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import syncnuke.player.VideoPlayer;
import syncnuke.player.VideoPlayerEventListener;

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
    private VideoPlayer videoPlayer;

    private SyncManager(VideoPlayer videoPlayer) {
        this.videoPlayer = videoPlayer;
    }

    public static SyncManager getInstance(VideoPlayer videoPlayer) {
        if (instance != null && instance.videoPlayer.equals(videoPlayer)) {
            return instance;
        }
        instance = new SyncManager(videoPlayer);
        videoPlayer.setEventListener(instance);

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
    public void onPlay() {
        syncExecutor.submit(() -> {
            synchronized (lock) {
                if (syncClient != null) {
                    syncClient.onPlay();
                }
            }
        });
    }

    @Override
    public void onPause() {
        syncExecutor.submit(() -> {
            synchronized (lock) {
                if (syncClient != null) {
                    syncClient.onPause();
                }
            }
        });
    }

    @Override
    public void onSeek(double position) {
        syncExecutor.submit(() -> {
            synchronized (lock) {
                if (syncClient != null) {
                    syncClient.onSeek(position);
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
