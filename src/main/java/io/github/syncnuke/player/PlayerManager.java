package io.github.syncnuke.player;

import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import org.tinylog.Logger;
import org.tinylog.TaggedLogger;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Controls a raw {@link VideoPlayer} and maintains the last confirmed player
 * status observed through polling.
 */
// The backing player is owned here and closed explicitly on replacement/close.
@SuppressWarnings("resource")
public final class PlayerManager implements VideoPlayer {

    private static final TaggedLogger log = Logger.tag("PlayerManager");
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 50;
    private static final double POSITION_DRIFT_TOLERANCE_SECONDS = 0.2;
    private static final Object INSTANCE_LOCK = new Object();

    private static volatile PlayerManager instance;

    private final TimingService timingService;
    private final long pollIntervalMillis;
    private final Object stateLock = new Object();
    private final Object lifecycleLock = new Object();

    /**
     * Used only for polling and detecting significant changes. Not used directly for getStatus()
     */
    private PlayerState playerState = new PlayerState();
    private VideoPlayer videoPlayer;
    private VideoPlayerEventListener eventListener;
    private ScheduledFuture<?> pollingTask;
    private boolean closed;

    private PlayerManager() {
        this(new TimingServiceImpl());
    }

    private PlayerManager(TimingService timingService) {
        this(timingService, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    private PlayerManager(TimingService timingService, long pollIntervalMillis) {
        this.timingService = Objects.requireNonNull(timingService, "timingService");
        this.pollIntervalMillis = pollIntervalMillis <= 0 ? 0 : pollIntervalMillis;
    }

    public static PlayerManager getInstance() {
        return getInstance(DEFAULT_POLL_INTERVAL_MILLIS);
    }

    public static PlayerManager getInstance(long pollIntervalMillis) {
        PlayerManager current = instance;
        if (current != null) {
            return current;
        }
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = new PlayerManager(new TimingServiceImpl(), pollIntervalMillis);
            }
            return instance;
        }
    }

    public void start(VideoPlayer videoPlayer) {
        Objects.requireNonNull(videoPlayer, "videoPlayer");

        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Player manager is closed.");
            }

            VideoPlayer previousPlayer;
            synchronized (stateLock) {
                previousPlayer = this.videoPlayer;
                this.videoPlayer = null;
            }

            cancelPollingTask();
            if (previousPlayer != null && previousPlayer != videoPlayer) {
                closePreviousPlayer(previousPlayer);
            }

            PlayerState initialStatus = observe(videoPlayer);
            synchronized (stateLock) {
                this.videoPlayer = videoPlayer;
                this.playerState = initialStatus;
            }

