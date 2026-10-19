package io.github.syncnuke.player.internal;

import io.github.syncnuke.player.NoMediaLoadedException;
import io.github.syncnuke.player.MediaPlayer;

import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.service.TimingService;
import io.github.syncnuke.service.TimingServiceImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates a {@link MediaPlayer} and maintains the last confirmed player
 * status observed through polling. Filters out excessive state updates,
 * including seek drift of up to {@value #POSITION_DRIFT_TOLERANCE_SECONDS} seconds.
 */
@Slf4j
public final class PlayerManager implements AutoCloseable {

    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 250;
    private static final double POSITION_DRIFT_TOLERANCE_SECONDS = 0.2;

    private final TimingService timingService;
    private final long pollIntervalMillis;
    private final Object stateLock = new Object();
    private final Object lifecycleLock = new Object();

    /**
     * Used only for polling and detecting significant changes. Not used directly for getStatus()
     */
    private PlayerState lastPlayerState = new PlayerState();
    private MediaPlayer mediaPlayer;
    private MediaPlayerEventListener eventListener;
    private ScheduledFuture<?> pollingTask;
    private boolean closed;

    private PlayerManager(TimingService timingService, long pollIntervalMillis) {
        this.timingService = Objects.requireNonNull(timingService, "timingService");
        this.pollIntervalMillis = pollIntervalMillis <= 0 ? 0 : pollIntervalMillis;
    }

    public static PlayerManager create(MediaPlayer mediaPlayer) {
        return create(mediaPlayer, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    public static PlayerManager create(MediaPlayer mediaPlayer, long pollIntervalMillis) {
        PlayerManager playerManager = new PlayerManager(new TimingServiceImpl(), pollIntervalMillis);
        playerManager.start(mediaPlayer);
        return playerManager;
    }

    public void start(MediaPlayer mediaPlayer) {
        Objects.requireNonNull(mediaPlayer, "mediaPlayer");

        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Player manager is closed.");
            }

            MediaPlayer previousPlayer;
            synchronized (stateLock) {
                previousPlayer = this.mediaPlayer;
                this.mediaPlayer = null;
            }

            cancelPollingTask();
            if (previousPlayer != null && previousPlayer != mediaPlayer) {
                closePreviousPlayer(previousPlayer);
            }

            PlayerState initialStatus = observe(mediaPlayer);
            synchronized (stateLock) {
                this.mediaPlayer = mediaPlayer;
                this.lastPlayerState = initialStatus;
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

    public void setListener(MediaPlayerEventListener eventListener) {
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
            MediaPlayer player = requireMediaPlayer();
            PlayerState current = observe(player);

            if (current.getPlaybackState() != desired.getPlaybackState()) {
                if (desired.getPlaybackState() == PlaybackState.PAUSED) {
                    player.pause();
                } else {
                    player.play();
                }
            }

            if (Double.compare(current.getPlaybackSpeed(), desired.getPlaybackSpeed()) != 0) {
                player.setPlaybackSpeed(desired.getPlaybackSpeed());
            }

            long elapsedMillis = Math.max(0, timingService.getCurrentTime() - current.getLastUpdateTime());
            double expectedPosition = expectedPosition(current, elapsedMillis);
            double drift = Math.abs(desired.getPosition() - expectedPosition);
            if (drift > POSITION_DRIFT_TOLERANCE_SECONDS) {
                player.seek(desired.getPosition());
            }
        }
    }

    public PlayerState getStatus() {
        synchronized (lifecycleLock) {
            return observe(requireMediaPlayer());
        }
    }

    private void poll() {
        try {
            MediaPlayer player;
            synchronized (stateLock) {
                player = mediaPlayer;
            }
            if (player == null) {
                return;
            }

            PlayerState observed = observe(player);
            MediaPlayerEventListener listener = null;
            PlayerState eventStatus = null;

            synchronized (stateLock) {
                if (player != mediaPlayer) {
                    return;
                }

                PlayerState previous = lastPlayerState;
                long elapsedMillis = Math.max(0, observed.getLastUpdateTime() - previous.getLastUpdateTime());
                boolean significantChange = hasSignificantChange(
                        previous,
                        observed,
                        elapsedMillis
                );

                lastPlayerState = observed;
                if (significantChange && eventListener != null) {
                    listener = eventListener;
                    eventStatus = new PlayerState(observed);
                }
            }

            if (listener != null) {
                try {
                    listener.onStatusChange(eventStatus);
                } catch (Throwable error) {
                    log.error("Media player status listener failed", error);
                }
            }
        } catch (NoMediaLoadedException ignored) {
            // We're not responsible for handling this, should continue polling
            log.debug("No media is currently loaded");
        } catch (RuntimeException error) {
            log.debug("Player status polling failure", error);
        }
    }

    private PlayerState observe(MediaPlayer player) {
        return copyAndValidate(player.getStatus());
    }

    private PlayerState copyAndValidate(PlayerState status) {
        PlayerState copy = new PlayerState(
                Objects.requireNonNull(status, "status")
        );
        if (copy.getPlaybackState() == null) {
            throw new IllegalArgumentException("Playback state must not be null.");
        }
        if (!Double.isFinite(copy.getPosition()) || copy.getPosition() < 0) {
            throw new IllegalArgumentException("Position must be a non-negative finite number.");
        }
        if (!Double.isFinite(copy.getPlaybackSpeed()) || copy.getPlaybackSpeed() <= 0) {
            throw new IllegalArgumentException("Playback speed must be a positive finite number.");
        }
        if (copy.getLastUpdateTime() < 0) {
            throw new IllegalArgumentException("Last update time must not be negative.");
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
        if (!Objects.equals(previous.getFileName(), observed.getFileName())
                || Double.compare(previous.getDuration(), observed.getDuration()) != 0) {
            return true;
        }
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

    private MediaPlayer requireMediaPlayer() {
        MediaPlayer player;
        synchronized (stateLock) {
            player = mediaPlayer;
        }
        if (player == null) {
            throw new IllegalStateException("Media player is not initialized.");
        }
        return player;
    }

    private void cancelPollingTask() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
    }

    private void closePreviousPlayer(MediaPlayer player) {
        try {
            player.close();
        } catch (Exception error) {
            log.error("Error closing previous media player", error);
        }
    }

    @Override
    public void close() throws Exception {
        MediaPlayer playerToClose;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelPollingTask();
            synchronized (stateLock) {
                playerToClose = mediaPlayer;
                mediaPlayer = null;
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
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
