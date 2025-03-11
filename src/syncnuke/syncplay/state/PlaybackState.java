package syncnuke.syncplay.state;

import lombok.Data;
import lombok.NoArgsConstructor;
import syncnuke.syncplay.data.FileData;

@Data
@NoArgsConstructor
public class PlaybackState {

    private FileData file;
    private double position;
    private boolean paused;
    private boolean doSeek;

    private long lastUpdateTime;
    private double playbackRate = 1.0;

    public PlaybackState(FileData file, double position, boolean paused, boolean doSeek) {
        this.file = file;
        this.position = position;
        this.paused = paused;
        this.doSeek = doSeek;
        lastUpdateTime = System.currentTimeMillis();
    }

    public void updateState(double position, boolean paused, boolean doSeek) {
        this.position = position;
        this.paused = paused;
        this.doSeek = doSeek;
    }

    public void updateFile(FileData file) {
        this.file = file;
    }

    public boolean hasFile() {
        return file != null;
    }

    public void clearSeek() {
        this.doSeek = false;
    }

    public void updatePosition() {
        double elapsedSeconds = (System.currentTimeMillis() - lastUpdateTime) / 1000.0;
        position += elapsedSeconds * playbackRate;
    }

    public double getPosition() {
        if (file != null && position > file.getDuration()) {
            // Stop playback at end of file
            paused = true;
            doSeek = false;
            position = file.getDuration();
        }
        return position;
    }

}
