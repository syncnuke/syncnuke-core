package io.github.syncnuke.player;

public interface VideoPlayerEventListener {
    void onPlay();
    void onPause();
    void onSeek(double position);
    // TODO: Add onSpeedChange(double speed);
}
