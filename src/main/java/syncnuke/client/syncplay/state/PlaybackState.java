package syncnuke.client.syncplay.state;

import lombok.Data;
import lombok.NoArgsConstructor;
import syncnuke.client.syncplay.dto.FileData;

@Data
@NoArgsConstructor
public class PlaybackState {

    private FileData file;
    private double position;
    private boolean paused;
    private boolean doSeek;

    public PlaybackState(FileData file, double position, boolean paused, boolean doSeek) {
        this.file = file;
        this.position = position;
        this.paused = paused;
        this.doSeek = doSeek;
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

}
