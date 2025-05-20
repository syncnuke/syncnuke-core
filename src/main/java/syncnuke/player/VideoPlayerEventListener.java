package syncnuke.player;

public interface VideoPlayerEventListener {
    void onPlay();
    void onPause();
    void onSeek(double position);
}
