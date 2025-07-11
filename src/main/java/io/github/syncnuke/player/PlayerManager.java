package io.github.syncnuke.player;

import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import org.tinylog.Logger;
import org.tinylog.TaggedLogger;

import java.util.concurrent.TimeUnit;

/**
 * Manages a video player instance. Polls the player every {@link #UPDATE_COOLDOWN} milliseconds, ensuring our object representation
 * of the player always matches the current video player state. Handles thread safety. Soaks any updates to the video player
 * that are more frequent than our cooldown, ensuring that communication with our synchronization stack is accurate but done
 * at the requested rate.
 */
public class PlayerManager implements VideoPlayer, VideoPlayerEventListener {

    private static final TaggedLogger log = Logger.tag("PlayerManager");
    private static final int UPDATE_COOLDOWN = 30; // milliseconds
    private static final double DRIFT_THRESHOLD = 0.1; // %, error threshold for drift detection

    private static volatile PlayerManager instance;

    private final TimingService timingService;
    private final Object lock;
    private final PlayerState playerState;

    private VideoPlayer videoPlayer;
    private VideoPlayerEventListener eventListener;

    private PlayerManager() {
        this(new TimingServiceImpl());
    }

    private PlayerManager(TimingService timingService) {
        this.timingService = timingService;
        this.lock = new Object();
        this.playerState = new PlayerState();
    }

    public static PlayerManager getInstance() {
        return instance != null ? instance : new PlayerManager();
    }

    public void start(VideoPlayer videoPlayer) {
        synchronized (lock) {
            if (this.videoPlayer != null) {
                try {
                    this.videoPlayer.close();
                } catch (Exception e) {
                    log.error("Error closing previous video player: {}", e.getMessage());
                }
            }
            this.videoPlayer = videoPlayer;
            this.videoPlayer.setEventListener(this);
            updateState();
            schedule();
        }
    }

    private void schedule() {
        timingService.schedule(() -> {
            synchronized (lock) {
                if (videoPlayer == null) {
                    throw new IllegalStateException("Video player is not initialized.");
                }
                if (eventListener == null) {
                    log.warn("Event listener not yet set, skipping update.");
                    return;
                }
                // Trigger listener based on video player updates
                PlaybackState playerPlaybackState = videoPlayer.isPaused() ? PlaybackState.PAUSED : PlaybackState.PLAYING;
                boolean playbackStateChanged = !playerState.getPlaybackState().equals(playerPlaybackState);
                if (playbackStateChanged) {
                    playerState.setPosition(videoPlayer.getPosition());
                    if (playerPlaybackState.equals(PlaybackState.PAUSED)) {
                        eventListener.onPause();
                    } else {
                        eventListener.onPlay();
                    }
                    playerState.setLastUpdateTime(timingService.getCurrentTime());
                } else {
                    if (isSignificantProgressChange()) {
                        eventListener.onSeek(videoPlayer.getPosition());
                        playerState.setPosition(videoPlayer.getPosition());
                        playerState.setLastUpdateTime(timingService.getCurrentTime());
                    }
                }
            }
        }, UPDATE_COOLDOWN, UPDATE_COOLDOWN, TimeUnit.MILLISECONDS);
    }

    // Listener wrapper methods
    @Override
    public void onPlay() {
        updateState(eventListener::onPlay);
    }

    @Override
    public void onPause() {
        updateState(eventListener::onPause);
    }

    @Override
    public void onSeek(double position) {
        if (!isSignificantProgressChange()) {
            return;
        }
        updateState(() -> eventListener.onSeek(position));
    }

    private boolean isSignificantProgressChange() {
        double currentPosition = videoPlayer.getPosition();
        long currentTime = timingService.getCurrentTime();

        long timeDiff = currentTime - playerState.getLastUpdateTime();

        double expectedAdvance = timeDiff * playerState.getPlaybackSpeed();
        if ((long) expectedAdvance == 0) {
            // Prevent division by zero
            return false;
        }
        double positionDiff = Math.abs(currentPosition - playerState.getPosition()) * 1000; // in milliseconds
        if (positionDiff < UPDATE_COOLDOWN) {
            return false;
        }
        double relativeError = Math.abs(positionDiff - expectedAdvance) / expectedAdvance;

        // Progress change does not match expected change based on playback speed, so a seek has occurred
        return relativeError > DRIFT_THRESHOLD;
    }

