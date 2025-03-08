package syncnuke.syncplay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import syncnuke.syncplay.data.FileData;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientState {

    private FileData currentFile;
    private double position;
    private boolean paused;
    private boolean doSeek;

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
}
