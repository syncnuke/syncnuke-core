package io.github.syncnuke.player.data;

public class PlayerStateUtils {

    /**
     * Returns a copy of the predicted player state at the given time, based on the current state and playback speed.
     */
    public static PlayerState advance(PlayerState state, long timeMilliseconds) {
        PlayerState predictedState = new PlayerState(state);
        if (predictedState.getPlaybackState() == PlaybackState.PLAYING) {
            double elapsedSeconds = (timeMilliseconds - predictedState.getLastUpdateTime()) / 1000.0;
            predictedState.setPosition(predictedState.getPosition() + elapsedSeconds * predictedState.getPlaybackSpeed());
        }
        return predictedState;
    }

}