    private void updateState(Runnable updateTask) {
        synchronized (lock) {
            if (videoPlayer == null) {
                throw new IllegalStateException("Attempted to send update request without a video player initialized.");
            }
            if (onCooldown()) {
                // The player's updating too fast, set it back to the last agreed state
                updatePlayer();
                log.debug("Update skipped due to cooldown. Player state reset to last known state.");
            } else {
                // Update the server state
                updateTask.run();
                updateState();
                log.debug("State update sent to server: {}", playerState);
            }
        }
    }

    private boolean onCooldown() {
        long currentTime = timingService.getCurrentTime();
        return currentTime - playerState.getLastUpdateTime() < UPDATE_COOLDOWN;
    }

    /**
     * Updates the video player state to match the synchronised state.
     */
    private void updatePlayer() {
        if (videoPlayer == null) {
            throw new IllegalStateException("Video player is not initialized.");
        }

        boolean playerPaused = videoPlayer.isPaused();
        boolean statePaused = playerState.getPlaybackState().equals(PlaybackState.PAUSED);

        // Play status should match the server
        if (playerPaused != statePaused) {
            if (statePaused) {
                videoPlayer.pause();
            } else {
                videoPlayer.play();
            }
        }

        // Playback progress should match the server
        if (!statePaused) {
            long currentTime = timingService.getCurrentTime();
            long timeDiff = currentTime - playerState.getLastUpdateTime();
            double expectedAdvance = timeDiff * playerState.getPlaybackSpeed();
            double newPosition = playerState.getPosition() + expectedAdvance / 1000.0; // Convert milliseconds to seconds
            playerState.setPosition(newPosition);
        }
        videoPlayer.seek(playerState.getPosition());

        // Playback speed should match the server
        if (videoPlayer.getPlaybackSpeed() != playerState.getPlaybackSpeed()) {
            videoPlayer.setPlaybackSpeed(playerState.getPlaybackSpeed());
        }
    }

    /**
     * Updates our shared state based on the current video player state.
     */
    private void updateState() {
        PlaybackState videoState = videoPlayer.isPaused() ? PlaybackState.PAUSED : PlaybackState.PLAYING;
        playerState.setPlaybackState(videoState);
        playerState.setPosition(videoPlayer.getPosition());
        playerState.setPlaybackSpeed(videoPlayer.getPlaybackSpeed());
        playerState.setLastUpdateTime(timingService.getCurrentTime());
    }

    // Player wrapper methods
    @Override
    public void play() {
        synchronized (lock) {
            playerState.setPlaybackState(PlaybackState.PLAYING);
            videoPlayer.play();
            playerState.setLastUpdateTime(timingService.getCurrentTime());
        }
        log.debug("Playing video player at position: {}", playerState.getPosition());
    }

    @Override
    public void pause() {
        synchronized (lock) {
            playerState.setPlaybackState(PlaybackState.PAUSED);
            videoPlayer.pause();
            playerState.setLastUpdateTime(timingService.getCurrentTime());
        }
        log.debug("Paused video player at position: {}", playerState.getPosition());
    }

    @Override
    public void seek(double position) {
        synchronized (lock) {
            playerState.setPosition(position);
            videoPlayer.seek(position);
            playerState.setLastUpdateTime(timingService.getCurrentTime());
        }
        log.debug("Set video player to position: {}", position);
    }

    @Override
    public void setPlaybackSpeed(double speed) {
        synchronized (lock) {
            if (speed <= 0) {
                throw new IllegalArgumentException("Playback speed must be greater than zero.");
            }
            playerState.setPlaybackSpeed(speed);
            videoPlayer.setPlaybackSpeed(speed);
            playerState.setLastUpdateTime(timingService.getCurrentTime());
        }
        log.debug("Set video player playback speed to: {}", speed);
    }

    @Override
    public double getPosition() {
        return playerState.getPosition();
    }

    @Override
    public double getPlaybackSpeed() {
        return playerState.getPlaybackSpeed();
    }

    @Override
    public boolean isPaused() {
        return playerState.getPlaybackState().equals(PlaybackState.PAUSED);
    }

    @Override
    public void load(String filePath) {
        videoPlayer.load(filePath);
    }

    @Override
    public void setEventListener(VideoPlayerEventListener eventListener) {
        this.eventListener = eventListener;
    }

    @Override
    public void close() throws Exception {
        synchronized (lock) {
            if (videoPlayer != null) {
                videoPlayer.close();
                videoPlayer = null;
            }
            timingService.shutdown();
        }
    }

}
