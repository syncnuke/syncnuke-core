package io.github.syncnuke.player.data;

import lombok.Data;

@Data
public class PlayerState {
    private PlaybackState playbackState; // TODO: set default if null errors occur
    /**
     * Playback position in seconds.
     */
    private double position;
    /**
     * Playback rate, e.g., 1.0 for normal speed, 0.5 for half speed, etc.
     */
    private double playbackSpeed;
    /**
     * The time when the state was last updated, in milliseconds since epoch.
     */
    private long lastUpdateTime;
}
