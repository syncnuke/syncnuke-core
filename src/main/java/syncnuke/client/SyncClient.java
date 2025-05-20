package syncnuke.client;

import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import syncnuke.player.VideoPlayer;
import syncnuke.player.VideoPlayerEventListener;
import syncnuke.tcp.TcpClient;

@Slf4j
public abstract class SyncClient extends TcpClient implements VideoPlayer, VideoPlayerEventListener {

    @Delegate(types = VideoPlayer.class)
    private final VideoPlayer videoPlayer;

    // Drift tracking
    private volatile int prevStatus = 1; // 1 = playing, 0 = paused
    private volatile double prevProgress = 0;
    private volatile long prevProgTime = System.currentTimeMillis();

    protected SyncClient(String host, int port, VideoPlayer videoPlayer) {
        super(host, port);
        this.videoPlayer = videoPlayer;
        this.videoPlayer.setEventListener(this);
    }

    public abstract void login(String username, String room);

    /**
     * Used to filter out video state updates that are too minor to tigger server notifications.
     * @param currentStatus     the status of the video player (1 = playing, 0 = paused)
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

    @Override
    public void close() {
        super.close();
        try {
            videoPlayer.close();
        } catch (Exception e) {
            log.warn("Failed to close VideoPlayer: {}", e.getMessage());
        }
    }

}
