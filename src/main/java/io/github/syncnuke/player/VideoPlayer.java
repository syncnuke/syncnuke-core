package io.github.syncnuke.player;

public interface VideoPlayer extends AutoCloseable {
    void play();
    void pause();
    void seek(double position);
    double getPosition();
    void setPlaybackSpeed(double speed);
    double getPlaybackSpeed();
    // TODO: Add setPlaybackSpeed(double speed);
    boolean isPaused();
    void load(String filePath);
    void setEventListener(VideoPlayerEventListener eventListener);
}
