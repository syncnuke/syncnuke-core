package syncnuke.syncplay.player;

public interface VideoPlayer extends AutoCloseable {
    void play();
    void pause();
    void seek(double position);
    double getPosition();
    boolean isPaused();
    void setEventListener(VideoPlayerEventListener eventListener);
}
