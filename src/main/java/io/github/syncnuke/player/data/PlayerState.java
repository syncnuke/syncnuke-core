package io.github.syncnuke.player.data;

import java.util.Objects;

import lombok.Data;

@Data
public class PlayerState {
    private PlaybackState playbackState = PlaybackState.PAUSED;
    /**
     * Playback position in seconds.
     */
    private double position;
    /**
     * Playback rate, e.g., 1.0 for normal speed, 0.5 for half speed, etc.
     */
    private double playbackSpeed = 1.0;
    /**
     * The time when the state was last updated, in milliseconds since epoch.
     */
    private long lastUpdateTime;

    public PlayerState() {
    }

    public PlayerState(PlayerState state) {
        Objects.requireNonNull(state, "state");
        this.playbackState = state.playbackState;
        this.position = state.position;
        this.playbackSpeed = state.playbackSpeed;
        this.lastUpdateTime = state.lastUpdateTime;
    }

    public PlayerState copy() {
        return new PlayerState(this);
    }

}
