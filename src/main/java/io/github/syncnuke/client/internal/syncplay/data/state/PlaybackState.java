package io.github.syncnuke.client.internal.syncplay.data.state;

import lombok.Data;
import lombok.NoArgsConstructor;
import io.github.syncnuke.client.internal.syncplay.data.dto.FileData;

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
