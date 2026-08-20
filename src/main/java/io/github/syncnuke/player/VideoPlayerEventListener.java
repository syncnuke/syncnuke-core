package io.github.syncnuke.player;

import io.github.syncnuke.player.data.PlayerState;

@FunctionalInterface
public interface VideoPlayerEventListener {
    void onStatusChange(PlayerState status);
}
