package io.github.syncnuke.player;

import io.github.syncnuke.player.data.PlayerState;

public interface VideoPlayer extends AutoCloseable {
    void play();
    void pause();
    void seek(double position);
    void load(String filePath);
    PlayerState getStatus();
}
