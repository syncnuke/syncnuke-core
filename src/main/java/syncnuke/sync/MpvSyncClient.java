package syncnuke.sync;

import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.player.VideoPlayer;
import syncnuke.syncplay.player.VideoPlayerEventListener;
import syncnuke.tcp.TcpClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MpvSyncClient extends TcpClient implements VideoPlayerEventListener {

    private final ThreadLocal<Boolean> serverCommandInProgress = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean ignoreUpdates = new AtomicBoolean(false);
    private final double debounceDelay;
    private final VideoPlayer videoPlayer;
    private volatile int prevStatus = 1; // 1 = playing, 0 = paused
    private volatile double prevProgress = 0;
    private volatile long prevProgTime = System.currentTimeMillis();

    public MpvSyncClient(String host, int port, double debounceDelay, VideoPlayer videoPlayer) {
        super(host, port);
        this.debounceDelay = debounceDelay;
        this.videoPlayer = videoPlayer;
        this.videoPlayer.setEventListener(this);
    }

    public MpvSyncClient(String host, int port, VideoPlayer videoPlayer) {
        this(host, port, 0, videoPlayer);
    }

    @Override
    protected void handleResponse(String line) {
        try {
            byte[] data = line.getBytes(StandardCharsets.ISO_8859_1);
            if (data.length < 3) {  // We need at least command index + status byte + some position data
                log.warn("Invalid response length: {}", data.length);
                return;
            }

            int commandIndex = data[0] & 0xFF;
            int statusByte = data[1] & 0xFF;
            long progressSeconds = bytesToLong(data);

            if (!ignoreUpdates.get()) {
                boolean isPaused = videoPlayer.isPaused();
                if ((statusByte == 1 && isPaused) || (statusByte == 0 && !isPaused)) {
                    serverCommandInProgress.set(true);
                    try {
                        if (statusByte == 1) {
                            videoPlayer.play();
                            log.info("Play command executed from server");
                        } else {
                            videoPlayer.pause();
                            log.info("Pause command executed from server");
                        }
                        if (isSignificantChange(statusByte, progressSeconds)) {
                            videoPlayer.seek(progressSeconds);
                            log.info("Synchronized seek with server during pause change: {}", progressSeconds);
                        }
                    } finally {
                        serverCommandInProgress.set(false);
                    }
                    prevStatus = statusByte;
                    prevProgress = progressSeconds;
                    prevProgTime = System.currentTimeMillis();
                }

                double currentProgress = videoPlayer.getPosition();
                if (commandIndex == 3 && Math.abs(currentProgress - progressSeconds) > 1) {
                    videoPlayer.seek(progressSeconds);
                    log.info("Seek command executed from server: {}", progressSeconds);
                    prevProgress = progressSeconds;
                    prevProgTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            log.error("Error processing server response: {}", e.getMessage());
        }
    }

    @Override
    public void onPlay() {
        if (serverCommandInProgress.get()) return;
        log.debug("Play event detected");
        
        // Track status change
        int currentStatus = 1;
        double currentProgress = videoPlayer.getPosition();
        
        if (isSignificantChange(currentStatus, currentProgress)) {
            sendState(currentStatus, currentProgress);
        }
        
        prevStatus = currentStatus;
        prevProgress = currentProgress;
        prevProgTime = System.currentTimeMillis();
    }

    @Override
    public void onPause() {
        if (serverCommandInProgress.get()) return;
        log.debug("Pause event detected");
        
        // Track status change
        int currentStatus = 0;
        double currentProgress = videoPlayer.getPosition();
        
        if (isSignificantChange(currentStatus, currentProgress)) {
            sendState(currentStatus, currentProgress);
        }
        
        prevStatus = currentStatus;
        prevProgress = currentProgress;
        prevProgTime = System.currentTimeMillis();
    }

    @Override
    public void onSeek(double position) {
        if (serverCommandInProgress.get()) return;
        log.debug("Seek event detected: {}", position);
        
        // Track position change
        int currentStatus = videoPlayer.isPaused() ? 0 : 1;
        
        if (isSignificantChange(currentStatus, position)) {
            sendState(currentStatus, position);
        }
        
        prevStatus = currentStatus;
        prevProgress = position;
        prevProgTime = System.currentTimeMillis();
    }

    private boolean isSignificantChange(int currentStatus, double currentProgress) {
        long currentTime = System.currentTimeMillis();
        double positionDiff = Math.abs(currentProgress - prevProgress);
        double timeDiff = (currentTime - prevProgTime) / 1000.0;
        return currentStatus != prevStatus || positionDiff > 1 || timeDiff > 1;
    }

    private void sendState(int status, double progress) {
        try {
            byte[] message = new byte[10];
            message[0] = 3; // Command index
            message[1] = (byte) status;
            long progressSeconds = (long) progress;
            byte[] progressBytes = longToBytes(progressSeconds);
            System.arraycopy(progressBytes, 0, message, 2, 8);

            ignoreUpdates.set(true);
            log.info("Sending state: status={}, progress={}", status, progress);
            send(new String(message, StandardCharsets.ISO_8859_1));
            Thread.sleep((long) (debounceDelay * 1000));
        } catch (InterruptedException e) {
            log.error("Failed to send state: {}", e.getMessage());
        } finally {
            ignoreUpdates.set(false);
        }
    }

    private byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[7 - i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    private long bytesToLong(byte[] bytes) {
        long value = 0;
        for (int i = 2; i < bytes.length; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }
}
