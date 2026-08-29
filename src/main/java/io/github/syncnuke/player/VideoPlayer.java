package io.github.syncnuke.player;

import io.github.syncnuke.player.data.PlayerState;

/**
 * Transport-independent view of the SyncNuke Player API.
 */
public interface VideoPlayer extends AutoCloseable {
    void play();
    void pause();
    void seek(double position);
    void setPlaybackSpeed(double playbackSpeed);
    void load(String file);
    PlayerState getStatus();
}