            if (pollIntervalMillis > 0) {
                pollingTask = timingService.schedule(
                        this::poll,
                        pollIntervalMillis,
                        pollIntervalMillis,
                        TimeUnit.MILLISECONDS
                );
            }
        }
    }

    public void setListener(VideoPlayerEventListener eventListener) {
        synchronized (stateLock) {
            this.eventListener = eventListener;
        }
    }

    /**
     * Applies the available player commands needed to approach the desired
     * status. The desired value is not installed as confirmed state; a later
     * raw observation must confirm the result.
     */
    public void updateStatus(PlayerState desiredStatus) {
        PlayerState desired = copyAndValidate(desiredStatus);

        synchronized (lifecycleLock) {
            VideoPlayer player = requireVideoPlayer();
            PlayerState current;
            if (pollIntervalMillis == 0) {
                current = observe(player);
            } else {
                synchronized (stateLock) {
                    current = playerState.copy();
                }
            }

            if (current.getPlaybackState() != desired.getPlaybackState()) {
                if (desired.getPlaybackState() == PlaybackState.PAUSED) {
                    player.pause();
                } else {
                    player.play();
                }
            }

            long elapsedMillis = Math.max(0, timingService.getCurrentTime() - current.getLastUpdateTime());
            double expectedPosition = expectedPosition(current, elapsedMillis);
            double drift = Math.abs(desired.getPosition() - expectedPosition);
            if (drift > POSITION_DRIFT_TOLERANCE_SECONDS) {
                player.seek(desired.getPosition());
            }
        }
    }

    @Override
    public void play() {
        synchronized (lifecycleLock) {
            requireVideoPlayer().play();
        }
    }

    @Override
    public void pause() {
        synchronized (lifecycleLock) {
            requireVideoPlayer().pause();
        }
    }

    @Override
    public void seek(double position) {
        synchronized (lifecycleLock) {
            requireVideoPlayer().seek(position);
        }
    }

    @Override
    public void load(String filePath) {
        synchronized (lifecycleLock) {
            requireVideoPlayer().load(filePath);
        }
    }

    @Override
    public PlayerState getStatus() {
        synchronized (lifecycleLock) {
            return observe(requireVideoPlayer());
        }
    }

    private void poll() {
        try {
            VideoPlayer player;
            synchronized (stateLock) {
                player = videoPlayer;
            }
            if (player == null) {
                return;
            }

            PlayerState observed = observe(player);
            VideoPlayerEventListener listener = null;
            PlayerState eventStatus = null;

            synchronized (stateLock) {
                if (player != videoPlayer) {
                    return;
                }

                PlayerState previous = playerState;
                long elapsedMillis = Math.max(0, observed.getLastUpdateTime() - previous.getLastUpdateTime());
                boolean significantChange = hasSignificantChange(
                        previous,
                        observed,
                        elapsedMillis
                );

                playerState = observed;
                if (significantChange && eventListener != null) {
                    listener = eventListener;
                    eventStatus = observed;
                }
            }

            if (listener != null) {
                try {
                    listener.onStatusChange(eventStatus);
                } catch (Throwable error) {
                    log.error(error, "Video player status listener failed");
                }
            }
        } catch (Throwable error) {
            log.error(error, "Failed to poll video player status");
        }
    }

    private PlayerState observe(VideoPlayer player) {
        PlayerState observed = copyAndValidate(player.getStatus());
        observed.setLastUpdateTime(timingService.getCurrentTime());
        return observed;
    }

    private PlayerState copyAndValidate(PlayerState status) {
        PlayerState copy = new PlayerState(
                Objects.requireNonNull(status, "status")
        );
        if (copy.getPlaybackState() == null) {
            throw new IllegalArgumentException("Playback state must not be null.");
        }
        return copy;
    }

    /**
     * Filters listener notification only. The observed status is cached whether
     * this method returns true or false.
     */
    private boolean hasSignificantChange(
            PlayerState previous,
            PlayerState observed,
            long elapsedMillis
    ) {
        if (previous.getPlaybackState() != observed.getPlaybackState()) {
            return true;
        }
        if (Double.compare(
                previous.getPlaybackSpeed(),
                observed.getPlaybackSpeed()
        ) != 0) {
            return true;
        }

        double expectedPosition = expectedPosition(previous, elapsedMillis);
        return Math.abs(observed.getPosition() - expectedPosition)
                > POSITION_DRIFT_TOLERANCE_SECONDS;
    }

    private double expectedPosition(PlayerState status, long elapsedMillis) {
        if (status.getPlaybackState() != PlaybackState.PLAYING) {
            return status.getPosition();
        }
        double expectedAdvance = elapsedMillis / 1000.0 * status.getPlaybackSpeed();
        return status.getPosition() + expectedAdvance;
    }

    private VideoPlayer requireVideoPlayer() {
        VideoPlayer player;
        synchronized (stateLock) {
            player = videoPlayer;
        }
        if (player == null) {
            throw new IllegalStateException("Video player is not initialized.");
        }
        return player;
    }

    private void cancelPollingTask() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
    }

    private void closePreviousPlayer(VideoPlayer player) {
        try {
            player.close();
        } catch (Exception error) {
            log.error(error, "Error closing previous video player");
        }
    }

    @Override
    public void close() throws Exception {
        VideoPlayer playerToClose;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelPollingTask();
            synchronized (stateLock) {
                playerToClose = videoPlayer;
                videoPlayer = null;
                eventListener = null;
            }
        }

        Exception closeFailure = null;
        try {
            if (playerToClose != null) {
                playerToClose.close();
            }
        } catch (Exception error) {
            closeFailure = error;
        } finally {
            timingService.shutdown();
            synchronized (INSTANCE_LOCK) {
                if (instance == this) {
                    instance = null;
                }
            }
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
