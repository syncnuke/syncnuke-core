package syncnuke.syncplay.state;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import syncnuke.syncplay.data.FileData;

@Data
@NoArgsConstructor
public class PlaybackState {

    private FileData currentFile;
    private double position;
    private boolean paused;
    private boolean doSeek;

    private long lastUpdateTime;
    private double playbackRate = 1.0;

    public PlaybackState(FileData currentFile, double position, boolean paused, boolean doSeek) {
        this.currentFile = currentFile;
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
        this.currentFile = file;
    }

    public boolean hasFile() {
        return currentFile != null;
    }

    public void clearSeek() {
        this.doSeek = false;
    }

    public void updatePosition() {
        double elapsedSeconds = (System.currentTimeMillis() - lastUpdateTime) / 1000.0;
        position += elapsedSeconds * playbackRate;
    }

    public double getPosition() {
        if (position > currentFile.getDuration()) {
            // Stop playback at end of file
            position = currentFile.getDuration();
            paused = true;
            doSeek = false;
        }
        return position;
    }

}
